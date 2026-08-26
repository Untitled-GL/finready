package io.finready.coverage;

import io.finready.product.CoveragePolicy;
import io.finready.product.ProductRisk;
import io.finready.product.ProductRiskRepository;
import io.finready.session.ConsultationRevision;
import io.finready.session.ConsultationRevisionRepository;
import io.finready.session.ConsultationSession;
import io.finready.session.SessionStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 저장된 Coverage 결과를 읽어 계약 응답으로 되살린다. <b>LLM 을 부르지 않는다.</b>
 *
 * <p>{@code GET /sessions/{id}} 의 {@code coverage} 필드와 {@link CoverageAnalysisService} 의
 * 멱등 재사용 경로가 <b>같은 코드를 쓰게 하려고</b> 뺐다. 응답을 만드는 곳이 둘이면
 * Gate 재계산 규칙이 갈라지고, 새로고침 결과가 분석 직후 결과와 달라진다.
 *
 * <p>Gate 는 저장하지 않고 매번 다시 판정한다 — {@code gate_override} 가 추가되면 같은
 * {@code coverage_result} 로도 결론이 바뀌기 때문이다(규칙 2, 합성 상태를 저장하지 않는다).
 */
@Service
public class CoverageQueryService {

	private final ConsultationRevisionRepository revisionRepository;
	private final CoverageResultRepository coverageResultRepository;
	private final ProductRiskRepository productRiskRepository;
	private final GateOverrideRepository gateOverrideRepository;
	private final GateEvaluator gateEvaluator;

	public CoverageQueryService(ConsultationRevisionRepository revisionRepository,
	                            CoverageResultRepository coverageResultRepository,
	                            ProductRiskRepository productRiskRepository,
	                            GateOverrideRepository gateOverrideRepository,
	                            GateEvaluator gateEvaluator) {
		this.revisionRepository = revisionRepository;
		this.coverageResultRepository = coverageResultRepository;
		this.productRiskRepository = productRiskRepository;
		this.gateOverrideRepository = gateOverrideRepository;
		this.gateEvaluator = gateEvaluator;
	}

	/**
	 * 최신 revision 의 Coverage 결과. 아직 분석 전이면 비어 있다.
	 *
	 * <p>계약이 {@code coverage: null} 을 허용하므로 없는 것은 오류가 아니다 —
	 * DRAFT 세션을 새로고침한 정상적인 경우다.
	 */
	public Optional<CoverageResponse> latestFor(ConsultationSession session) {
		return latestFor(session,
				revisionRepository.findTopBySessionIdOrderByRevisionNoDesc(session.getId()));
	}

	/**
	 * {@link #latestFor(ConsultationSession)} 와 같지만, 호출자가 최신 revision 을
	 * 이미 조회해뒀다면 그 결과를 넘겨 중복 쿼리를 피한다(TRD §14.1, 리전 교차 왕복 절감).
	 * {@code GET /sessions/{id}} 가 이 오버로드를 쓴다 — 스냅샷 응답에도 같은 revision 이
	 * 필요해서 어차피 먼저 조회돼 있다.
	 */
	public Optional<CoverageResponse> latestFor(ConsultationSession session,
	                                            Optional<ConsultationRevision> currentRevision) {
		return coverageOf(session, currentRevision, overridesOf(session.getId()));
	}

	/**
	 * F08 리포트 전용. Coverage 섹션과 {@code overrides} 섹션이 같은 {@code gate_override}
	 * 조회를 공유한다 — 따로 부르면({@link #latestFor}로 Coverage를, 별도 호출로 overrides를
	 * 각각 읽으면) 세션당 왕복이 하나 더 든다(TRD §14.1, 리전 교차 비용).
	 */
	public CoverageReportSections reportSectionsOf(ConsultationSession session) {
		Map<String, GateOverride> overrides = overridesOf(session.getId());
		Optional<CoverageResponse> coverage = coverageOf(session,
				revisionRepository.findTopBySessionIdOrderByRevisionNoDesc(session.getId()),
				overrides);
		List<CoverageResponse.OverrideView> overrideViews = overrides.values().stream()
				.map(CoverageResponse.OverrideView::from)
				.toList();
		return new CoverageReportSections(coverage, overrideViews);
	}

	/** {@link #reportSectionsOf} 의 반환값. {@code GateOverride} 엔티티는 이 안에서도 나가지 않는다. */
	public record CoverageReportSections(Optional<CoverageResponse> coverage,
	                                     List<CoverageResponse.OverrideView> overrides) {
	}

	private Optional<CoverageResponse> coverageOf(ConsultationSession session,
	                                              Optional<ConsultationRevision> currentRevision,
	                                              Map<String, GateOverride> overrides) {
		return currentRevision.flatMap(revision -> {
			List<CoverageResult> results = coverageResultRepository
					.findByRevisionIdOrderByRiskIdAsc(revision.getId());
			if (results.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(respond(session.getStatus(), session.getId(),
					revision.getId(), results, analysableRisks(session.getProductId()),
					overrides,
					// 이번 호출에서 잰 레이턴시가 없다. 0 을 넣으면 "0ms 에 끝났다"로 읽혀
					// 관측 데이터가 오염된다
					null));
		});
	}

	/**
	 * 저장된 결과 + 현재 Override 로 Gate 를 다시 판정해 응답을 만든다.
	 *
	 * <p>{@link CoverageAnalysisService} 의 멱등 재사용 경로가 쓴다 — Override 를
	 * 아직 조회해두지 않았으므로 여기서 새로 읽는다.
	 */
	CoverageResponse respond(SessionStatus status,
	                         String sessionId,
	                         Long revisionId,
	                         List<CoverageResult> results,
	                         Map<String, ProductRisk> risksById,
	                         CoverageResponse.AnalysisView analysis) {
		return respond(status, sessionId, revisionId, results, risksById,
				overridesOf(sessionId), analysis);
	}

	/** Override 를 이미 조회해둔 호출자용 — {@link #coverageOf} 가 쓴다. */
	private CoverageResponse respond(SessionStatus status,
	                                 String sessionId,
	                                 Long revisionId,
	                                 List<CoverageResult> results,
	                                 Map<String, ProductRisk> risksById,
	                                 Map<String, GateOverride> overrides,
	                                 CoverageResponse.AnalysisView analysis) {
		GateEvaluator.GateVerdict verdict =
				gateEvaluator.evaluate(results, policiesOf(risksById), overrides.keySet());

		return CoverageResponse.of(sessionId, revisionId, status, verdict, results,
				risksById, overrides, analysis);
	}

	/**
	 * {@code NOT_APPLICABLE} 은 분석 대상이 아니다 — 상품에 해당하지 않는 Risk 를
	 * "설명 안 됨"으로 세면 Gate 가 영원히 안 열린다.
	 */
	Map<String, ProductRisk> analysableRisks(String productId) {
		return productRiskRepository.findByProductIdOrderByRiskIdAsc(productId).stream()
				.filter(risk -> risk.getCoveragePolicy() != CoveragePolicy.NOT_APPLICABLE)
				.collect(Collectors.toMap(ProductRisk::getRiskId, Function.identity(),
						(first, second) -> first, LinkedHashMap::new));
	}

	Map<String, GateOverride> overridesOf(String sessionId) {
		return gateOverrideRepository.findBySessionIdOrderByRiskIdAsc(sessionId).stream()
				.collect(Collectors.toMap(GateOverride::getRiskId, Function.identity(),
						(first, second) -> first, LinkedHashMap::new));
	}

	private Map<String, CoveragePolicy> policiesOf(Map<String, ProductRisk> risksById) {
		return risksById.entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey,
						entry -> entry.getValue().getCoveragePolicy(),
						(first, second) -> first, LinkedHashMap::new));
	}
}

package io.finready.report;

import io.finready.audit.AuditEventRepository;
import io.finready.common.ApiException;
import io.finready.common.ErrorCode;
import io.finready.coverage.CoverageQueryService;
import io.finready.coverage.CoverageResponse;
import io.finready.product.Product;
import io.finready.product.ProductRepository;
import io.finready.session.CloseEligibility;
import io.finready.session.CloseEligibilityEvaluator;
import io.finready.session.ConsultationRevisionRepository;
import io.finready.session.ConsultationSession;
import io.finready.session.ConsultationSessionRepository;
import io.finready.session.RevisionResponse;
import io.finready.understanding.RiskUnderstandingState;
import io.finready.understanding.UnderstandingQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * F08 리포트 조회.
 *
 * <p><b>아무것도 계산하지 않는다.</b> Coverage 섹션은 {@link CoverageQueryService},
 * Understanding 섹션은 {@link UnderstandingQueryService}, 종료 조건은
 * {@link CloseEligibilityEvaluator} 가 만든 것을 모아 담을 뿐이다.
 *
 * <p>여기서 다시 조립하면 <b>리포트의 Gate 판정이 화면의 Gate 판정과 달라질 수 있다.</b>
 * 상담 중에는 통과였는데 리포트에서는 막혀 보이는 종류의 어긋남은, 발견해도 어느 쪽이
 * 맞는지 판단할 근거가 없다. {@code GET /sessions/{id}} 스냅샷을 만들 때와 같은 판단이다.
 *
 * <p><b>LLM 을 부르지 않는다.</b> 리포트를 몇 번 열어도 요금이 들지 않고 판정도 바뀌지 않는다.
 */
@Service
public class ReportService {

	private final ConsultationSessionRepository sessionRepository;
	private final ConsultationRevisionRepository revisionRepository;
	private final ProductRepository productRepository;
	private final AuditEventRepository auditEventRepository;
	private final CoverageQueryService coverageQueryService;
	private final UnderstandingQueryService understandingQueryService;
	private final CloseEligibilityEvaluator closeEligibilityEvaluator;

	public ReportService(ConsultationSessionRepository sessionRepository,
	                     ConsultationRevisionRepository revisionRepository,
	                     ProductRepository productRepository,
	                     AuditEventRepository auditEventRepository,
	                     CoverageQueryService coverageQueryService,
	                     UnderstandingQueryService understandingQueryService,
	                     CloseEligibilityEvaluator closeEligibilityEvaluator) {
		this.sessionRepository = sessionRepository;
		this.revisionRepository = revisionRepository;
		this.productRepository = productRepository;
		this.auditEventRepository = auditEventRepository;
		this.coverageQueryService = coverageQueryService;
		this.understandingQueryService = understandingQueryService;
		this.closeEligibilityEvaluator = closeEligibilityEvaluator;
	}

	@Transactional(readOnly = true)
	public ReportResponse getReport(String sessionId) {
		ConsultationSession session = sessionRepository.findById(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND,
						"상담 세션을 찾을 수 없습니다."));

		CoverageQueryService.CoverageReportSections coverageSections =
				coverageQueryService.reportSectionsOf(session);
		Optional<CoverageResponse> coverage = coverageSections.coverage();
		List<RiskUnderstandingState> understanding = understandingQueryService.statesOf(session);
		List<String> unresolvedRiskIds = closeEligibilityEvaluator.unresolvedRiskIds(understanding);

		CloseEligibility closeEligibility = closeEligibilityEvaluator.evaluate(
				session.getStatus(),
				coverage.map(CoverageResponse::warningRiskIds).orElseGet(List::of),
				unresolvedRiskIds);

		return new ReportResponse(
				session.getId(),
				session.getStatus(),
				productViewOf(session),
				coverage.map(ReportService::sectionOf).orElse(ReportResponse.CoverageSection.EMPTY),
				understanding,
				coverageSections.overrides(),
				revisionRepository.findBySessionIdOrderByRevisionNoAsc(sessionId).stream()
						.map(RevisionResponse::from)
						.toList(),
				auditEventRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId).stream()
						.map(ReportResponse.AuditEventView::from)
						.toList(),
				unresolvedRiskIds,
				closeEligibility,
				ReportResponse.DISCLAIMER);
	}

	private static ReportResponse.CoverageSection sectionOf(CoverageResponse coverage) {
		return new ReportResponse.CoverageSection(
				coverage.revisionId(), coverage.gateStatus(), coverage.risks());
	}

	/**
	 * 상품이 사라졌다고 리포트를 못 보게 하지 않는다 — 판정 결과는 세션에 이미 저장돼 있고,
	 * 종료된 상담의 리포트는 상품 카탈로그와 독립적으로 남아야 한다.
	 */
	private ReportResponse.ProductView productViewOf(ConsultationSession session) {
		return new ReportResponse.ProductView(
				session.getProductId(),
				productRepository.findById(session.getProductId())
						.map(Product::getName)
						.orElse(null),
				// 상품의 현재 버전이 아니라 세션이 고정한 값이다 (TRD §4.1)
				session.getProductRiskVersion());
	}
}

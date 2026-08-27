package io.finready.coverage;

import io.finready.product.CoveragePolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gate 판정 (PRD §7.4). <b>서버만 계산한다</b> — 프론트는 risks 배열을 순회해 재계산하지 않고
 * {@code gateStatus} / {@code canProceedToUnderstanding} 를 그대로 쓴다(규칙 8).
 *
 * <p>판정 대상은 {@code coverageStatus} 다. {@code classifierStatus}(AI 원판정)로 Gate 를
 * 매기면 검증을 통과하지 못한 근거로 문을 열게 된다.
 */
@Component
public class GateEvaluator {

	/** WARN_ONLY 중 종료 전 acknowledge 대상 (TRD §8.6 {@code warnings}) */
	private static final Set<CoverageStatus> ACKNOWLEDGEABLE =
			Set.of(CoverageStatus.INSUFFICIENT, CoverageStatus.NOT_FOUND);

	/**
	 * TRD §8.6 {@code blocking} 을 그대로 옮긴 것이다.
	 *
	 * <pre>
	 * (coveragePolicy == GATE_REQUIRED AND coverage_status != EXPLAINED)
	 * OR (coverage_status == CONTRADICTED)          # policy 무관 승격
	 * </pre>
	 *
	 * <p><b>CONTRADICTED 는 WARN_ONLY 여도 막는다</b>(PRD §7.3 · TRD §8.6). 반대로 설명한
	 * 상담은 중요도가 낮은 항목이라도 그냥 통과시킬 수 없다는 판단이며, 등급이 아니라
	 * 방향의 문제라서 정책과 무관하게 승격된다.
	 *
	 * <p><b>WARN_ONLY 의 NOT_FOUND·INSUFFICIENT 는 막지 않는다</b> — 같은 두 문서가
	 * 명시적으로 "진행 가능"으로 규정한다. warning 으로 모아 종료 전 acknowledge 를 받는다.
	 */
	private boolean isBlocking(CoverageResult result, CoveragePolicy policy) {
		if (policy == null || policy == CoveragePolicy.NOT_APPLICABLE) {
			return false;
		}
		return (policy == CoveragePolicy.GATE_REQUIRED && result.getCoverageStatus() != CoverageStatus.EXPLAINED)
				|| result.getCoverageStatus() == CoverageStatus.CONTRADICTED;
	}

	/**
	 * @param results       분석된 Risk 별 결과
	 * @param policies      riskId → coveragePolicy. NOT_APPLICABLE 은 애초에 분석 대상이 아니다
	 * @param overriddenRiskIds 직원이 Override 한 Risk. Gate 판정에서만 빠지고 기록은 남는다
	 */
	public GateVerdict evaluate(List<CoverageResult> results,
	                            Map<String, CoveragePolicy> policies,
	                            Set<String> overriddenRiskIds) {

		List<CoverageResult> blocking = results.stream()
				.filter(r -> isBlocking(r, policies.get(r.getRiskId())))
				.toList();

		List<String> blockingRiskIds = blocking.stream()
				.map(CoverageResult::getRiskId)
				.filter(riskId -> !overriddenRiskIds.contains(riskId))
				.toList();

		// WARN_ONLY 의 미확인·불충분만 모은다. CONTRADICTED 는 위에서 막혔으므로
		// 여기 들어오면 "경고로 처리하고 통과"라는 반대 뜻이 된다
		List<String> warningRiskIds = results.stream()
				.filter(r -> policies.get(r.getRiskId()) == CoveragePolicy.WARN_ONLY)
				.filter(r -> ACKNOWLEDGEABLE.contains(r.getCoverageStatus()))
				.map(CoverageResult::getRiskId)
				.toList();

		if (!blockingRiskIds.isEmpty()) {
			return new GateVerdict(GateStatus.GATE_BLOCKED, false, blockingRiskIds, warningRiskIds);
		}

		// Override 로 열렸는지, 원래 열려 있었는지를 구분한다. 리포트에 남아야 하는 차이다
		boolean openedByOverride = blocking.stream()
				.anyMatch(r -> overriddenRiskIds.contains(r.getRiskId()));

		GateStatus status = openedByOverride
				? GateStatus.READY_WITH_STAFF_OVERRIDE
				: GateStatus.READY_FOR_UNDERSTANDING;

		return new GateVerdict(status, true, List.of(), warningRiskIds);
	}

	/**
	 * @param canProceedToUnderstanding 고객 이해 확인 버튼 활성화 여부. 프론트가 재계산하지 않는다
	 * @param blockingRiskIds           현재 Gate 를 막고 있는 Risk. 빈 배열이면 통과
	 * @param warningRiskIds            WARN_ONLY 중 미확인·불충분. 종료 전 acknowledge 대상.
	 *                                  CONTRADICTED 는 여기 오지 않는다 — blockingRiskIds 로 간다
	 */
	public record GateVerdict(
			GateStatus gateStatus,
			boolean canProceedToUnderstanding,
			List<String> blockingRiskIds,
			List<String> warningRiskIds
	) {
	}
}

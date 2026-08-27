package io.finready.coverage;

import io.finready.product.CoveragePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 판정. 프론트가 재계산하지 않고 그대로 쓰는 값이라(규칙 8) 여기가 유일한 진실이다.
 */
@DisplayName("GateEvaluator — Gate 판정")
class GateEvaluatorTest {

	private final GateEvaluator evaluator = new GateEvaluator();
	private final CoverageResultFactory factory = new CoverageResultFactory(new CoverageStatusResolver());

	private static final Map<String, CoveragePolicy> POLICIES = Map.of(
			"R01", CoveragePolicy.GATE_REQUIRED,
			"R02", CoveragePolicy.GATE_REQUIRED,
			"R05", CoveragePolicy.WARN_ONLY,
			"R06", CoveragePolicy.WARN_ONLY);

	@Test
	@DisplayName("GATE_REQUIRED 가 모두 EXPLAINED 면 열린다")
	void opensWhenAllGateRequiredExplained() {
		GateEvaluator.GateVerdict verdict = evaluator.evaluate(
				List.of(explained("R01"), explained("R02")), POLICIES, Set.of());

		assertThat(verdict.gateStatus()).isEqualTo(GateStatus.READY_FOR_UNDERSTANDING);
		assertThat(verdict.canProceedToUnderstanding()).isTrue();
		assertThat(verdict.blockingRiskIds()).isEmpty();
	}

	@Test
	@DisplayName("GATE_REQUIRED 하나가 NOT_FOUND 면 막힌다")
	void blocksOnMissingGateRequiredRisk() {
		GateEvaluator.GateVerdict verdict = evaluator.evaluate(
				List.of(explained("R01"), notFound("R02")), POLICIES, Set.of());

		assertThat(verdict.gateStatus()).isEqualTo(GateStatus.GATE_BLOCKED);
		assertThat(verdict.canProceedToUnderstanding()).isFalse();
		assertThat(verdict.blockingRiskIds()).containsExactly("R02");
	}

	@Test
	@DisplayName("WARN_ONLY 의 NOT_FOUND 는 문을 막지 않고 warning 으로만 모인다")
	void warnOnlyNotFoundDoesNotBlock() {
		GateEvaluator.GateVerdict verdict = evaluator.evaluate(
				List.of(explained("R01"), explained("R02"), notFound("R05")), POLICIES, Set.of());

		assertThat(verdict.canProceedToUnderstanding()).isTrue();
		assertThat(verdict.blockingRiskIds()).isEmpty();
		assertThat(verdict.warningRiskIds()).containsExactly("R05");
	}

	@Test
	@DisplayName("WARN_ONLY 의 INSUFFICIENT 도 막지 않는다 — PRD §7.3 이 '진행 가능'으로 규정한다")
	void warnOnlyInsufficientDoesNotBlock() {
		GateEvaluator.GateVerdict verdict = evaluator.evaluate(
				List.of(explained("R01"), explained("R02"), insufficient("R05")), POLICIES, Set.of());

		assertThat(verdict.gateStatus()).isEqualTo(GateStatus.READY_FOR_UNDERSTANDING);
		assertThat(verdict.blockingRiskIds()).isEmpty();
		assertThat(verdict.warningRiskIds()).containsExactly("R05");
	}

	@Test
	@DisplayName("WARN_ONLY 여도 CONTRADICTED 면 막는다 — policy 무관 승격(PRD §7.3 · TRD §8.6)")
	void contradictedIsPromotedRegardlessOfPolicy() {
		GateEvaluator.GateVerdict verdict = evaluator.evaluate(
				List.of(explained("R01"), explained("R02"), contradicted("R05")), POLICIES, Set.of());

		assertThat(verdict.gateStatus()).isEqualTo(GateStatus.GATE_BLOCKED);
		assertThat(verdict.canProceedToUnderstanding()).isFalse();
		assertThat(verdict.blockingRiskIds()).containsExactly("R05");
	}

	@Test
	@DisplayName("승격된 WARN_ONLY CONTRADICTED 는 warning 에 중복으로 담기지 않는다")
	void promotedContradictedIsNotAlsoAWarning() {
		GateEvaluator.GateVerdict verdict = evaluator.evaluate(
				List.of(explained("R01"), explained("R02"), contradicted("R05"), notFound("R06")),
				POLICIES, Set.of());

		// 막는 항목이 동시에 "경고로 처리하고 통과" 목록에 있으면 화면이 둘 중 하나를 잘못 읽는다
		assertThat(verdict.blockingRiskIds()).containsExactly("R05");
		assertThat(verdict.warningRiskIds()).containsExactly("R06");
	}

	@Test
	@DisplayName("승격된 WARN_ONLY CONTRADICTED 도 Override 하면 READY_WITH_STAFF_OVERRIDE 로 열린다")
	void overrideOpensPromotedContradicted() {
		GateEvaluator.GateVerdict verdict = evaluator.evaluate(
				List.of(explained("R01"), explained("R02"), contradicted("R05")), POLICIES, Set.of("R05"));

		assertThat(verdict.gateStatus()).isEqualTo(GateStatus.READY_WITH_STAFF_OVERRIDE);
		assertThat(verdict.canProceedToUnderstanding()).isTrue();
		assertThat(verdict.blockingRiskIds()).isEmpty();
	}

	@Test
	@DisplayName("Override 된 Risk 는 Gate 를 막지 않지만 READY_WITH_STAFF_OVERRIDE 로 구분된다")
	void overrideOpensGateButIsRecorded() {
		GateEvaluator.GateVerdict verdict = evaluator.evaluate(
				List.of(explained("R01"), notFound("R02")), POLICIES, Set.of("R02"));

		assertThat(verdict.gateStatus()).isEqualTo(GateStatus.READY_WITH_STAFF_OVERRIDE);
		assertThat(verdict.canProceedToUnderstanding()).isTrue();
		assertThat(verdict.blockingRiskIds()).isEmpty();
	}

	@Test
	@DisplayName("Override 가 없는데 원래 열려 있으면 READY_FOR_UNDERSTANDING 이다")
	void plainOpenIsDistinguishedFromOverride() {
		GateEvaluator.GateVerdict verdict = evaluator.evaluate(
				List.of(explained("R01"), explained("R02")), POLICIES, Set.of("R01"));

		assertThat(verdict.gateStatus()).isEqualTo(GateStatus.READY_FOR_UNDERSTANDING);
	}

	@Test
	@DisplayName("CONTRADICTED 도 GATE_REQUIRED 를 막는다")
	void contradictedBlocks() {
		GateEvaluator.GateVerdict verdict = evaluator.evaluate(
				List.of(explained("R01"), contradicted("R02")), POLICIES, Set.of());

		assertThat(verdict.blockingRiskIds()).containsExactly("R02");
	}

	@Test
	@DisplayName("Gate 는 classifierStatus 가 아니라 coverageStatus 로 판정한다")
	void judgesByCoverageStatusNotClassifierStatus() {
		// AI 는 EXPLAINED 라 했지만 근거를 원문에서 찾지 못한 경우.
		// classifierStatus 로 판정하면 문이 잘못 열린다
		CoverageResult downgraded = factory.create("S", 1L, "R02",
				CoverageStatus.EXPLAINED, "AI 는 설명됐다고 봄", null, "찾을 수 없는 인용",
				ProvenanceCheck.failed(ProvenanceFailureReason.NOT_FOUND), SemanticRelation.SUPPORTS);

		assertThat(downgraded.getClassifierStatus()).isEqualTo(CoverageStatus.EXPLAINED);
		assertThat(downgraded.getCoverageStatus()).isEqualTo(CoverageStatus.INSUFFICIENT);

		GateEvaluator.GateVerdict verdict =
				evaluator.evaluate(List.of(explained("R01"), downgraded), POLICIES, Set.of());

		assertThat(verdict.gateStatus()).isEqualTo(GateStatus.GATE_BLOCKED);
		assertThat(verdict.blockingRiskIds()).containsExactly("R02");
	}

	private CoverageResult explained(String riskId) {
		return factory.create("S", 1L, riskId, CoverageStatus.EXPLAINED, "설명됨", "근거 확인",
				"원문에서 인용한 구간", ProvenanceCheck.found(0, 20), SemanticRelation.SUPPORTS);
	}

	private CoverageResult notFound(String riskId) {
		return factory.create("S", 1L, riskId, CoverageStatus.NOT_FOUND, "언급 없음", null, null,
				ProvenanceCheck.failed(ProvenanceFailureReason.EMPTY), null);
	}

	private CoverageResult insufficient(String riskId) {
		return factory.create("S", 1L, riskId, CoverageStatus.INSUFFICIENT, "언급은 있으나 불완전",
				"근거 확인", "원문에서 인용한 구간", ProvenanceCheck.found(0, 20),
				SemanticRelation.INSUFFICIENT);
	}

	private CoverageResult contradicted(String riskId) {
		return factory.create("S", 1L, riskId, CoverageStatus.CONTRADICTED, "반대로 설명함", "근거 확인",
				"원문에서 인용한 구간", ProvenanceCheck.found(0, 20), SemanticRelation.CONTRADICTS);
	}
}

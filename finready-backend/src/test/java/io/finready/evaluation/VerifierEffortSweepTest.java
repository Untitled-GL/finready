package io.finready.evaluation;

import com.anthropic.models.messages.OutputConfig;
import io.finready.ai.EvalVerifierFactory;
import io.finready.coverage.SemanticRelation;
import io.finready.coverage.SemanticVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 5 Phase 6 — verifier effort HIGH → MEDIUM (TRD §18 사다리 R1).
 *
 * <p><b>실제 LLM 을 호출한다.</b> {@code ./gradlew evaluate} 로만 돌고 기본 {@code test}
 * 에서는 제외된다. 6회(HIGH 3 + MEDIUM 3) ≈ $0.09.
 *
 * <p>{@code CONS_A_003} R01("낙인 없음 → 원금 지켜짐")을 고정 픽스처로 쓴다 — Rule baseline
 * 대비 LLM 우위를 지탱하는 유일한 근거이자, effort 를 낮췄을 때 <b>가장 먼저 무너질 만한</b>
 * 경계 케이스다. classifier 는 부르지 않는다 — evidenceText 를 상담 원문에서 직접 고정해
 * 분류기 변동성과 verifier effort 라는 두 변수가 섞이지 않게 한다.
 *
 * <p>{@code CoverageBaselineComparisonTest} 와 같은 이유로 <b>점수로 단언하지 않는다.</b>
 * relation 이 실행마다 갈릴 수 있는 것 자체가 이 실험의 산출물이라, 여기서 단언을 걸면
 * 결론이 나왔을 때 결론이 아니라 테스트를 고치게 된다. 판단은 이 출력을 보고
 * {@code docs/decisions/2026-08-20-coverage-latency-fanout.md} 에 사람이 적는다.
 */
@Tag("evaluation")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Verifier effort 스윕 — HIGH vs MEDIUM (CONS_A_003 R01)")
class VerifierEffortSweepTest {

	private static final int RUNS_PER_EFFORT = 3;

	/** `demo_seed.json` CONS_A_003 원문에서 그대로 옮긴 구간. R01 CONTRADICTS 의 근거다 */
	private static final String EVIDENCE_TEXT =
			"낙인 배리어가 없는 노낙인 구조라서 투자 기간 중에 지수가 잠깐 크게 빠지더라도 "
					+ "그것만으로 손실이 확정되지 않습니다. 그래서 실무적으로 보면 사실상 "
					+ "원금은 지켜진다고 보셔도 크게 무리가 없습니다.";

	/** `product_a_risk_schema.json` R01.fact 원문 그대로 */
	private static final String R01_FACT =
			"본 증권은 원금이 보장되지 않으며, 만기까지 보유하더라도 만기평가일에 3개 기초자산 중 "
					+ "하나라도 최초기준가격의 65% 미만이면 원금 손실이 발생한다. 낙인(Knock-In) "
					+ "배리어가 없다는 점은 손실 요건의 판단 방식이 다를 뿐 원금 보장을 의미하지 않는다.";

	private static final SemanticVerifier.VerificationRequest REQUEST =
			new SemanticVerifier.VerificationRequest("R01", "원금 손실 가능성", R01_FACT, EVIDENCE_TEXT);

	private String apiKey;

	@BeforeAll
	void setUp() {
		apiKey = System.getenv("LLM_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException(
					"LLM_API_KEY 가 없어 평가를 실행할 수 없다. ~/.gradle/gradle.properties 의 "
							+ "llmApiKey 또는 셸 환경변수로 넣을 것 (CoverageBaselineComparisonTest 참조)");
		}
		System.out.printf("LLM Verifier 준비 — 키 %d자 확인%n", apiKey.length());
	}

	@Test
	@DisplayName("HIGH와 MEDIUM을 같은 픽스처에 대고 나란히 잰다")
	void sweep() {
		List<Run> highRuns = runEffort(OutputConfig.Effort.HIGH);
		List<Run> mediumRuns = runEffort(OutputConfig.Effort.MEDIUM);

		printReport(highRuns, mediumRuns);
	}

	private List<Run> runEffort(OutputConfig.Effort effort) {
		SemanticVerifier verifier = EvalVerifierFactory.claude(apiKey, effort);
		List<Run> runs = new ArrayList<>();

		for (int i = 1; i <= RUNS_PER_EFFORT; i++) {
			long startNanos = System.nanoTime();
			List<SemanticVerifier.RelationVerdict> verdicts =
					verifier.verify("eval-verifier-sweep-" + effort + "-" + i, sessionTranscript(), List.of(REQUEST));
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

			assertThat(verdicts).hasSize(1);
			SemanticVerifier.RelationVerdict verdict = verdicts.getFirst();
			runs.add(new Run(elapsedMs, verdict.relation(), verdict.reason()));
		}
		return runs;
	}

	/**
	 * verify() 는 맥락 확인용으로 상담 전문을 요구하지만, 이 스윕은 인용 구간과 사실의 의미
	 * 관계만 시험하면 되므로 그 구간을 포함한 최소 문맥만 준다 — 전문을 그대로 두면
	 * classifier 픽스처(CONS_A_003)를 이 파일에 통째로 복제해야 한다.
	 */
	private String sessionTranscript() {
		return "코스피200, S&P 500, 유로스톡스50 세 지수를 기초자산으로 하는 3년 만기 상품입니다. "
				+ "원금 손실 가능성이 아예 없는 상품은 아닙니다만, 이 상품은 " + EVIDENCE_TEXT;
	}

	private void printReport(List<Run> highRuns, List<Run> mediumRuns) {
		System.out.printf("%n=== Verifier effort 스윕 — CONS_A_003 R01 ===%n");
		System.out.printf("%-8s %-6s %-14s %-40s%n", "effort", "run", "latency(ms)", "relation / reason");
		System.out.println("-".repeat(90));

		printRuns("HIGH", highRuns);
		printRuns("MEDIUM", mediumRuns);

		System.out.println("-".repeat(90));
		System.out.printf("HIGH   median=%dms  CONTRADICTS=%d/%d%n",
				median(highRuns), countContradicts(highRuns), highRuns.size());
		System.out.printf("MEDIUM median=%dms  CONTRADICTS=%d/%d%n",
				median(mediumRuns), countContradicts(mediumRuns), mediumRuns.size());

		if (countContradicts(mediumRuns) < mediumRuns.size()) {
			System.out.println();
			System.out.println("⚠ MEDIUM 에서 CONTRADICTS 가 흔들렸다 — 사전 등록 중단 조건에 해당하는 신호다. "
					+ "R1(부분 병렬화) 이후 이 실험을 확대하기 전에 조사할 것.");
		}
	}

	private void printRuns(String effort, List<Run> runs) {
		for (int i = 0; i < runs.size(); i++) {
			Run run = runs.get(i);
			System.out.printf("%-8s %-6d %-14d %s / %s%n",
					effort, i + 1, run.latencyMs(), run.relation(), run.reason());
		}
	}

	private long median(List<Run> runs) {
		List<Long> sorted = runs.stream().map(Run::latencyMs).sorted().toList();
		return sorted.get(sorted.size() / 2);
	}

	private long countContradicts(List<Run> runs) {
		return runs.stream().filter(run -> run.relation() == SemanticRelation.CONTRADICTS).count();
	}

	private record Run(long latencyMs, SemanticRelation relation, String reason) {
	}
}

package io.finready.ai;

import com.anthropic.models.messages.OutputConfig;
import io.finready.coverage.SemanticVerifier;
import io.finready.coverage.SemanticRelation;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * F03 Evidence Semantic Verifier — Claude 구현.
 *
 * <p>분류기와 나눠 둔 이유가 프롬프트에 그대로 드러난다. 분류기는 "설명했는가"를 넓게 보고,
 * 여기서는 <b>인용된 근거가 그 주장을 실제로 뒷받침하는가</b>만 좁게 본다. 키워드가 겹치지만
 * 의미가 반대인 경우("낙인이 없다" → "원금이 지켜진다")를 잡는 것이 이 호출의 존재 이유다.
 */
class ClaudeSemanticVerifier implements SemanticVerifier {

	/**
	 * 프롬프트를 고치면 반드시 올린다 (TRD §7.2).
	 *
	 * <p>v1 → v2: effort 명시(v1 은 안 걸어 기본 high 로 돌았다), reason 길이 제한.
	 *
	 * <p>v2 → v3: <b>분류기와 같은 핵심/부연 기준을 넣었다.</b> v2 까지는 이 기준이
	 * 분류기(당시 coverage-v2)에만 있어서, 분류기가 "부연이 빠진 건 EXPLAINED"로 판정한 것을
	 * Verifier 가 옛 기준으로 다시 깎는 일이 관측됐다(R04: classifier EXPLAINED →
	 * verifier INSUFFICIENT). 두 단계가 다른 잣대를 쓰면 경계 케이스가 실행마다 흔들린다.
	 *
	 * <p>실제로 기록·노출되는 값은 여기에 effort 를 붙인 {@link #promptVersion} 이다 —
	 * {@code ClaudeCoverageClassifier} 가 배치 크기를 문자열에 박아둔 것과 같은 이유
	 * (Step 5 Phase 6). effort 가 다르면 다른 실험이고, 그것이 문자열에 없으면
	 * {@code llm_call_log} 만 보고는 어느 조건의 행인지 복원할 수 없다.
	 */
	private static final String PROMPT_VERSION = "verifier-v3";
	private static final String STAGE = "SEMANTIC_VERIFY";

	/**
	 * 분류기(medium)보다 높다. 이 단계가 잡아야 하는 것이 "낙인 없음 → 원금 지켜짐" 같은
	 * <b>표현은 비슷한데 의미가 반대인</b> 경우라, 여기서 추론을 아끼면 정확히 그 재현율이 떨어진다.
	 * 대상 Risk 만 도는 호출이라 전체 비용에서 차지하는 비중도 작다.
	 *
	 * <p>Step 5 Phase 6 에서 MEDIUM 을 시험한다 — {@link #ClaudeSemanticVerifier(AiGateway,
	 * OutputConfig.Effort)} 참조. 운영 기본값은 여전히 HIGH다.
	 */
	private static final OutputConfig.Effort EFFORT = OutputConfig.Effort.HIGH;

	private static final String SYSTEM_PROMPT = """
			당신은 ELS 상담 검토 보조 도구의 근거 검증 단계다.

			앞 단계에서 각 위험 항목에 대해 상담 내용의 한 구간을 근거로 인용했다.
			당신의 일은 **그 인용 구간이 해당 위험의 사실을 실제로 뒷받침하는지** 판정하는 것이다.

			"이 위험이 설명되었는가"를 다시 판정하지 않는다. 오직 인용된 구간과 사실 사이의
			의미 관계만 본다.

			## 판정 기준

			- SUPPORTS: 인용 구간이 해당 사실의 핵심을 정확히 전달한다.
			- CONTRADICTS: 인용 구간이 해당 사실과 반대되는 내용을 전달하거나,
			  고객이 위험을 반대로 이해하게 만든다.
			- INSUFFICIENT: 관련은 있으나 사실의 핵심이 빠져 있다.
			- UNRELATED: 인용 구간이 해당 사실과 관련이 없다.

			## 무엇이 "핵심"인가

			사실 설명에는 핵심과 부연이 섞여 있다. 기준은
			**고객이 이 위험 때문에 무엇을 잃을 수 있는지 전달되었는가**다.

			- 핵심: 그 위험이 실제로 존재한다는 것, 그리고 고객이 입을 수 있는 불이익
			- 부연: 그 판정을 계산하는 방법 — 기준 수치, 산식, 차수, 평가 시점 등

			**부연이 빠졌다는 이유로 INSUFFICIENT를 주지 않는다.** 핵심이 전달되었으면 SUPPORTS다.

			예시:
			- 사실: "만기상환금액은 만기평가일 종가라는 단일 시점을 기준으로, 세 기초자산 중
			  성과가 가장 낮은 하나를 기준으로 산정된다"
			  인용: "만기에는 세 지수 중에 가장 많이 떨어진 지수를 기준으로 상환금액이 결정되고요"
			  → SUPPORTS. 고객이 알아야 할 불이익(최저 성과 자산이 결과를 정한다)이 전달됐다.
			  "단일 시점"은 계산 방법이라 부연이다.

			- 단, 손실 **범위**처럼 그 수치 자체가 불이익인 경우는 수치가 핵심이다.
			  "손실이 날 수 있다"만으로는 "최대 -100%"를 전달했다고 볼 수 없다.

			## 이미 내려진 판정을 다시 하지 않는다

			앞 단계가 "이 위험이 설명되었는가"를 판정했다. 당신은 그것을 재검토하지 않는다.
			**인용 구간과 사실 사이의 의미 관계만** 본다.

			인용이 사실의 핵심을 담고 있으면 SUPPORTS다. 상담 전체에서 그 위험을 더 자세히
			설명했어야 한다고 생각되더라도, 그것은 당신이 판정할 것이 아니다.

			## 특히 주의할 것

			표현이 비슷해 보여도 의미가 반대인 경우를 놓치지 않는다. 예:

			- 사실: "낙인 배리어가 없다는 점이 원금 보장을 의미하지 않는다"
              인용: "낙인이 없는 구조라서 사실상 원금은 지켜집니다"
              → CONTRADICTS. 같은 소재를 다루지만 고객을 반대로 이해시킨다.

			- 사실: "최대 손실률은 -100%다"
			  인용: "손실이 날 수도 있습니다"
			  → INSUFFICIENT. 관련은 있으나 손실 범위라는 핵심이 없다.

			## 출력 형식

			다른 설명 없이 JSON만 출력한다.

			{"results":[{"riskId":"R01","relation":"SUPPORTS","reason":"판정 근거"}]}

			- 요청받은 모든 riskId에 대해 정확히 하나씩 결과를 낸다. 빠뜨리거나 중복하지 않는다.
			- relation은 위 4가지 값 중 하나만 쓴다. 다른 문자열을 만들지 않는다.
			- reason은 한 문장, 60자 이내로 쓴다.
			""";

	private final AiGateway gateway;
	private final OutputConfig.Effort effort;

	/** 프롬프트 버전에 effort 를 붙인 실제 값. {@code llm_call_log.prompt_version} 에 들어간다 */
	private final String promptVersion;

	ClaudeSemanticVerifier(AiGateway gateway) {
		this(gateway, EFFORT);
	}

	/**
	 * <b>effort 스윕 전용</b>이다. 운영 호출부는 없다 — {@code AiPortConfig} 는 1-인자
	 * 생성자를 쓴다 (Step 5 Phase 6, {@code ClaudeCoverageClassifier} 의
	 * {@code risksPerCall} 스윕 생성자와 같은 이유).
	 */
	ClaudeSemanticVerifier(AiGateway gateway, OutputConfig.Effort effort) {
		this.gateway = gateway;
		this.effort = effort;
		this.promptVersion = "%s-%s".formatted(PROMPT_VERSION, effort.toString().toLowerCase());
	}

	@Override
	public String promptVersion() {
		return promptVersion;
	}

	@Override
	public List<RelationVerdict> verify(String sessionId, String transcript, List<VerificationRequest> requests) {
		AiGateway.AiCall call = new AiGateway.AiCall(
				sessionId,
				STAGE,
				promptVersion,
				SYSTEM_PROMPT,
				buildUserMessage(transcript, requests),
				"targets=%d".formatted(requests.size()),
				2048L,
				effort);

		return gateway.call(call, this::parse);
	}

	/**
	 * 상담 원문 전체를 함께 보낸다. 인용 구간만 보내면 앞뒤 맥락이 잘려 반대 의미를 놓친다 —
	 * "원금이 지켜진다"가 앞 문장의 조건절에 걸려 있는지 아닌지가 판정을 가른다.
	 */
	private String buildUserMessage(String transcript, List<VerificationRequest> requests) {
		StringBuilder builder = new StringBuilder();
		builder.append("## 검증 대상\n\n");
		for (VerificationRequest request : requests) {
			builder.append("### ").append(request.riskId()).append(" — ").append(request.title()).append('\n');
			builder.append("사실: ").append(request.fact()).append('\n');
			builder.append("인용된 근거: ").append(request.evidenceText()).append("\n\n");
		}
		builder.append("## 상담 내용 전체 (맥락 확인용)\n\n").append(transcript).append('\n');
		return builder.toString();
	}

	private List<RelationVerdict> parse(String responseText) {
		JsonNode results = JsonResponses.requireArray(JsonResponses.parse(responseText), "results");

		List<RelationVerdict> verdicts = new ArrayList<>();
		for (JsonNode item : results) {
			verdicts.add(new RelationVerdict(
					JsonResponses.requireText(item, "riskId"),
					JsonResponses.requireEnum(item, "relation", SemanticRelation.class),
					JsonResponses.optionalText(item, "reason")));
		}
		return verdicts;
	}
}

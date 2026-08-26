package io.finready.ai;

import com.anthropic.models.messages.OutputConfig;
import io.finready.coverage.SemanticVerifier;
import org.mockito.Mockito;

/**
 * 평가에서 실제 Claude Verifier 를 <b>Spring 컨텍스트 없이</b> 만드는 브릿지. 테스트 전용이다.
 *
 * <p>{@link EvalClassifierFactory} 와 같은 이유로 존재한다 — {@code ClaudeSemanticVerifier}
 * 와 {@code AiPortConfig} 는 package-private 이라 {@code io.finready.evaluation} 에서
 * 닿지 않는다. 운영 코드의 가시성을 평가 때문에 넓히지 않는다.
 */
public final class EvalVerifierFactory {

	private EvalVerifierFactory() {
	}

	/**
	 * @param apiKey 실제 Anthropic API 키. 호출마다 요금이 든다
	 */
	public static SemanticVerifier claude(String apiKey) {
		return new ClaudeSemanticVerifier(gateway(apiKey));
	}

	/**
	 * effort 스윕용 (Step 5 Phase 6 — verifier HIGH → MEDIUM 실험).
	 *
	 * <p>운영에는 이 경로가 없다 — {@code AiPortConfig} 는 1-인자 생성자를 쓴다.
	 * 스윕으로 돈 호출은 {@code llm_call_log.prompt_version} 에 {@code verifier-v3-medium}
	 * 처럼 effort 가 박히므로 나중에도 조건이 구분된다.
	 */
	public static SemanticVerifier claude(String apiKey, OutputConfig.Effort effort) {
		return new ClaudeSemanticVerifier(gateway(apiKey), effort);
	}

	private static AiGateway gateway(String apiKey) {
		AiProperties properties = new AiProperties(
				apiKey,
				"claude-sonnet-4-6",
				"https://api.anthropic.com",
				60,
				1,
				0.0);

		// 호출 기록은 DB 로 간다. 평가는 DB 를 쓰지 않으므로 삼키는 대역을 끼운다
		LlmCallRecorder recorder = new LlmCallRecorder(Mockito.mock(LlmCallLogRepository.class));

		return new AiGateway(properties, recorder);
	}
}

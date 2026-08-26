package io.finready.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import io.finready.common.ApiException;
import io.finready.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Claude 호출 단일 지점. 프롬프트를 만드는 것은 각 포트 구현체가 하고, 여기서는
 * <b>호출·캐싱·재시도·기록</b>만 한다.
 *
 * <p>모아둔 이유는 셋이다. ① prompt caching 을 안 쓰면 $5 크레딧이 금방 마른다 —
 * 시스템 프롬프트가 전 세션 공통이라 캐시 prefix 로 딱 맞고 캐시 읽기는 ~0.1배다.
 * ② 재시도 정책이 흩어지면 "1회만"(TRD §7.1)이 지켜지는지 확인할 수 없다.
 * ③ {@code llm_call_log} 는 평가 재현의 원천이라(TRD §7.2) 빠뜨리는 경로가 있으면 안 된다.
 *
 * <p><b>호출은 트랜잭션 밖에서 한다</b>(규칙 6). 이 클래스는 트랜잭션을 열지 않으며,
 * 기록만 {@link LlmCallRecorder} 가 자기 트랜잭션으로 처리한다.
 */
public class AiGateway {

	private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

	/** 응답 원문을 통째로 남기면 로그 테이블이 금방 커진다. 재현에 필요한 만큼만 자른다 */
	private static final int MAX_LOGGED_RESPONSE = 8000;

	private final AnthropicClient client;
	private final AiProperties properties;
	private final LlmCallRecorder recorder;

	public AiGateway(AiProperties properties, LlmCallRecorder recorder) {
		this.properties = properties;
		this.recorder = recorder;
		this.client = AnthropicOkHttpClient.builder()
				.apiKey(properties.apiKey())
				.baseUrl(properties.baseUrl())
				.timeout(properties.timeout())
				// SDK 재시도는 전송 계층(429·5xx·연결 끊김)만 담당한다.
				// 파싱 실패 재시도는 의미가 다르므로 아래 call() 이 따로 센다
				.maxRetries(properties.maxRetries())
				.build();
	}

	/**
	 * 요청을 보내고 <b>텍스트 본문</b>을 돌려준다. 파싱은 호출부 몫이다 — 응답 모양이
	 * 포트마다 다르고, 여기서 파싱하면 이 클래스가 모든 포트를 알아야 한다.
	 *
	 * <p>파싱 실패 시 호출부가 {@link #call} 을 다시 부르는 게 아니라,
	 * {@code parser} 를 넘겨 여기서 1회 재시도하게 한다. 그래야 "1회만"이 한 곳에서 지켜진다.
	 *
	 * @param parser 응답 텍스트 → 결과. 파싱 불가면 예외를 던지면 된다
	 * @throws ApiException 재시도 후에도 파싱 실패면 {@code AI_PARSING_FAILED}(503),
	 *                      호출 자체가 실패하면 {@code AI_TIMEOUT}(503)
	 */
	public <T> T call(AiCall request, ResponseParser<T> parser) {
		RuntimeException lastFailure = null;

		// attempt 는 1부터. TRD §7.1 의 "1회만 재시도" = 최대 2회 시도
		for (short attempt = 1; attempt <= properties.maxRetries() + 1; attempt++) {
			long startNanos = System.nanoTime();
			String rawResponse = null;
			LlmCallLog.TokenUsage usage = null;
			boolean parsedOk = false;
			try {
				Sent sent = send(request);
				rawResponse = sent.text();
				usage = sent.usage();
				T parsed = parser.parse(rawResponse);
				parsedOk = true;
				return parsed;
			} catch (RuntimeException ex) {
				lastFailure = ex;
				log.warn("LLM 호출/파싱 실패 (stage={}, attempt={}): {}",
						request.stage(), attempt, ex.getMessage());
			} finally {
				recorder.record(new LlmCallLog(
						request.sessionId(),
						request.stage(),
						request.promptVersion(),
						properties.model(),
						request.summary(),
						truncate(rawResponse),
						parsedOk,
						elapsedMs(startNanos),
						attempt,
						usage,
						request.effort().toString()));
			}
		}

		throw toApiException(lastFailure, request);
	}

	private Sent send(AiCall request) {
		MessageCreateParams.Builder params = MessageCreateParams.builder()
				.model(properties.model())
				.maxTokens(request.maxTokens())
				// 걸지 않으면 Sonnet 4.6 기본값이 high 다. 분류·다듬기에는 과하고
				// 레이턴시와 토큰이 그만큼 더 든다 (TRD §14 예산 12초)
				.outputConfig(OutputConfig.builder().effort(request.effort()).build())
				// 시스템 프롬프트를 캐시 prefix 로 쓴다. 전 세션 공통이라 여기가 캐시가 가장 잘 듣는
				// 지점이며, 캐시는 prefix 일치라 이 앞에 변하는 값을 두면 전부 무효가 된다.
				// TTL 은 1시간(기본 5분 대비 쓰기 1.25배→2배, 배포 전 결정 항목이었다). 심사가
				// 09-07~09-11 닷새에 걸쳐 띄엄띄엄 들어올 것이라 5분으로는 세션 간 캐시가 거의
				// 안 겹친다 — 매번 쓰기만 물고 읽기 혜택이 없다. quota는 빠듯하지 않으므로
				// (docs/decisions/2026-08-18-coverage-prompt-tuning.md) 쓰기 비용 증가보다
				// 적중률을 우선한다
				.systemOfTextBlockParams(List.of(TextBlockParam.builder()
						.text(request.systemPrompt())
						.cacheControl(CacheControlEphemeral.builder()
								.ttl(CacheControlEphemeral.Ttl.TTL_1H)
								.build())
						.build()))
				.addUserMessage(request.userMessage());

		if (properties.temperature() != null) {
			// Sonnet 4.6 은 sampling 파라미터를 받는다. Opus 4.7+ / Sonnet 5 는 400 이므로
			// 모델을 올릴 때 설정에서 함께 지워야 한다
			params.temperature(properties.temperature());
		}

		Message message = client.messages().create(params.build());
		logUsage(request, message);

		String text = message.content().stream()
				.flatMap(block -> block.text().stream())
				.map(textBlock -> textBlock.text())
				.reduce("", String::concat);

		if (text.isBlank()) {
			throw new IllegalStateException("응답에 텍스트 블록이 없다 (stopReason=%s)"
					.formatted(message.stopReason()));
		}
		return new Sent(text, tokenUsage(message));
	}

	/**
	 * 응답 본문과 토큰 사용량을 함께 들고 나온다.
	 *
	 * <p>{@code send} 가 {@code String} 만 돌려주던 동안 usage 는 {@link #logUsage} 안에서
	 * INFO 한 줄로 소비되고 사라졌다. 그래서 "레이턴시는 출력 토큰에 비례한다"는 튜닝
	 * 문서의 핵심 주장이 <b>DB 로는 검증되지 않는 상태</b>였다 (TRD §7.2 가 이 테이블을
	 * 성능 실측의 원천으로 규정하는데도).
	 */
	private record Sent(String text, LlmCallLog.TokenUsage usage) {
	}

	/** 응답을 받았지만 usage 를 못 읽는 경우까지 호출 전체를 죽이지 않는다 */
	private LlmCallLog.TokenUsage tokenUsage(Message message) {
		try {
			Usage usage = message.usage();
			return new LlmCallLog.TokenUsage(
					toInt(usage.inputTokens()),
					toInt(usage.outputTokens()),
					toInt(usage.cacheReadInputTokens().orElse(0L)),
					toInt(usage.cacheCreationInputTokens().orElse(0L)));
		}
		catch (RuntimeException ex) {
			log.debug("usage 판독 실패 (stage={})", message.id(), ex);
			return null;
		}
	}

	private Integer toInt(Long value) {
		return value == null ? null : Math.toIntExact(value);
	}

	/**
	 * 토큰 사용량과 <b>캐시 적중</b>을 남긴다.
	 *
	 * <p>이게 없으면 두 가지를 영영 모른다. ① 크레딧이 어디로 나가는지 — 세션당 호출이
	 * 6~9회라 어느 단계가 비싼지 알아야 줄일 곳을 정한다. ② prompt caching 이 실제로
	 * 붙는지 — 캐시 미달(모델별 최소 토큰)이면 <b>오류 없이 조용히</b> 안 걸리므로,
	 * {@code cacheRead} 가 계속 0 이면 그게 유일한 신호다.
	 *
	 * <p>레이턴시만으로는 판단할 수 없다. 실행 간 편차가 캐시 효과보다 크다는 것이
	 * 이미 관측됐다(같은 프롬프트로 verifier 5.0s ~ 12.0s).
	 */
	private void logUsage(AiCall request, Message message) {
		try {
			Usage usage = message.usage();
			log.info("LLM usage — stage={}, in={}, out={}, cacheRead={}, cacheWrite={}",
					request.stage(),
					usage.inputTokens(),
					usage.outputTokens(),
					usage.cacheReadInputTokens().orElse(0L),
					usage.cacheCreationInputTokens().orElse(0L));
		} catch (RuntimeException ex) {
			// 관측 실패가 본 요청을 죽이지 않게 한다
			log.debug("usage 기록 실패 (stage={})", request.stage(), ex);
		}
	}

	/**
	 * 파싱 실패와 호출 실패를 구분한다. 둘 다 503 이지만 코드가 달라서 프론트가
	 * "잠시 후 다시"와 "응답을 이해하지 못했습니다"를 다르게 안내할 수 있다.
	 */
	private ApiException toApiException(RuntimeException failure, AiCall request) {
		boolean parsingProblem = failure instanceof ResponseParseException;
		ErrorCode code = parsingProblem ? ErrorCode.AI_PARSING_FAILED : ErrorCode.AI_TIMEOUT;
		log.error("LLM 호출이 재시도 후에도 실패했다 (stage={})", request.stage(), failure);
		return new ApiException(code, "AI 분석이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.");
	}

	private String truncate(String text) {
		if (text == null) {
			return null;
		}
		return text.length() <= MAX_LOGGED_RESPONSE
				? text
				: text.substring(0, MAX_LOGGED_RESPONSE) + "…(생략)";
	}

	private int elapsedMs(long startNanos) {
		return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
	}

	/**
	 * @param summary       프롬프트 <b>전문이 아니라 요약</b>이다. 상담 원문 전체를 넣으면
	 *                      로그 테이블이 상담 내용의 사본이 된다
	 * @param promptVersion Hold-out 재현 조건이다 (TRD §7.2). 프롬프트를 고치면 반드시 올린다
	 * @param systemPrompt  <b>캐시 prefix 다.</b> 세션마다 달라지는 값을 여기 넣으면 캐시가
	 *                      통째로 무효가 된다 — 변하는 것은 {@code userMessage} 로 보낸다
	 * @param effort        낮출수록 빠르고 싸지만 추론이 얕아진다. 의미 판단이 필요한 단계에서
	 *                      아끼면 정확히 그 단계의 재현율이 떨어진다
	 */
	public record AiCall(String sessionId,
	                     String stage,
	                     String promptVersion,
	                     String systemPrompt,
	                     String userMessage,
	                     String summary,
	                     long maxTokens,
	                     OutputConfig.Effort effort) {
	}

	@FunctionalInterface
	public interface ResponseParser<T> {
		T parse(String responseText);
	}

	/** 파싱 실패를 호출 실패와 구분하기 위한 표식 */
	public static class ResponseParseException extends RuntimeException {
		public ResponseParseException(String message) {
			super(message);
		}
	}
}

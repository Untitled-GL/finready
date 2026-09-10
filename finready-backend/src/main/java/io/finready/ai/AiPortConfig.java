package io.finready.ai;

import io.finready.coverage.CoverageClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import io.finready.coverage.SemanticVerifier;
import io.finready.explanation.ReExplanationGenerator;
import io.finready.understanding.AnswerJudge;
import io.finready.understanding.QuestionGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * LLM 포트 배선. <b>키가 있으면 실제 구현체, 없으면 스텁</b>을 등록한다.
 *
 * <p>키가 없어도 애플리케이션은 뜬다 — LLM 이 필요 없는 화면(F01·F02·리포트)까지 막지
 * 않기 위해서다. 대신 <b>기동 로그에 어느 모드인지 한 줄 남기고</b>, 호출되면 명확히 터진다.
 * 조용히 빈 결과를 돌려주면 전 Risk 가 "설명 안 됨"으로 읽혀 Gate 가 잠기고, 원인이
 * 코드가 아니라 설정이라는 걸 알기 어렵다.
 *
 * <p>{@code @ConditionalOnMissingBean} 을 쓰지 않는다. 일반 {@code @Configuration} 에서는
 * 평가 순서가 빈 정의 순서에 의존해 조용히 어긋날 수 있다 — 여기서는 메서드가 직접
 * 고르므로 어느 쪽이 등록되는지가 코드에 그대로 보인다.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiPortConfig {

	private static final Logger log = LoggerFactory.getLogger(AiPortConfig.class);

	private final AiProperties properties;

	/** 키가 없으면 null 이다. 게이트웨이 생성 자체가 키를 요구하므로 미리 만들 수 없다 */
	private final AiGateway gateway;

	public AiPortConfig(AiProperties properties, LlmCallRecorder recorder, MeterRegistry meterRegistry) {
		this.properties = properties;
		this.gateway = properties.isConfigured() ? new AiGateway(properties, recorder, meterRegistry) : null;

		// 배포 로그에서 바로 보이게 한다. 심사 중 "왜 분석이 안 되지"를 설정에서 찾게 하려면
		// 기동 시점에 한 줄이 있어야 한다
		if (properties.isConfigured()) {
			log.info("LLM 설정 확인 — model={}, baseUrl={}", properties.model(), properties.baseUrl());
		} else {
			log.warn("LLM_API_KEY 가 없다. LLM 이 필요한 기능(F03~F07)은 호출 시 실패한다");
		}
	}

	@Bean
	CoverageClassifier coverageClassifier() {
		if (gateway == null) {
			return (sessionId, transcript, risks) -> {
				throw unconfigured("Coverage 분류기");
			};
		}
		return new ClaudeCoverageClassifier(gateway);
	}

	@Bean
	SemanticVerifier semanticVerifier() {
		if (gateway == null) {
			return (sessionId, transcript, requests) -> {
				throw unconfigured("Evidence Semantic Verifier");
			};
		}
		return new ClaudeSemanticVerifier(gateway);
	}

	/**
	 * 스텁이 여기만 <b>조용히 빈 결과를 돌려준다.</b> 질문 생성 실패는 계약이 정한 정상
	 * 경로이고(검수된 {@code fallbackQuestion} 으로 대체 + {@code source: FALLBACK}),
	 * 호출부가 이미 그 경로를 갖고 있다. 여기서 던지면 LLM 없이 F04 를 돌려볼 수 없다.
	 */
	@Bean
	QuestionGenerator questionGenerator() {
		if (gateway == null) {
			return (sessionId, seeds) -> List.of();
		}
		return new ClaudeQuestionGenerator(gateway);
	}

	@Bean
	AnswerJudge answerJudge() {
		if (gateway == null) {
			return (sessionId, request) -> {
				throw unconfigured("답변 판정기");
			};
		}
		return new ClaudeAnswerJudge(gateway);
	}

	/**
	 * {@code questionGenerator} 와 같이 <b>조용한 스텁</b>이다. 재설명에는 검수된
	 * {@code fallbackPlainExplanation} 이 있고 호출부가 이미 그 경로를 갖고 있다 —
	 * 여기서 던지면 LLM 없이 F06 화면을 띄워볼 수 없다.
	 */
	@Bean
	ReExplanationGenerator reExplanationGenerator() {
		if (gateway == null) {
			return (sessionId, riskId, title, fact, sourceText, answer, level) -> null;
		}
		return new ClaudeReExplainer(gateway);
	}

	/**
	 * 계약 오류가 아니라 배포 설정 누락이다. {@code ApiException} 을 쓰면 "재시도하면 될지도"로
	 * 보이지만 키가 없는 한 몇 번을 눌러도 안 된다. 500 + 스택트레이스로 남겨 로그에서 바로 보이게 한다.
	 */
	private static IllegalStateException unconfigured(String port) {
		return new IllegalStateException(
				port + " 구현체가 없다. LLM_API_KEY 를 설정할 것 "
						+ "(TRD D-02, finready-backend/CLAUDE.md '다음 순서')");
	}
}

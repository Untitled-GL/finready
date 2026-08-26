package io.finready.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * openapi.yml v1.4.2 의 Error 스키마.
 *
 * <p>{@code @Schema(name = "Error")} — springdoc은 기본적으로 Java 클래스의 단순 이름
 * ({@code ErrorResponse})으로 컴포넌트 스키마 이름을 짓는다. 계약이 이 스키마를
 * {@code #/components/schemas/Error}로 참조하므로 이름을 강제로 맞춘다
 * (TRD §17 계약 대조 테스트가 이 불일치를 잡는다).
 *
 * @param code        계약 enum
 * @param message     화면에 그대로 노출 가능한 한국어 메시지. 내부 예외 문구를 그대로 담지 않는다
 * @param riskId      Risk 단위 오류일 때만. 아니면 null
 * @param recoverable true 면 프론트가 재시도 버튼을 노출한다
 * @param requestId   서버 로그 추적용. RequestIdFilter 가 넣은 MDC 값과 같다
 */
@Schema(name = "Error")
public record ErrorResponse(
		ErrorCode code,
		String message,
		String riskId,
		boolean recoverable,
		String requestId
) {
}

package io.finready.understanding;

/**
 * openapi.yml {@code progress} 객체. {@link UnderstandingResponse}와
 * {@link StaffResolutionResponse}가 공유한다 — FE가 "Risk {currentRiskIndex}/{totalRiskCount}"를
 * 직접 세지 않고 서버가 내려주는 값을 쓰게 하기 위함이다(규칙 8).
 */
public record Progress(int currentRiskIndex, int totalRiskCount) {
}

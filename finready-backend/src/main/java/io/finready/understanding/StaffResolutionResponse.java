package io.finready.understanding;

import io.finready.session.SessionStatus;

/**
 * openapi.yml {@code StaffResolutionResponse}. v1.4.2에서 {@code RiskUnderstandingState}를
 * 감싸고 흐름 값({@code nextAction}·{@code progress})을 더하는 구조로 재설계됐다 —
 * FE가 다음 Risk로 갈지 리포트로 갈지를 서버 주도로 결정할 수 있게 하기 위해서다.
 *
 * <p>{@code riskState}가 {@code aiStatus}를 이미 담고 있다(규칙 1 — 직원이 해결해도
 * AI 원판정은 그대로 남는다). {@code RiskUnderstandingState}는 Report에서도 쓰이는 순수
 * 상태 표현이라 "다음에 무엇을 할지"가 없다 — 그래서 흐름 값은 이 응답 스키마에만 둔다.
 *
 * <p>TRD §17 계약 대조 테스트가 이 재설계와 코드 사이의 실제 드리프트를 잡아냈다
 * (구 응답이 요청을 그대로 echo하는 평평한 구조였다, 2026-08-26).
 *
 * @param sessionStatus 마지막 Risk 처리로 세션이 AWAITING_STAFF_REVIEW로 전이했을 수 있다
 */
public record StaffResolutionResponse(
		RiskUnderstandingState riskState,
		NextAction nextAction,
		Progress progress,
		SessionStatus sessionStatus
) {
}

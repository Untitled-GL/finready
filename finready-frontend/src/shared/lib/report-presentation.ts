import { UNDERSTANDING_AI_STATUS_LABEL } from "@/shared/constants/labels";
import type {
  RiskUnderstandingState,
  UnderstandingAIStatus,
} from "@/shared/types/domain";
import { understandingStyle } from "@/shared/ui/status-pill";

export interface UnderstandingTimelineEvent {
  label: string;
  answer?: string;
  sub: string;
  tone: string;
}

export function understandingTimelineEvents(
  state: RiskUnderstandingState,
): UnderstandingTimelineEvent[] {
  const attempts = state.attempts ?? [];
  const events: UnderstandingTimelineEvent[] = [];

  attempts.forEach((attempt, index) => {
    const status = attempt.aiStatus as UnderstandingAIStatus | undefined;
    events.push({
      label: `${attempt.attempt}차 답변`,
      ...(attempt.answer ? { answer: attempt.answer } : {}),
      sub: status
        ? `AI 판정: ${UNDERSTANDING_AI_STATUS_LABEL[status]}${attempt.reason ? ` · ${attempt.reason}` : ""}`
        : "AI 판정 없음",
      tone: status ? understandingStyle(status).dotColor : "var(--color-line)",
    });
    if (index === 0 && status === "MISUNDERSTOOD" && attempts.length > 1) {
      events.push({
        label: "근거 기반 재설명",
        sub: "상품설명서 원문으로 다시 설명",
        tone: "var(--color-accent)",
      });
    }
  });

  if (state.staffResolution) {
    events.push({
      label:
        state.staffResolution.disposition === "UNRESOLVED"
          ? "미해결로 종결"
          : "직원 확인으로 해결",
      sub: state.staffResolution.reason ?? "",
      tone:
        state.staffResolution.disposition === "UNRESOLVED"
          ? "var(--color-none-dot)"
          : "var(--color-ok-dot)",
    });
  } else if (state.finalDisposition === "AUTO_RESOLVED") {
    events.push({
      label: "확인 완료",
      sub: "AI 판정으로 완료",
      tone: "var(--color-ok-dot)",
    });
  } else if (state.finalDisposition === "SKIPPED_BY_OVERRIDE") {
    events.push({
      label: "질문 제외",
      sub: "직원 판단으로 제외 · 미해결로 남음",
      tone: "var(--color-none-dot)",
    });
  }

  return events;
}

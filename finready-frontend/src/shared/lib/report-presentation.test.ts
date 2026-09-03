import { describe, expect, it } from "vitest";
import { understandingTimelineEvents } from "@/shared/lib/report-presentation";
import type { RiskUnderstandingState } from "@/shared/types/domain";

describe("report understanding timeline", () => {
  it("shows what the customer actually answered beside the AI judgement", () => {
    const state = {
      riskId: "R01",
      riskTitle: "원금 손실 가능성",
      attempts: [
        {
          attempt: 1,
          answer: "만기까지 보유하면 원금을 돌려받는다고 생각했습니다.",
          aiStatus: "MISUNDERSTOOD",
          reason: "만기 보유가 원금 보장을 뜻하지 않습니다.",
        },
      ],
      workflowStatus: "IN_PROGRESS",
      finalDisposition: null,
    } as RiskUnderstandingState;

    expect(understandingTimelineEvents(state)).toEqual([
      {
        label: "1차 답변",
        answer: "만기까지 보유하면 원금을 돌려받는다고 생각했습니다.",
        sub: "AI 판정: 다르게 이해 · 만기 보유가 원금 보장을 뜻하지 않습니다.",
        tone: "var(--color-bad-dot)",
      },
    ]);
  });

  it("keeps the final AI result clearly separate from the customer answer", () => {
    const state = {
      riskId: "R02",
      riskTitle: "최대 손실 범위",
      attempts: [
        {
          attempt: 1,
          answer: "전액을 잃을 수도 있습니다.",
          aiStatus: "UNDERSTOOD",
          reason: "최대 손실 범위를 정확히 설명했습니다.",
        },
      ],
      workflowStatus: "COMPLETE",
      finalDisposition: "AUTO_RESOLVED",
    } as RiskUnderstandingState;

    expect(understandingTimelineEvents(state)).toEqual([
      {
        label: "1차 답변",
        answer: "전액을 잃을 수도 있습니다.",
        sub: "AI 판정: 이해 확인 · 최대 손실 범위를 정확히 설명했습니다.",
        tone: "var(--color-ok-dot)",
      },
      {
        label: "확인 완료",
        sub: "AI 판정으로 완료",
        tone: "var(--color-ok-dot)",
      },
    ]);
  });
});

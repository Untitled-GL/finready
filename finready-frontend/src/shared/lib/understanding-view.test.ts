import { describe, expect, it } from "vitest";
import {
  displayedRiskIndex,
  restorePendingResult,
  shouldReturnToStaff,
} from "@/shared/lib/understanding-view";
import type { RiskUnderstandingState } from "@/shared/types/domain";

const states = [
  {
    riskId: "R01",
    riskTitle: "원금 손실 가능성",
    attempts: [
      {
        attempt: 1,
        question: "만기까지 보유하면 어떻게 되나요?",
        answer: "원금은 돌려받습니다.",
        aiStatus: "MISUNDERSTOOD",
        reason: "만기 보유가 원금 보장을 뜻하지 않습니다.",
      },
    ],
    staffResolution: null,
    workflowStatus: "IN_PROGRESS",
    finalDisposition: null,
    pendingQuestion: null,
  },
  {
    riskId: "R02",
    riskTitle: "최대 손실 범위",
    attempts: [],
    staffResolution: null,
    workflowStatus: "NOT_STARTED",
    finalDisposition: null,
    pendingQuestion: {
      attempt: 1,
      question: "최대 얼마까지 잃을 수 있나요?",
      source: "LLM",
    },
  },
] as RiskUnderstandingState[];

describe("customer understanding view restoration", () => {
  it("restores the misunderstood answer result instead of reopening its first question", () => {
    expect(restorePendingResult("REEXPLAIN", states)).toEqual({
      riskId: "R01",
      result: {
        riskId: "R01",
        aiStatus: "MISUNDERSTOOD",
        reason: "만기 보유가 원금 보장을 뜻하지 않습니다.",
        answer: "원금은 돌려받습니다.",
        nextAction: "REEXPLAIN",
        progress: { currentRiskIndex: 1, totalRiskCount: 2 },
      },
    });
  });

  it("does not invent a result when the server has moved to another action", () => {
    expect(restorePendingResult("RECHECK", states)).toBeNull();
    expect(restorePendingResult("NEXT_RISK", states)).toBeNull();
  });

  it("keeps the shell progress on the answer whose result is visible", () => {
    expect(displayedRiskIndex(2, { currentRiskIndex: 1 })).toBe(1);
    expect(displayedRiskIndex(2, undefined)).toBe(2);
  });

  it("shows the final result before returning the device to staff", () => {
    expect(shouldReturnToStaff(true, "result", true)).toBe(false);
    expect(shouldReturnToStaff(true, "question", true)).toBe(false);
    expect(shouldReturnToStaff(true, "question", false)).toBe(true);
  });
});

import type {
  NextAction,
  RiskUnderstandingState,
  UnderstandingAIStatus,
} from "@/shared/types/domain";

export interface ResultPresentation {
  riskId?: string;
  aiStatus: UnderstandingAIStatus;
  reason?: string;
  answer?: string;
  nextAction: NextAction;
  progress?: {
    currentRiskIndex?: number;
    totalRiskCount?: number;
  };
}

export function restorePendingResult(
  nextAction: NextAction | null | undefined,
  states: RiskUnderstandingState[],
): { riskId: string; result: ResultPresentation } | null {
  if (nextAction !== "REEXPLAIN") return null;

  const index = states.findIndex((state) => {
    const attempts = state.attempts ?? [];
    return (
      state.workflowStatus === "IN_PROGRESS" &&
      !state.pendingQuestion &&
      attempts.length > 0
    );
  });
  if (index < 0) return null;

  const state = states[index];
  const attempts = state.attempts ?? [];
  const attempt = attempts[attempts.length - 1];
  if (!state.riskId || !attempt?.aiStatus || !attempt.answer) return null;

  return {
    riskId: state.riskId,
    result: {
      riskId: state.riskId,
      aiStatus: attempt.aiStatus,
      reason: attempt.reason,
      answer: attempt.answer,
      nextAction,
      progress: {
        currentRiskIndex: index + 1,
        totalRiskCount: states.length,
      },
    },
  };
}

export function displayedRiskIndex(
  questionIndex: number,
  resultProgress: ResultPresentation["progress"] | undefined,
): number {
  return resultProgress?.currentRiskIndex ?? questionIndex;
}

export function shouldReturnToStaff(
  customerDone: boolean,
  viewKind: "question" | "result" | "reexplain" | "done",
  hasLocalSubmission: boolean,
): boolean {
  return customerDone && viewKind === "question" && !hasLocalSubmission;
}

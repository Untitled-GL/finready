"use client";

import type { NextAction, UnderstandingAIStatus } from "@/shared/types/domain";
import { StatusDot, understandingStyle } from "@/shared/ui/status-pill";

/**
 * S05 — what the AI made of the answer, said plainly to the customer.
 *
 * Three states, no score. "이해 확인" means the answer agreed with the
 * verified fact, not that the customer's understanding was measured, so
 * nothing here is phrased as a grade or a percentage.
 */

const HEADLINE: Record<UnderstandingAIStatus, string> = {
  UNDERSTOOD: "이해하신 내용이 상품 조건과 일치합니다.",
  MISUNDERSTOOD: "상품 조건과 다르게 이해하신 부분이 있습니다.",
  UNCERTAIN: "한 번 더 여쭤보겠습니다.",
};

const CTA: Record<NextAction, string> = {
  NEXT_RISK: "다음 위험 확인",
  REEXPLAIN: "다시 설명 보기",
  RECHECK: "다시 확인하기",
  STAFF_RESOLUTION_REQUIRED: "담당 직원에게 전달",
  GO_TO_REPORT: "확인 마치기",
};

const NOTE: Record<NextAction, string> = {
  NEXT_RISK: "다음 핵심 위험으로 넘어갑니다.",
  REEXPLAIN: "상품설명서 원문을 근거로 다시 설명해 드립니다.",
  RECHECK: "표현을 바꿔 한 번 더 여쭤봅니다.",
  STAFF_RESOLUTION_REQUIRED: "이 항목은 담당 직원이 함께 확인합니다.",
  GO_TO_REPORT: "확인이 모두 끝났습니다.",
};

export function ResultView({
  aiStatus,
  reason,
  answer,
  kicker,
  nextAction,
  pending,
  onContinue,
}: {
  aiStatus: UnderstandingAIStatus;
  reason: string | undefined;
  answer: string;
  kicker: string;
  nextAction: NextAction;
  pending: boolean;
  onContinue: () => void;
}) {
  const style = understandingStyle(aiStatus);

  return (
    <div className="screen-in px-[20px] pt-[52px] pb-[72px] sm:px-[40px] sm:pt-[80px] sm:pb-[96px]">
      <div className="mx-auto max-w-[760px]">
        <p className="mb-[14px] text-[14px] font-semibold text-[var(--color-cust-muted)]">
          {kicker}
        </p>

        <div className="flex items-center gap-[10px]">
          <StatusDot shape={style.shape} color={style.dotColor} size={10} />
          <span className="text-[14px] font-semibold" style={{ color: style.fg }}>
            {style.label}
          </span>
        </div>

        <h2 className="mt-[18px] text-[28px] leading-[1.4] font-semibold tracking-[-0.022em] text-pretty sm:text-[34px] sm:leading-[1.35]">
          {HEADLINE[aiStatus]}
        </h2>
        {reason ? (
          <p className="mt-[18px] text-[17.5px] leading-[1.75] text-pretty text-[oklch(0.38_0.015_75)]">
            {reason}
          </p>
        ) : null}

        <div className="mt-[32px] rounded-[16px] bg-white px-[24px] py-[22px]">
          <p className="mb-[10px] text-[12.5px] font-semibold text-[var(--color-cust-muted-soft)]">
            고객님의 답변
          </p>
          <p className="text-[16.5px] leading-[1.75]">{answer}</p>
        </div>

        <div className="mt-[36px] flex flex-col items-start gap-[10px] sm:flex-row sm:items-center sm:gap-[18px]">
          <button
            type="button"
            onClick={onContinue}
            disabled={pending}
            className="rounded-[10px] bg-[var(--color-accent)] px-[28px] py-[14px] text-[15.5px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
          >
            {pending ? "준비 중…" : CTA[nextAction]}
          </button>
          <span className="text-[13.5px] text-[var(--color-cust-muted-soft)]">
            {NOTE[nextAction]}
          </span>
        </div>
      </div>
    </div>
  );
}

"use client";

import type { ReExplanationResponse } from "@/shared/types/domain";

/**
 * S06 — the grounded re-explanation.
 *
 * The chain has to stay visible end to end: what the customer said, which
 * part diverged, what the product actually stipulates, the plain-language
 * restatement, and the page and sentence it came from. Every claim on this
 * screen traces back to the verified source shown at the bottom.
 *
 * Whether the wording was model-adjusted or the reviewed fallback is
 * labelled rather than hidden — the guardrail decision belongs to the
 * server; this screen reports it.
 */
export function ReExplanationView({
  data,
  misunderstanding,
  kicker,
  pending,
  onContinue,
}: {
  data: ReExplanationResponse;
  /**
   * The classifier's stated reason for the MISUNDERSTOOD call. It lives on
   * the understanding result, not on the re-explanation payload, so it is
   * passed in rather than read off `data`.
   */
  misunderstanding: string | undefined;
  kicker: string;
  pending: boolean;
  onContinue: () => void;
}) {
  return (
    <div className="screen-in px-[20px] pt-[48px] pb-[72px] sm:px-[40px] sm:pt-[72px] sm:pb-[96px]">
      <div className="mx-auto max-w-[800px]">
        <p className="text-[14px] font-semibold text-[var(--color-cust-muted)]">
          {kicker} · 다시 설명해 드릴게요
        </p>

        <Section label="고객님이 작성한 답변" className="mt-[36px]">
          <p className="border-l-2 border-[var(--color-rule-quote)] pl-[18px] text-[18px] leading-[1.75]">
            {data.customerAnswer}
          </p>
        </Section>

        <Section label="확인된 오해" className="mt-[28px]">
          <p className="text-[18px] leading-[1.75] text-[var(--color-bad-reexp)]">
            {misunderstanding ?? "이해하신 내용이 상품 조건과 다릅니다."}
          </p>
        </Section>

        <Section label="실제 상품 조건" className="mt-[28px]">
          <p className="text-[19px] leading-[1.7] font-medium">{data.riskFact}</p>
        </Section>

        <div className="mt-[28px]">
          <p className="mb-[12px] text-[12.5px] font-semibold tracking-[0.08em] text-[var(--color-cust-muted-soft)]">
            쉽게 다시 설명하면
          </p>
          <p className="text-[19px] leading-[1.8] text-pretty">{data.explanation}</p>
        </div>

        <div className="mt-[32px] rounded-[16px] bg-white px-[26px] py-[24px]">
          <div className="flex flex-col items-start justify-between gap-[6px] sm:flex-row sm:items-center sm:gap-[16px]">
            <p className="text-[14px] font-semibold">
              상품설명서 {data.sourcePage}페이지
            </p>
            <span className="text-[13px] text-[var(--color-cust-muted-soft)]">
              검수된 원문 근거
              {data.source === "FALLBACK" ? " · 검수 문안" : " · 표현 조정됨"}
            </span>
          </div>
          <p className="mt-[14px] text-[16.5px] leading-[1.75] text-[oklch(0.3_0.01_260)]">
            {data.sourceText}
          </p>
        </div>

        <div className="mt-[36px] flex flex-col items-start gap-[10px] sm:flex-row sm:items-center sm:gap-[18px]">
          <button
            type="button"
            onClick={onContinue}
            disabled={pending}
            className="rounded-[10px] bg-[var(--color-accent)] px-[28px] py-[14px] text-[15.5px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
          >
            다시 확인하기
          </button>
          <span className="text-[13.5px] text-[var(--color-cust-muted-soft)]">
            이해하신 내용을 한 번만 더 확인하겠습니다.
          </span>
        </div>
      </div>
    </div>
  );
}

function Section({
  label,
  className = "",
  children,
}: {
  label: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={`border-b border-[var(--color-cust-line)] pb-[28px] ${className}`}>
      <p className="mb-[12px] text-[12.5px] font-semibold tracking-[0.08em] text-[var(--color-cust-muted-soft)]">
        {label}
      </p>
      {children}
    </div>
  );
}

"use client";

import { INPUT_LIMITS } from "@/shared/constants/labels";
import type { DemoAnswer } from "@/shared/types/domain";

/**
 * The customer's answer box, used for both the first question and the
 * follow-up.
 *
 * The sample answers exist so a demo can reach any branch on purpose. They
 * are labelled as demo aids and carry no hint about which one is "right" —
 * the point of the screen is what the customer understood, not a quiz.
 */
export function AnswerForm({
  question,
  kicker,
  note,
  answer,
  onAnswerChange,
  samples,
  pending,
  onSubmit,
}: {
  question: string;
  kicker: string;
  note: string;
  answer: string;
  onAnswerChange: (value: string) => void;
  samples: DemoAnswer[];
  pending: boolean;
  onSubmit: () => void;
}) {
  const tooLong = answer.length > INPUT_LIMITS.answer;
  const canSubmit = answer.trim().length > 0 && !tooLong && !pending;

  return (
    <div className="screen-in px-[20px] pt-[48px] pb-[72px] sm:px-[40px] sm:pt-[72px] sm:pb-[96px]">
      <div className="mx-auto max-w-[760px]">
        <p className="text-[14px] font-semibold text-[var(--color-cust-muted)]">
          {kicker}
        </p>
        <h2 className="mt-[20px] text-[28px] leading-[1.4] font-semibold tracking-[-0.022em] text-pretty sm:text-[36px] sm:leading-[1.35]">
          {question}
        </h2>
        <p className="mt-[20px] text-[16.5px] leading-[1.7] text-[var(--color-cust-body)]">
          편하게 이해하신 대로 답해 주세요. 정답을 맞히는 것이 아니라, 설명이
          충분했는지 확인하는 과정입니다.
        </p>

        <textarea
          value={answer}
          onChange={(e) => onAnswerChange(e.target.value)}
          aria-label="이해한 내용"
          placeholder="이해한 내용을 적어주세요"
          maxLength={INPUT_LIMITS.answer}
          className="mt-[32px] block min-h-[150px] w-full resize-y rounded-[16px] border border-[var(--color-cust-line-soft)] bg-white px-[26px] py-[24px] text-[17px] leading-[1.75]"
        />

        {samples.length > 0 ? (
          <>
            <p className="mt-[26px] mb-[10px] text-[13px] font-semibold text-[var(--color-cust-muted-soft)]">
              데모용 샘플 답변
            </p>
            <div className="flex flex-col gap-[8px]">
              {samples.map((sample, index) => {
                const text = sample.answer;
                const on = answer === text;
                return (
                  <button
                    key={`${sample.expectedLabel}-${index}`}
                    type="button"
                    onClick={() => onAnswerChange(text)}
                    className="block w-full rounded-[12px] border bg-white px-[18px] py-[14px] text-left text-[15.5px] leading-[1.6] text-[oklch(0.3_0.012_75)]"
                    style={{
                      borderColor: on
                        ? "var(--color-accent)"
                        : "var(--color-cust-line)",
                      boxShadow: on ? "inset 0 0 0 1px var(--color-accent)" : "none",
                    }}
                  >
                    {text}
                  </button>
                );
              })}
            </div>
          </>
        ) : null}

        <div className="mt-[32px] flex flex-col items-start gap-[10px] sm:flex-row sm:items-center sm:gap-[18px]">
          <button
            type="button"
            onClick={onSubmit}
            disabled={!canSubmit}
            className="rounded-[10px] bg-[var(--color-accent)] px-[28px] py-[14px] text-[15.5px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
          >
            {pending ? "확인 중…" : "답변 확인"}
          </button>
          <span className="text-[13.5px] text-[var(--color-cust-muted-soft)]">
            {note}
          </span>
        </div>
      </div>
    </div>
  );
}

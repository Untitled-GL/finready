"use client";

import { useEffect, useRef, useState } from "react";
import { OVERRIDE_CATEGORY_LABEL, INPUT_LIMITS } from "@/shared/constants/labels";
import { OVERRIDE_CATEGORIES } from "@/shared/types/domain";
import type { GateOverrideRequest, OverrideCategory } from "@/shared/types/domain";
import { ErrorNote } from "@/shared/ui/error-note";

/**
 * Staff override of a blocked gate.
 *
 * It records a *second* judgement beside the AI's; it never edits the
 * classifier's verdict. When the blocked risk is one the customer will be
 * questioned about, the staff member has to say explicitly whether they
 * actually explained it — answering "no" removes that risk from the
 * customer flow rather than quietly passing it through.
 */
export function OverrideDialog({
  blockingRiskIds,
  riskLabels,
  requiresExplanationConfirmation,
  pending,
  error,
  onSubmit,
  onClose,
}: {
  blockingRiskIds: string[];
  riskLabels: Record<string, string>;
  requiresExplanationConfirmation: boolean;
  pending: boolean;
  error: unknown;
  onSubmit: (req: GateOverrideRequest) => void;
  onClose: () => void;
}) {
  const [category, setCategory] = useState<OverrideCategory | null>(null);
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const reasonOk = reason.trim().length >= 5 && reason.length <= INPUT_LIMITS.reason;
  const canSubmit = Boolean(category) && reasonOk && !pending;

  const submit = () => {
    if (!canSubmit || !category) return;
    onSubmit({
      riskIds: blockingRiskIds,
      category,
      reason: reason.trim(),
      ...(requiresExplanationConfirmation
        ? { staffExplanationConfirmed: confirmed }
        : {}),
      actor: "staff-demo",
    });
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="override-title"
      className="fade-in fixed inset-0 z-70 flex items-center justify-center bg-[var(--color-scrim)] p-[16px] backdrop-blur-[3px] sm:p-[40px]"
    >
      <div className="screen-in w-[560px] rounded-[18px] bg-white px-[34px] py-[32px] shadow-[0_30px_60px_-24px_oklch(0.3_0.02_260/0.5)]">
        <h3 id="override-title" className="text-[22px] font-semibold tracking-[-0.015em]">
          직원 판단으로 진행
        </h3>
        <p className="mt-[12px] text-[15.5px] leading-[1.65] text-[var(--color-ink-muted)]">
          AI 판정은 그대로 보존되며, 리포트에 직원 판단 이력으로 함께 기록됩니다.
          가능하다면 보완 설명 후 재분석을 먼저 권장합니다.
        </p>

        <p className="mt-[20px] mb-[8px] text-[12.5px] font-semibold text-[oklch(0.5_0.01_260)]">
          대상 항목
        </p>
        <p className="text-[15.5px]">
          {blockingRiskIds.map((id) => `${id} ${riskLabels[id] ?? ""}`).join("   ·   ")}
        </p>

        <p className="mt-[22px] mb-[10px] text-[12.5px] font-semibold text-[oklch(0.5_0.01_260)]">
          사유 구분
        </p>
        <div className="flex flex-wrap gap-[8px]">
          {OVERRIDE_CATEGORIES.map((value) => {
            const on = category === value;
            return (
              <button
                key={value}
                type="button"
                aria-pressed={on}
                onClick={() => setCategory(value)}
                className={`rounded-[8px] border px-[13px] py-[8px] text-[13.5px] font-medium ${
                  on
                    ? "border-[var(--color-accent)] bg-[var(--color-accent-soft)] text-[var(--color-accent)]"
                    : "border-[var(--color-line)] bg-white text-[var(--color-ink-body)] hover:bg-[oklch(0.97_0.004_85)]"
                }`}
              >
                {OVERRIDE_CATEGORY_LABEL[value]}
              </button>
            );
          })}
        </div>

        <label
          htmlFor="override-reason"
          className="mt-[22px] mb-[10px] block text-[12.5px] font-semibold text-[oklch(0.5_0.01_260)]"
        >
          사유 (필수)
        </label>
        <textarea
          id="override-reason"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          maxLength={INPUT_LIMITS.reason}
          placeholder="예: 고객에게 구두로 조기상환 조건을 추가 설명함"
          className="block min-h-[88px] w-full resize-y rounded-[10px] border border-[var(--color-line)] px-[16px] py-[14px] text-[15.5px] leading-[1.6]"
        />

        {requiresExplanationConfirmation ? (
          <label className="mt-[18px] flex items-start gap-[10px] text-[14.5px] leading-[1.55] text-[var(--color-ink-body)]">
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(e) => setConfirmed(e.target.checked)}
              className="mt-[3px]"
            />
            <span>
              이 항목을 고객에게 실제로 설명했으며, 고객 이해확인 질문을 진행해도
              됩니다.
              {!confirmed ? (
                <span className="mt-[4px] block text-[13px] text-[var(--color-override-body)]">
                  체크하지 않으면 해당 위험은 고객 질문에서 제외되고 미해결로
                  남습니다.
                </span>
              ) : null}
            </span>
          </label>
        ) : null}

        {error ? <ErrorNote className="mt-[18px]" error={error} /> : null}

        <div className="mt-[26px] flex flex-wrap items-center gap-[12px] sm:gap-[14px]">
          <button
            type="button"
            onClick={submit}
            disabled={!canSubmit}
            className="rounded-[10px] bg-[var(--color-accent)] px-[22px] py-[13px] text-[15px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
          >
            직원 판단 기록하고 진행
          </button>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            className="rounded-[10px] border border-[oklch(0.88_0.008_260)] px-[20px] py-[13px] text-[15px] font-semibold hover:bg-[oklch(0.97_0.004_85)]"
          >
            취소
          </button>
          <span className="ml-auto text-[13px] text-[var(--color-muted-soft)]">
            {category ? (reasonOk ? "기록 준비 완료" : "사유 5자 이상") : "사유 구분 선택"}
          </span>
        </div>
      </div>
    </div>
  );
}

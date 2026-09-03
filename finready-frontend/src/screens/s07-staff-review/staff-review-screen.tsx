"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { useResolveByStaff, useSession } from "@/shared/api/queries";
import {
  INPUT_LIMITS,
  UNDERSTANDING_AI_STATUS_LABEL,
} from "@/shared/constants/labels";
import { decideNextAction } from "@/shared/lib/resume";
import type { StaffDisposition } from "@/shared/types/domain";
import { ErrorNote } from "@/shared/ui/error-note";
import { ScreenSkeleton } from "@/shared/ui/screen-skeleton";
import { StaffShell, useScenarioQuery } from "@/shared/ui/staff-shell";
import { understandingStyle } from "@/shared/ui/status-pill";

/**
 * S07 — the staff member's call on a risk the AI could not settle.
 *
 * Reached either because two attempts ran out, or because a staff member
 * disagreed with the AI from the report. Whichever way, this records a
 * *new* decision beside the AI's; the attempts above it are shown exactly
 * as the classifier left them and are never rewritten.
 */
export function StaffReviewScreen({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  const params = useSearchParams();
  const query = useScenarioQuery();
  const session = useSession(sessionId);
  const resolve = useResolveByStaff(sessionId);
  const [reason, setReason] = useState("");
  /** Set when the resolution saved but the server gave us no resume point. */
  const [routeUnknown, setRouteUnknown] = useState(false);

  if (session.isError) {
    return (
      <div className="mx-auto max-w-[920px] px-[20px] py-[48px] sm:px-[40px] sm:py-[64px]">
        <ErrorNote error={session.error} onRetry={() => void session.refetch()} />
      </div>
    );
  }
  if (!session.data) return <ScreenSkeleton />;

  const states = session.data.understanding ?? [];
  const requested = params.get("risk");
  const state =
    states.find((s) => s.riskId === requested) ??
    states.find((s) => s.workflowStatus === "MANUAL_REVIEW_REQUIRED") ??
    states[0];

  if (!state) {
    return (
      <StaffShell sessionId={sessionId} revision={revisionOf(session.data)} current="s07">
        <div className="mx-auto max-w-[920px] px-[20px] py-[48px] sm:px-[40px] sm:py-[64px]">
          <p className="text-[17px] text-[var(--color-ink-muted)]">
            검토할 항목이 없습니다.
          </p>
        </div>
      </StaffShell>
    );
  }

  const attempts = state.attempts ?? [];
  const sessionClosed =
    session.data.sessionStatus === "SESSION_CLOSED_BY_STAFF" ||
    session.data.sessionStatus === "SESSION_CLOSED_WITH_UNRESOLVED";

  // 직원 처리를 받는 상태는 `MANUAL_REVIEW_REQUIRED` 하나다. `COMPLETE` 는 서버가
  // 종착으로 두어(`WorkflowStateMachine`) 409 `RISK_ALREADY_FINALIZED` 로 거절한다.
  // 이 화면은 위험 목록의 첫 항목으로도 열릴 수 있으므로(아래 `states[0]` 폴백)
  // 여기서도 막지 않으면 사유를 다 쓴 뒤에야 거절당한다.
  const isManualReview = state.workflowStatus === "MANUAL_REVIEW_REQUIRED";
  const alreadyFinal = state.workflowStatus === "COMPLETE";
  const readOnly = sessionClosed || alreadyFinal;
  const reasonOk =
    reason.trim().length >= 5 && reason.length <= INPUT_LIMITS.reason;
  const canSubmit = reasonOk && !resolve.isPending && !readOnly;

  const submit = (disposition: StaffDisposition) => {
    if (!canSubmit) return;
    resolve.mutate(
      {
        riskId: state.riskId as string,
        disposition,
        reason: reason.trim(),
        actor: "staff-demo",
      },
      {
        // The response says where to go (contract v1.4.2), so there is no
        // refetch-and-guess step: the client neither counts remaining risks
        // nor re-derives the branch rule. If the server somehow says nothing,
        // it stops and reports rather than picking a plausible destination.
        onSuccess: (result) => {
          const decision = decideNextAction(sessionId, result.nextAction, query);
          if (decision.kind === "unknown") {
            setRouteUnknown(true);
            return;
          }
          router.push(decision.href);
        },
      },
    );
  };

  return (
    <StaffShell
      sessionId={sessionId}
      revision={revisionOf(session.data)}
      current="s07"
    >
      <div className="screen-in mx-auto max-w-[920px] px-[20px] pt-[48px] pb-[72px] sm:px-[40px] sm:pt-[64px] sm:pb-[96px]">
        <p className="mb-[10px] text-[12.5px] font-semibold tracking-[0.1em] text-[var(--color-muted-soft)]">
          S07 · {isManualReview ? "직원 확인 필요" : alreadyFinal ? "처리 완료" : "직원 재확인"}
        </p>
        <h2 className="text-[32px] leading-[1.28] font-bold tracking-[-0.024em]">
          {isManualReview
            ? "이 항목은 직원이 함께 확인해야 합니다"
            : alreadyFinal
              ? "이 항목은 처리가 끝났습니다"
              : "이 항목을 직원이 다시 확인합니다"}
        </h2>
        <p className="mt-[14px] max-w-[760px] text-[17px] leading-[1.65] text-[var(--color-ink-muted)]">
          {isManualReview ? (
            <>
              두 번의 확인에도 이해 여부를 확정하지 못했습니다. 고객과 함께 확인한
              뒤 처리 결과를 남겨주세요. AI 판정은 아래 기록 그대로 보존되며, 직원
              처리는 별도로 남습니다.
            </>
          ) : alreadyFinal ? (
            <>
              이미 처리가 끝난 항목이라 기록을 변경할 수 없습니다. AI 판정과 직원
              처리는 아래에 각각 그대로 보존됩니다.
            </>
          ) : (
            <>
              AI 판정이 실제 상황과 다르다고 판단되면 직원 처리를 기록할 수 있습니다.
              AI 판정은 아래 기록 그대로 보존되며, 직원 처리는 별도로 남습니다.
            </>
          )}
        </p>

        <div className="mt-[32px] rounded-[14px] border border-[var(--color-line-soft)] bg-[var(--color-surface-muted)] px-[26px] py-[24px]">
          <p className="mb-[14px] font-mono text-[12px] font-semibold text-[var(--color-muted)]">
            {state.riskId} · {state.riskTitle}
          </p>
          {attempts.map((attempt) => {
            const style = attempt.aiStatus
              ? understandingStyle(attempt.aiStatus)
              : null;
            return (
              <div
                key={attempt.attempt}
                className="grid grid-cols-1 gap-[6px] border-t border-[var(--color-line-soft)] py-[12px] sm:grid-cols-[96px_1fr] sm:gap-[18px]"
              >
                <span className="text-[13px] font-semibold text-[oklch(0.5_0.01_260)]">
                  {attempt.attempt}차 답변
                </span>
                <div>
                  <p className="text-[15.5px] leading-[1.7]">{attempt.answer}</p>
                  <p
                    className="mt-[6px] text-[13px]"
                    style={{ color: style?.fg ?? "var(--color-muted)" }}
                  >
                    {attempt.aiStatus
                      ? UNDERSTANDING_AI_STATUS_LABEL[attempt.aiStatus]
                      : "판정 없음"}
                    {attempt.reason ? ` · ${attempt.reason}` : ""}
                  </p>
                </div>
              </div>
            );
          })}
        </div>

        {state.staffResolution ? (
          <div className="mt-[28px] rounded-[12px] border border-[var(--color-line-soft)] bg-white px-[24px] py-[20px]">
            <p className="text-[13px] font-semibold text-[var(--color-muted)]">
              기록된 직원 처리
            </p>
            <p className="mt-[8px] text-[15.5px] leading-[1.65]">
              {state.staffResolution.reason}
            </p>
            <p className="mt-[8px] font-mono text-[11.5px] text-[var(--color-muted-soft)]">
              {state.staffResolution.disposition === "UNRESOLVED"
                ? "미해결"
                : "직원 확인 완료"} · 처리자 {state.staffResolution.actor}
            </p>
          </div>
        ) : null}

        {readOnly ? (
          <p className="mt-[28px] text-[15.5px] text-[var(--color-muted)]">
            {sessionClosed
              ? "종료된 상담이므로 기록을 변경할 수 없습니다."
              : "이미 처리가 끝난 항목이라 기록을 변경할 수 없습니다."}
          </p>
        ) : (
          <>
            <label
              htmlFor="resolution-reason"
              className="mt-[28px] mb-[12px] block text-[12.5px] font-semibold text-[oklch(0.5_0.01_260)]"
            >
              처리 사유 (필수)
            </label>
            <textarea
              id="resolution-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={INPUT_LIMITS.reason}
              placeholder="예: 상품설명서 원문을 함께 보며 재설명 후 고객이 구두로 이해를 확인함"
              className="block min-h-[88px] w-full resize-y rounded-[10px] border border-[var(--color-line)] bg-white px-[16px] py-[14px] text-[15.5px] leading-[1.6]"
            />

            {resolve.isError ? (
              <ErrorNote className="mt-[18px]" error={resolve.error} />
            ) : null}

            {routeUnknown ? (
              <div
                role="alert"
                className="mt-[18px] rounded-[12px] border border-[var(--color-block-line)] bg-[var(--color-block-bg)] px-[24px] py-[18px]"
              >
                <p className="text-[15.5px] leading-[1.55] font-semibold">
                  처리는 저장됐지만 다음 단계를 확인하지 못했습니다.
                </p>
                <p className="mt-[6px] text-[14px] text-[var(--color-block-body)]">
                  상담 상태를 다시 불러온 뒤 이동해주세요.
                </p>
                <button
                  type="button"
                  onClick={() => {
                    setRouteUnknown(false);
                    void session.refetch().then((fresh) => {
                      const decision = decideNextAction(
                        sessionId,
                        fresh.data?.nextAction,
                        query,
                      );
                      if (decision.kind === "navigate") router.push(decision.href);
                      else setRouteUnknown(true);
                    });
                  }}
                  className="mt-[14px] rounded-[9px] border border-[var(--color-override-line)] bg-white px-[16px] py-[9px] text-[14px] font-semibold hover:bg-[oklch(0.97_0.004_85)]"
                >
                  상담 상태 다시 불러오기
                </button>
              </div>
            ) : null}

            <div className="mt-[24px] flex flex-wrap items-center gap-[12px] sm:gap-[14px]">
              <button
                type="button"
                onClick={() => submit("RESOLVED_BY_STAFF")}
                disabled={!canSubmit}
                className="rounded-[10px] bg-[var(--color-accent)] px-[24px] py-[13px] text-[15px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
              >
                직원 확인으로 해결 처리
              </button>
              <button
                type="button"
                onClick={() => submit("UNRESOLVED")}
                disabled={!canSubmit}
                className="rounded-[10px] border border-[oklch(0.88_0.008_260)] bg-white px-[20px] py-[13px] text-[15px] font-semibold hover:bg-[oklch(0.97_0.004_85)] disabled:opacity-50"
              >
                미해결로 남기기
              </button>
              <span className="w-full text-[13px] text-[var(--color-muted-soft)] sm:ml-auto sm:w-auto">
                미해결은 리포트와 종료 상태에 그대로 표시됩니다
              </span>
            </div>
          </>
        )}
      </div>
    </StaffShell>
  );
}

function revisionOf(session: { currentRevision?: { revision?: number } }): number {
  return session.currentRevision?.revision ?? 1;
}

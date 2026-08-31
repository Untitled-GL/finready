"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useCloseSession, useReport } from "@/shared/api/queries";
import {
  ACTOR_ROLE_LABEL,
  auditEventLabel,
  COVERAGE_POLICY_LABEL,
  COVERAGE_STATUS_LABEL,
  DISCLAIMER_REPORT,
  FINAL_DISPOSITION_LABEL,
  INPUT_LIMITS,
  UNDERSTANDING_AI_STATUS_LABEL,
} from "@/shared/constants/labels";
import {
  warningCoverageItems,
  type WarningCoverageItem,
} from "@/shared/lib/coverage-summary";
import type {
  ReportResponse,
  RiskUnderstandingState,
  SessionStatus,
} from "@/shared/types/domain";
import { ErrorNote } from "@/shared/ui/error-note";
import { ScreenSkeleton } from "@/shared/ui/screen-skeleton";
import { StaffShell, useScenarioQuery } from "@/shared/ui/staff-shell";
import { coverageStyle, StatusDot, understandingStyle } from "@/shared/ui/status-pill";

/**
 * S08 — the record of what happened, and the only place the session can be
 * closed.
 *
 * The layout's job is to keep two things visibly separate: what the AI
 * judged, and what a person decided. They sit in adjacent columns, both
 * always shown, because collapsing them into one status is exactly the
 * thing this product refuses to do. Closing is gated by the server's
 * `closeEligibility`, not by anything computed here.
 */
export function ReportScreen({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  const querySuffix = useScenarioQuery("&");
  const report = useReport(sessionId);
  const close = useCloseSession(sessionId);

  const [acknowledged, setAcknowledged] = useState(false);
  const [unresolvedReason, setUnresolvedReason] = useState("");

  if (report.isError) {
    return (
      <div className="mx-auto max-w-[1080px] px-[40px] py-[64px]">
        <ErrorNote error={report.error} onRetry={() => void report.refetch()} />
      </div>
    );
  }
  if (!report.data) return <ScreenSkeleton />;

  const data = report.data;
  const results = data.coverage?.results ?? [];
  const understanding = data.understanding ?? [];
  const eligibility = data.closeEligibility ?? {};
  const revisions = data.revisions ?? [];
  const pendingWarnings = eligibility.requiresWarningAcknowledgement ?? [];
  const warningItems = warningCoverageItems(pendingWarnings, results);
  const closed = isClosed(data.sessionStatus);

  const needsAck = pendingWarnings.length > 0;
  const needsReason = eligibility.requiresUnresolvedReason ?? false;
  const serverAllowsClose = eligibility.canClose ?? false;
  // 검증 리포트인데 "언제 한 상담인지"가 본문에 없었다. 세션 생성 감사 기록이
  // 항상 첫 행이므로 계약 변경 없이 여기서 읽는다.
  const startedAt = formatSessionStart(
    (data.auditEvents ?? [])[0]?.createdAt ?? revisions[0]?.createdAt,
  );
  const canClose =
    serverAllowsClose &&
    (!needsAck || acknowledged) &&
    (!needsReason || unresolvedReason.trim().length >= 5) &&
    !close.isPending;

  const submitClose = () => {
    if (!canClose) return;
    close.mutate({
      actor: "staff-demo",
      ...(needsReason ? { unresolvedReason: unresolvedReason.trim() } : {}),
      ...(needsAck ? { acknowledgedWarnings: pendingWarnings } : {}),
    });
  };

  return (
    <StaffShell
      sessionId={sessionId}
      revision={revisions[revisions.length - 1]?.revision ?? 1}
      current="s08"
    >
      <div className="screen-in px-[40px] pt-[48px] pb-[96px]">
        <div className="mx-auto max-w-[1080px]">
          <p className="mb-[10px] text-[12.5px] font-semibold tracking-[0.1em] text-[var(--color-muted-soft)]">
            S08 · FINREADY REPORT
          </p>
          <div className="flex items-end justify-between gap-[24px]">
            <div>
              <h2 className="text-[36px] leading-[1.22] font-bold tracking-[-0.028em]">
                상담 검증 리포트
              </h2>
              <p className="mt-[12px] text-[16.5px] text-[var(--color-ink-muted)]">
                {data.product?.name} ·{" "}
                {revisions[revisions.length - 1]?.revision ?? 1}차 상담 기록 기준
              </p>
              {startedAt ? (
                <p className="mt-[6px] text-[14.5px] text-[var(--color-muted)]">
                  상담 시작 {startedAt}
                </p>
              ) : null}
            </div>
            <SessionStatusBadge status={data.sessionStatus} />
          </div>

          <Summary data={data} />

          {/* 1 — coverage */}
          <section className="mt-[48px]">
            <h3 className="text-[20px] font-semibold tracking-[-0.015em]">
              1 · 상품 위험 설명 충족도
            </h3>
            <p className="mt-[8px] text-[14.5px] text-[oklch(0.52_0.01_260)]">
              AI가 판정한 뒤 상담 원문에서 근거를 다시 확인한 최종 상태입니다. 고객
              이해 확인으로 넘어갈 수 있는지도 이 상태로 결정됩니다.
            </p>
            <div className="mt-[18px]">
              {results.map((result) => {
                const style = coverageStyle(result.coverageStatus);
                return (
                  <div
                    key={result.riskId}
                    className="grid grid-cols-[56px_1fr_190px_150px] items-center gap-[16px] border-t border-[var(--color-line-soft)] py-[13px]"
                  >
                    <span className="font-mono text-[11.5px] font-semibold text-[var(--color-muted)]">
                      {result.riskId}
                    </span>
                    <span className="text-[15.5px]">
                      {result.title}
                      {result.downgraded ? (
                        <span className="ml-[10px] text-[12.5px] text-[var(--color-warn-fg)]">
                          AI는 &lsquo;{COVERAGE_STATUS_LABEL[result.classifierStatus]}
                          &rsquo;으로 봤으나 근거 확인에서 기준 미달
                        </span>
                      ) : null}
                    </span>
                    <span
                      className="inline-flex w-fit items-center gap-[7px] rounded-full px-[11px] py-[5px] text-[13px] font-semibold"
                      style={{ background: style.bg, color: style.fg }}
                    >
                      <StatusDot shape={style.shape} color={style.dotColor} />
                      {style.label}
                    </span>
                    <span className="font-mono text-[11.5px] font-medium text-[var(--color-muted-soft)]">
                      {COVERAGE_POLICY_LABEL[result.coveragePolicy]}
                    </span>
                  </div>
                );
              })}
            </div>
          </section>

          {/* 2 — understanding: AI verdict beside human disposition */}
          <section className="mt-[48px]">
            <h3 className="text-[20px] font-semibold tracking-[-0.015em]">
              2 · 고객 이해 확인
            </h3>
            <p className="mt-[8px] text-[14.5px] text-[oklch(0.52_0.01_260)]">
              설명 충족도와 별개로, 고객이 핵심 위험을 어떻게 이해했는지 확인한
              결과입니다.
            </p>
            {understanding.length === 0 ? (
              <p className="mt-[16px] border-t border-[var(--color-line-soft)] pt-[16px] text-[15.5px] text-[oklch(0.5_0.01_260)]">
                아직 고객 이해 확인을 진행하지 않았습니다.
              </p>
            ) : (
              <div className="mt-[18px]">
                <div className="grid grid-cols-[56px_1fr_200px_200px_180px] items-center gap-[16px] pb-[10px]">
                  <span />
                  {["핵심 위험", "AI 판정", "최종 처리"].map((label) => (
                    <span
                      key={label}
                      className="text-[12px] font-semibold tracking-[0.08em] text-[var(--color-muted-soft)]"
                    >
                      {label}
                    </span>
                  ))}
                  <span />
                </div>

                {understanding.map((state) => (
                  <UnderstandingRow
                    key={state.riskId}
                    state={state}
                    closed={closed}
                    onReview={() =>
                      router.push(
                        `/session/${sessionId}/review?risk=${state.riskId}${querySuffix}`,
                      )
                    }
                  />
                ))}

                <p className="mt-[14px] text-[13px] text-[var(--color-muted-faint)]">
                  AI가 판정한 내용과 직원이 처리한 내용은 서로 지우지 않고 둘 다
                  기록에 남습니다.
                </p>
              </div>
            )}
          </section>

          {/* 3 — staff decisions, with the reason actually written */}
          <section className="mt-[48px]">
            <h3 className="text-[20px] font-semibold tracking-[-0.015em]">
              3 · 직원 판단 및 처리
            </h3>
            <StaffDecisions data={data} />
          </section>

          {/* 4 — history */}
          <section className="mt-[48px]">
            <h3 className="text-[20px] font-semibold tracking-[-0.015em]">
              4 · 검증 이력
            </h3>
            <p className="mt-[8px] text-[14.5px] text-[oklch(0.52_0.01_260)]">
              {/* 아래 타임라인은 고객 확인이 있어야 그려진다. 설명이 없는 내용을
                  약속하지 않도록 문장을 상태에 맞춘다. */}
              {understanding.length === 0
                ? "상담 기록이 어떻게 보완됐는지 보여줍니다."
                : "상담 기록 변경과 핵심 위험별 확인 흐름입니다."}
            </p>

            <div className="mt-[24px] flex flex-wrap gap-[12px]">
              {revisions.map((revision, index) => (
                <div
                  key={revision.revisionId}
                  className="rounded-[10px] border border-[var(--color-line-soft)] bg-white px-[16px] py-[12px]"
                >
                  <p className="text-[13.5px] font-semibold">
                    {index === 0
                      ? "1차 · 최초 상담 기록"
                      : `${revision.revision}차 · 보완 설명 반영`}
                  </p>
                  <p className="mt-[4px] text-[12.5px] text-[var(--color-muted-soft)]">
                    {(revision.charCount ?? 0).toLocaleString()}자 저장됨
                  </p>
                </div>
              ))}
            </div>

            {understanding.length > 0 ? (
              <div className="mt-[24px] grid grid-cols-3 gap-[32px]">
                {understanding.map((state) => (
                  <Timeline key={state.riskId} state={state} />
                ))}
              </div>
            ) : null}
          </section>

          {/* 5 — audit trail: every automatic system record, in one place */}
          <AuditLog data={data} />

          {/* close */}
          {closed ? (
            <div
              className="fade-in mt-[40px] flex items-center gap-[16px] rounded-[12px] px-[24px] py-[20px]"
              style={
                data.sessionStatus === "SESSION_CLOSED_WITH_UNRESOLVED"
                  ? {
                      background: "var(--color-warn-bg-soft)",
                      border: "1px solid var(--color-override-line)",
                    }
                  : {
                      background: "var(--color-ok-banner-bg)",
                      border: "1px solid var(--color-ok-banner-line)",
                    }
              }
            >
              <p className="flex-1 text-[16.5px] font-semibold">
                {data.sessionStatus === "SESSION_CLOSED_WITH_UNRESOLVED"
                  ? `미해결 ${(data.unresolvedRiskIds ?? []).length}건을 남기고 상담을 종료했습니다.`
                  : "상담을 종료했습니다."}
              </p>
              <button
                type="button"
                onClick={() => router.push("/")}
                className="rounded-[9px] border border-[oklch(0.86_0.008_260)] bg-white px-[18px] py-[11px] text-[14px] font-semibold hover:bg-[oklch(0.97_0.004_85)]"
              >
                처음으로
              </button>
            </div>
          ) : (
            <div className="mt-[40px] border-t border-[var(--color-line-strong)] pt-[28px]">
              {/* 서버가 아직 종료를 허용하지 않는 상태에서 체크박스를 먼저 보여주면,
                  눌러도 버튼이 안 켜져 고장난 것처럼 보인다. 실제로 막고 있는
                  이유를 먼저, 크게 알린다. */}
              {!serverAllowsClose ? (
                <div className="mb-[20px] max-w-[840px] rounded-[12px] border border-[var(--color-line)] bg-[oklch(0.975_0.003_260)] px-[20px] py-[18px]">
                  <p className="text-[15.5px] font-semibold">
                    아직 상담을 종료할 수 없습니다.
                  </p>
                  <p className="mt-[6px] text-[14.5px] leading-[1.6] text-[var(--color-ink-muted)]">
                    고객 이해 확인을 끝내야 종료할 수 있습니다.
                  </p>
                  {needsAck ? (
                    <div className="mt-[16px] border-t border-[var(--color-line-soft)] pt-[14px]">
                      <p className="text-[14px] text-[var(--color-ink-muted)]">
                        종료할 때 함께 확인하게 될 항목 {pendingWarnings.length}건
                      </p>
                      <WarningRows items={warningItems} muted />
                    </div>
                  ) : null}
                </div>
              ) : (
                <>
                  {needsAck ? (
                    <label className="mb-[20px] flex max-w-[840px] items-start gap-[12px] rounded-[12px] border border-[var(--color-warn-line)] bg-[var(--color-warn-bg-soft)] px-[20px] py-[18px]">
                      <input
                        type="checkbox"
                        checked={acknowledged}
                        onChange={(e) => setAcknowledged(e.target.checked)}
                        className="mt-[4px]"
                      />
                      <span className="text-[15px] leading-[1.65]">
                        <b className="font-semibold">
                          참고 확인 항목 {pendingWarnings.length}건이 완전히 설명되지
                          않은 상태입니다.
                        </b>
                        <br />
                        내용을 확인했으며 이 상태로 상담을 종료합니다. 이 확인은 AI
                        설명 충족도 판정을 변경하지 않습니다.
                        <WarningRows items={warningItems} />
                      </span>
                    </label>
                  ) : null}

                  {needsReason ? (
                    <div className="mb-[20px]">
                      <p className="mb-[10px] text-[15.5px] font-semibold">
                        미해결 항목이 있어 종료 사유가 필요합니다
                      </p>
                      <textarea
                        value={unresolvedReason}
                        onChange={(e) => setUnresolvedReason(e.target.value)}
                        maxLength={INPUT_LIMITS.reason}
                        placeholder="예: 고객 요청으로 상담을 종료하고, 미해결 항목은 다음 방문 시 재확인 예정"
                        className="block min-h-[76px] w-full max-w-[720px] resize-y rounded-[10px] border border-[var(--color-line)] bg-white px-[16px] py-[14px] text-[15.5px] leading-[1.6]"
                      />
                    </div>
                  ) : null}
                </>
              )}

              {close.isError ? (
                <ErrorNote className="mb-[20px]" error={close.error} />
              ) : null}

              <div className="flex items-center gap-[16px]">
                <button
                  type="button"
                  onClick={submitClose}
                  disabled={!canClose}
                  className="rounded-[10px] bg-[var(--color-accent)] px-[28px] py-[14px] text-[15.5px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
                >
                  {needsReason ? "미해결 항목과 함께 종료" : "상담 종료"}
                </button>
                <span className="text-[13.5px] text-[var(--color-muted-soft)]">
                  {serverAllowsClose
                    ? "종료 후에는 기록이 변경되지 않습니다."
                    : ""}
                </span>
              </div>
            </div>
          )}

          <p className="mt-[28px] text-[12.5px] text-[var(--color-muted-faint)]">
            {data.disclaimer ?? DISCLAIMER_REPORT}
          </p>
        </div>
      </div>
    </StaffShell>
  );
}

/* ── pieces ──────────────────────────────────────────────────────── */

function isClosed(status: SessionStatus | undefined): boolean {
  return (
    status === "SESSION_CLOSED_BY_STAFF" ||
    status === "SESSION_CLOSED_WITH_UNRESOLVED"
  );
}

const SESSION_STATUS_LABEL: Record<SessionStatus, string> = {
  DRAFT: "작성 중",
  // "설명 충족도 확인"이었으나 충족됐다는 뜻으로 읽혔다. 이 값은 결과가 아니라
  // 진행 단계이므로 단계 이름을 그대로 쓴다.
  COVERAGE_ANALYZED: "설명 충족도 분석 완료",
  GATE_BLOCKED: "보완 필요",
  UNDERSTANDING_IN_PROGRESS: "고객 확인 진행 중",
  AWAITING_STAFF_REVIEW: "직원 검토 대기",
  SESSION_CLOSED_BY_STAFF: "종료됨",
  SESSION_CLOSED_WITH_UNRESOLVED: "미해결 포함 종료",
};

function SessionStatusBadge({ status }: { status: SessionStatus | undefined }) {
  if (!status) return null;
  const warm =
    status === "GATE_BLOCKED" || status === "SESSION_CLOSED_WITH_UNRESOLVED";
  return (
    <span
      className="shrink-0 rounded-full px-[14px] py-[7px] text-[13px] font-semibold"
      style={{
        background: warm ? "var(--color-none-bg)" : "var(--color-ok-bg)",
        color: warm ? "var(--color-none-fg)" : "var(--color-ok-fg)",
      }}
    >
      {SESSION_STATUS_LABEL[status]}
    </span>
  );
}

/**
 * 종료 전 확인이 필요한 WARN_ONLY 항목들. 종료 가능/불가능 두 자리에서 같은
 * 목록을 보여주므로 한 곳에 둔다 — `muted`는 아직 누를 수 없는 안내라는 뜻이다.
 */
function WarningRows({
  items,
  muted = false,
}: {
  items: WarningCoverageItem[];
  muted?: boolean;
}) {
  return (
    <span className="mt-[12px] grid gap-[8px]">
      {items.map((item) => (
        <span
          key={item.riskId}
          className="grid grid-cols-[52px_1fr_auto] items-center gap-[10px] border-t pt-[8px] text-[13.5px]"
          style={{
            borderColor: muted
              ? "var(--color-line-soft)"
              : "var(--color-warn-line)",
          }}
        >
          <span className="font-mono text-[11.5px] font-semibold text-[var(--color-muted)]">
            {item.riskId}
          </span>
          <span>{item.title}</span>
          <span
            className="font-semibold"
            style={{
              color: muted ? "var(--color-muted)" : "var(--color-warn-fg)",
            }}
          >
            {item.coverageStatus
              ? COVERAGE_STATUS_LABEL[item.coverageStatus]
              : "확인 필요"}
          </span>
        </span>
      ))}
    </span>
  );
}

/** Last attempt = the AI's standing verdict on that risk. */
function lastAiStatus(state: RiskUnderstandingState) {
  const attempts = state.attempts ?? [];
  return attempts[attempts.length - 1]?.aiStatus;
}

function Summary({ data }: { data: ReportResponse }) {
  const results = data.coverage?.results ?? [];
  const understanding = data.understanding ?? [];
  const explained = results.filter((r) => r.coverageStatus === "EXPLAINED").length;
  const auto = understanding.filter(
    (u) => u.finalDisposition === "AUTO_RESOLVED",
  ).length;
  const byStaff = understanding.filter(
    (u) => u.finalDisposition === "RESOLVED_BY_STAFF",
  ).length;
  const unresolved = (data.unresolvedRiskIds ?? []).length;

  /**
   * 고객 이해 확인 전에는 아래 네 항목이 "집계해보니 0건"이 아니라 "아직 집계할
   * 것이 없는" 상태다. 둘 다 `0건`으로 찍으면 미해결 0건(좋은 신호)과 고객 확인
   * 0건(미완료 신호)이 같아 보이므로 값을 비워 둔다.
   */
  const notStarted = understanding.length === 0;
  const pending = "—";

  const cells = [
    {
      q: "상품 위험 설명이 확인됐나요?",
      a: `${explained} / ${results.length}건`,
      sub: "근거 검증까지 마친 최종 상태",
      warn: explained < results.length,
    },
    {
      q: "고객 확인 대상",
      a: notStarted ? pending : `${understanding.length}건`,
      // 예전에는 "R01 · R02 · R03"이 고정 문자열이라 0건과 나란히 붙어 서로
      // 모순돼 보였다. 실제 대상만 적는다.
      sub: notStarted
        ? "고객 확인 전"
        : understanding.map((u) => u.riskId).join(" · "),
      warn: false,
    },
    {
      q: "AI 자동 확인",
      a: notStarted ? pending : `${auto}건`,
      sub: "직원 개입 없이 자동 처리",
      warn: false,
    },
    {
      q: "직원 확인",
      a: notStarted ? pending : `${byStaff}건`,
      sub: "직원이 직접 확인·처리",
      warn: false,
    },
    {
      q: "미해결",
      a: notStarted ? pending : `${unresolved}건`,
      sub: notStarted
        ? "고객 확인 후 집계"
        : unresolved > 0
          ? "종료 사유 필요"
          : "없음",
      warn: unresolved > 0,
    },
  ];

  return (
    <div className="mt-[40px] border-t border-b border-[var(--color-line-strong)] py-[28px]">
      <div className="grid grid-cols-5">
        {cells.map((cell) => (
          <div key={cell.q} className="pr-[28px]">
            <p className="text-[13.5px] leading-[1.5] text-[var(--color-muted)]">
              {cell.q}
            </p>
            <p
              className="mt-[10px] text-[20px] font-semibold tracking-[-0.015em]"
              style={{
                color: cell.warn
                  ? "var(--color-none-fg)"
                  : cell.a === pending
                    ? "var(--color-muted-soft)"
                    : undefined,
              }}
            >
              {cell.a}
            </p>
            <p className="mt-[6px] text-[13px] text-[var(--color-muted-soft)]">
              {cell.sub}
            </p>
          </div>
        ))}
      </div>

      {notStarted ? (
        <p className="mt-[20px] text-[13.5px] text-[var(--color-muted)]">
          고객 이해 확인을 아직 진행하지 않아 오른쪽 네 항목은 집계되지 않았습니다.
        </p>
      ) : null}
    </div>
  );
}

function UnderstandingRow({
  state,
  closed,
  onReview,
}: {
  state: RiskUnderstandingState;
  closed: boolean;
  onReview: () => void;
}) {
  const aiStatus = lastAiStatus(state);
  const style = aiStatus ? understandingStyle(aiStatus) : null;
  const disposition = state.finalDisposition;
  const needsStaffCall = state.workflowStatus === "MANUAL_REVIEW_REQUIRED";
  const dispositionWarn =
    disposition === "UNRESOLVED" || disposition === "SKIPPED_BY_OVERRIDE";

  return (
    <div className="grid grid-cols-[56px_1fr_200px_200px_180px] items-center gap-[16px] border-t border-[var(--color-line-soft)] py-[13px]">
      <span className="font-mono text-[11.5px] font-semibold text-[var(--color-muted)]">
        {state.riskId}
      </span>
      <span className="text-[15.5px]">{state.riskTitle}</span>

      <span>
        {style && aiStatus ? (
          <span
            className="inline-flex w-fit items-center gap-[7px] rounded-full px-[11px] py-[5px] text-[13px] font-semibold"
            style={{ background: style.bg, color: style.fg }}
          >
            <StatusDot shape={style.shape} color={style.dotColor} />
            {UNDERSTANDING_AI_STATUS_LABEL[aiStatus]}
          </span>
        ) : (
          <span className="text-[14px] text-[var(--color-muted-soft)]">판정 없음</span>
        )}
      </span>

      <span
        className="text-[14px]"
        style={{ color: dispositionWarn ? "var(--color-none-fg)" : undefined }}
      >
        {disposition ? FINAL_DISPOSITION_LABEL[disposition] : "진행 중"}
      </span>

      {/* 직원 처리를 받을 수 있는 Risk 에만 띄운다.
          `MANUAL_REVIEW_REQUIRED` 는 처분(finalDisposition)이 아직 없는 유일한
          상태이고, 나머지는 전부 `COMPLETE` — 서버가 COMPLETE 를 종착으로 두어
          (`WorkflowStateMachine`) 직원 처리를 409 `RISK_ALREADY_FINALIZED` 로
          거절한다. 모든 행에 띄우면 눌러서 사유까지 쓴 뒤에야 거절당한다. */}
      {!closed && needsStaffCall ? (
        <button
          type="button"
          onClick={onReview}
          className="justify-self-start px-[2px] py-[6px] text-[13px] text-[var(--color-muted-soft)] underline underline-offset-[3px] hover:text-[var(--color-ink-body)]"
        >
          직원이 확인
        </button>
      ) : (
        <span />
      )}
    </div>
  );
}

function StaffDecisions({ data }: { data: ReportResponse }) {
  const overrides = data.overrides ?? [];
  const resolutions = (data.understanding ?? []).filter((u) => u.staffResolution);

  if (overrides.length === 0 && resolutions.length === 0) {
    return (
      <p className="mt-[16px] border-t border-[var(--color-line-soft)] pt-[16px] text-[15.5px] text-[oklch(0.5_0.01_260)]">
        직원 개입 없이 AI 판정과 고객 확인만으로 진행됐습니다.
      </p>
    );
  }

  return (
    <div className="mt-[18px]">
      {overrides.map((override, index) => (
        <div
          key={`${override.riskId}-${index}`}
          className="border-t border-[var(--color-line-soft)] py-[16px]"
        >
          <div className="flex flex-wrap items-center gap-[12px]">
            <span className="font-mono text-[11.5px] font-semibold text-[var(--color-muted)]">
              {override.riskId}
            </span>
            <span className="text-[15.5px] font-semibold">Gate 직원 판단</span>
            <span className="font-mono text-[11.5px] font-medium text-[var(--color-muted-soft)]">
              {override.category} · {override.actor}
              {override.staffExplanationConfirmed === false
                ? " · 설명 미확인으로 질문 제외"
                : ""}
            </span>
          </div>
          <p className="mt-[8px] text-[15.5px] leading-[1.65] text-[var(--color-ink-body)]">
            {override.reason}
          </p>
        </div>
      ))}

      {resolutions.map((state) => {
        const resolution = state.staffResolution!;
        return (
          <div
            key={state.riskId}
            className="border-t border-[var(--color-line-soft)] py-[16px]"
          >
            <div className="flex flex-wrap items-center gap-[12px]">
              <span className="font-mono text-[11.5px] font-semibold text-[var(--color-muted)]">
                {state.riskId}
              </span>
              <span className="text-[15.5px] font-semibold">
                {resolution.disposition === "UNRESOLVED"
                  ? "미해결로 종결"
                  : "직원 확인으로 해결"}
              </span>
              <span className="font-mono text-[11.5px] font-medium text-[var(--color-muted-soft)]">
                {resolution.actor}
                {resolution.createdAt
                  ? ` · ${new Date(resolution.createdAt).toLocaleString("ko-KR")}`
                  : ""}
              </span>
            </div>
            {/* The reason the staff member actually typed, not a marker. */}
            <p className="mt-[8px] text-[15.5px] leading-[1.65] text-[var(--color-ink-body)]">
              {resolution.reason}
            </p>
          </div>
        );
      })}
    </div>
  );
}

function Timeline({ state }: { state: RiskUnderstandingState }) {
  const attempts = state.attempts ?? [];
  const events: Array<{ label: string; sub: string; tone: string }> = [];

  attempts.forEach((attempt, index) => {
    const status = attempt.aiStatus;
    events.push({
      label: `${attempt.attempt}차 답변`,
      sub: status
        ? `${UNDERSTANDING_AI_STATUS_LABEL[status]}${attempt.reason ? ` · ${attempt.reason}` : ""}`
        : "",
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
      sub: "AI 자동 확인",
      tone: "var(--color-ok-dot)",
    });
  } else if (state.finalDisposition === "SKIPPED_BY_OVERRIDE") {
    events.push({
      label: "질문 제외",
      sub: "직원 판단으로 제외 · 미해결로 남음",
      tone: "var(--color-none-dot)",
    });
  }

  return (
    <div>
      <p className="mb-[16px] text-[16px] font-semibold">
        {state.riskId} {state.riskTitle}
      </p>
      {events.map((event, index) => (
        <div key={index} className="grid grid-cols-[14px_1fr] items-start gap-[14px]">
          <div className="flex h-full flex-col items-center">
            <span
              className="mt-[4px] size-[9px] flex-none rounded-full"
              style={{ background: event.tone }}
            />
            {index < events.length - 1 ? (
              <span className="w-px flex-1 bg-[var(--color-line)]" />
            ) : null}
          </div>
          <div className="pb-[16px]">
            <p className="text-[15px] font-medium">{event.label}</p>
            {event.sub ? (
              <p className="mt-[4px] text-[13px] leading-[1.5] text-[var(--color-muted)]">
                {event.sub}
              </p>
            ) : null}
          </div>
        </div>
      ))}
    </div>
  );
}

function AuditLog({ data }: { data: ReportResponse }) {
  // 원문 `payloadSummary`는 `key=value` 나열이라 상담 직원에게는 읽을 것이
  // 없다. 감사 기록이므로 지우지는 않고 기본으로 접어 둔다.
  const [showRaw, setShowRaw] = useState(false);
  const events = data.auditEvents ?? [];
  if (events.length === 0) return null;

  return (
    <section className="mt-[48px]">
      <div className="flex items-end justify-between gap-[24px]">
        <div>
          <h3 className="text-[20px] font-semibold tracking-[-0.015em]">
            5 · 처리 기록
          </h3>
          <p className="mt-[8px] text-[14.5px] text-[oklch(0.52_0.01_260)]">
            시스템·AI·직원이 이 상담을 처리하며 남긴 자동 기록입니다. 열람 전용이며
            고치거나 지울 수 없습니다.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setShowRaw((on) => !on)}
          aria-expanded={showRaw}
          className="shrink-0 px-[2px] py-[6px] text-[13px] text-[var(--color-muted-soft)] underline underline-offset-[3px] hover:text-[var(--color-ink-body)]"
        >
          {showRaw ? "기술 상세 숨기기" : "기술 상세 보기"}
        </button>
      </div>

      <div className="mt-[18px]">
        <div className="grid grid-cols-[130px_70px_1fr] gap-[16px] border-b border-[var(--color-line-strong)] pb-[10px]">
          {["시간", "처리 주체", "처리 내용"].map((label) => (
            <span
              key={label}
              className="text-[12px] font-semibold tracking-[0.08em] text-[var(--color-muted-soft)]"
            >
              {label}
            </span>
          ))}
        </div>

        {events.map((event, index) => (
          <div
            key={index}
            className="grid grid-cols-[130px_70px_1fr] items-start gap-[16px] border-t border-[var(--color-line-faint)] py-[11px]"
          >
            <span className="text-[13px] text-[var(--color-muted)]">
              {formatEventTime(event.createdAt)}
            </span>
            <span className="text-[13px] text-[var(--color-muted)]">
              {event.actorRole ? ACTOR_ROLE_LABEL[event.actorRole] : "-"}
            </span>
            <span>
              <span className="text-[14.5px]">{auditEventLabel(event.eventType)}</span>
              {showRaw && event.payloadSummary ? (
                <span className="mt-[2px] block font-mono text-[12px] leading-[1.5] text-[var(--color-muted-faint)]">
                  {event.payloadSummary}
                </span>
              ) : null}
            </span>
          </div>
        ))}
      </div>
    </section>
  );
}

function formatSessionStart(createdAt: string | undefined): string | null {
  if (!createdAt) return null;
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleString("ko-KR", {
    year: "numeric",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatEventTime(createdAt: string | undefined): string {
  if (!createdAt) return "-";
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return "-";
  return date.toLocaleString("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

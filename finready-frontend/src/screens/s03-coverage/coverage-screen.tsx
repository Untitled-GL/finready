"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { OverrideDialog } from "@/screens/s03-coverage/override-dialog";
import { RiskDetail } from "@/screens/s03-coverage/risk-detail";
import {
  useAnalyzeCoverage,
  useCachedCoverage,
  useDemoProduct,
  useOverrideGate,
  useSession,
} from "@/shared/api/queries";
import { COVERAGE_STATUS_LABEL, DISCLAIMER } from "@/shared/constants/labels";
import {
  countCoverageStatuses,
  coverageBannerTone,
  warningCoverageItems,
} from "@/shared/lib/coverage-summary";
import type { CoverageResult } from "@/shared/types/domain";
import { AnalysisOverlay } from "@/shared/ui/analysis-overlay";
import { ErrorNote } from "@/shared/ui/error-note";
import { ScreenSkeleton } from "@/shared/ui/screen-skeleton";
import { coverageStyle, StatusDot } from "@/shared/ui/status-pill";
import {
  StaffShell,
  useScenarioQuery,
} from "@/shared/ui/staff-shell";

/**
 * S03 — was each product risk actually explained?
 *
 * Every judgement on this screen comes from the server: `coverageStatus`,
 * `gateStatus` and `canProceedToUnderstanding` are read, never recomputed.
 * The screen's job is to make the reasoning inspectable — which risk, what
 * the transcript said, what the verified fact says.
 */
export function CoverageScreen({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  const params = useSearchParams();
  const query = useScenarioQuery();
  const querySuffix = useScenarioQuery("&");
  const demo = useDemoProduct();
  const session = useSession(sessionId);
  const reanalyze = useAnalyzeCoverage(sessionId);
  const cachedCoverage = useCachedCoverage(sessionId);
  const override = useOverrideGate(sessionId);
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [warningAck, setWarningAck] = useState(false);

  if (demo.isError || session.isError) {
    return (
      <div className="mx-auto max-w-[1360px] px-[40px] py-[64px]">
        <ErrorNote
          error={demo.error ?? session.error}
          onRetry={() => {
            void demo.refetch();
            void session.refetch();
          }}
        />
      </div>
    );
  }
  if (!demo.data || !session.data) return <ScreenSkeleton />;

  // Prefer the coverage response the server most recently returned; fall back
  // to the session snapshot, which the contract allows to be null.
  const coverage = cachedCoverage.data ?? session.data.coverage;
  const revisionText = session.data.currentRevision?.text ?? "";
  const revision = session.data.currentRevision?.revision ?? 1;
  const risks = demo.data.risks ?? [];

  // The session status tells us whether analysis has already happened, even
  // when this particular response did not include the result.
  const analysed =
    session.data.sessionStatus !== "DRAFT" &&
    (session.data.currentRevision?.revision ?? 0) > 0;

  if (!coverage) {
    return (
      <StaffShell sessionId={sessionId} revision={revision} current="s03">
        <div className="mx-auto max-w-[1360px] px-[40px] py-[64px]">
          {analysed ? (
            <>
              {/* The session says coverage exists but this snapshot did not
                  carry it. Re-running the analysis is idempotent for the same
                  revision, so the staff member can ask for it — rather than
                  the screen quietly re-posting and hiding the gap. */}
              <p className="text-[17px] leading-[1.6] text-[var(--color-ink-muted)]">
                이 상담의 설명 충족도 결과를 화면에 불러오지 못했습니다. 분석 기록은
                남아 있으며, 다시 불러와도 판정은 바뀌지 않습니다.
              </p>
              {reanalyze.isError ? (
                <ErrorNote
                  className="mt-[20px] max-w-[720px]"
                  error={reanalyze.error}
                  onRetry={() => reanalyze.mutate(undefined)}
                />
              ) : null}
              <button
                type="button"
                onClick={() => reanalyze.mutate(undefined)}
                disabled={reanalyze.isPending}
                className="mt-[20px] rounded-[10px] bg-[var(--color-accent)] px-[28px] py-[14px] text-[15.5px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
              >
                {reanalyze.isPending ? "불러오는 중…" : "설명 충족도 다시 불러오기"}
              </button>
            </>
          ) : (
            <>
              <p className="text-[17px] text-[var(--color-ink-muted)]">
                아직 분석된 상담 내용이 없습니다.
              </p>
              <button
                type="button"
                onClick={() => router.push(`/session/${sessionId}/transcript${query}`)}
                className="mt-[20px] rounded-[10px] bg-[var(--color-accent)] px-[28px] py-[14px] text-[15.5px] font-semibold text-white hover:bg-[var(--color-accent-hover)]"
              >
                상담 내용 입력하기
              </button>
            </>
          )}
        </div>
      </StaffShell>
    );
  }

  const results = coverage.risks ?? [];
  const blocking = coverage.blockingRiskIds ?? [];
  const warningRiskIds = coverage.warningRiskIds ?? [];
  const needsWarningAck = warningRiskIds.length > 0;
  const warningItems = warningCoverageItems(warningRiskIds, results);

  // The server decides whether the customer step may start. `gateStatus` is
  // only used to phrase *why* — never to re-judge the answer.
  const canProceed = coverage.canProceedToUnderstanding ?? false;
  const withOverride = coverage.gateStatus === "READY_WITH_STAFF_OVERRIDE";

  const selectedId = params.get("risk") ?? blocking[0] ?? results[0]?.riskId ?? "";
  const selected =
    results.find((r) => r.riskId === selectedId) ?? results[0] ?? null;
  const selectedRisk = risks.find((r) => r.riskId === selected?.riskId);

  const riskLabels = Object.fromEntries(
    results.map((r) => [r.riskId as string, r.title ?? ""]),
  );
  const gateRequiredCount = results.filter(
    (r) => r.coveragePolicy === "GATE_REQUIRED",
  ).length;

  const selectRisk = (riskId: string) => {
    const next = new URLSearchParams(params.toString());
    next.set("risk", riskId);
    router.replace(`/session/${sessionId}/coverage?${next.toString()}`, {
      scroll: false,
    });
  };

  const blockingUnderstandingTargets = blocking.filter(
    (id) => risks.find((r) => r.riskId === id)?.understandingCheck,
  );

  return (
    <StaffShell sessionId={sessionId} revision={revision} current="s03">
      {reanalyze.isPending ? <AnalysisOverlay label="다시 분석하고 있습니다" /> : null}

      {overrideOpen ? (
        <OverrideDialog
          blockingRiskIds={blocking}
          riskLabels={riskLabels}
          requiresExplanationConfirmation={blockingUnderstandingTargets.length > 0}
          pending={override.isPending}
          error={override.isError ? override.error : null}
          onClose={() => setOverrideOpen(false)}
          onSubmit={(req) =>
            override.mutate(req, { onSuccess: () => setOverrideOpen(false) })
          }
        />
      ) : null}

      <div className="screen-in px-[40px] pt-[40px] pb-[80px]">
        <div className="mx-auto max-w-[1360px]">
          <p className="mb-[10px] text-[12.5px] font-semibold tracking-[0.1em] text-[var(--color-muted-soft)]">
            S03 · 설명 충족도 (COVERAGE)
          </p>
          <div className="flex items-end justify-between gap-[24px]">
            <div>
              <h2 className="text-[34px] leading-[1.25] font-bold tracking-[-0.025em]">
                설명 충족도
              </h2>
              <p className="mt-[12px] text-[17px] leading-[1.6] text-[var(--color-ink-muted)]">
                상담 원문에서 {results.length}개 상품 위험이 각각 어떻게 설명됐는지
                확인했습니다.
              </p>
            </div>
          </div>

          <GateBanner
            blocked={!canProceed}
            withOverride={withOverride}
            warningRiskIds={warningRiskIds}
            results={results}
            targetCount={
              (session.data.understanding ?? []).length ||
              risks.filter((r) => r.understandingCheck).length
            }
          />

          {reanalyze.isError ? (
            <ErrorNote
              className="mt-[20px]"
              error={reanalyze.error}
              onRetry={() => reanalyze.mutate(undefined)}
            />
          ) : null}

          <div className="mt-[36px] grid grid-cols-[452px_1fr] items-start gap-[32px]">
            <div>
              <div className="flex items-baseline justify-between border-b border-[var(--color-line-strong)] pb-[12px]">
                <span className="text-[12px] font-semibold tracking-[0.1em] text-[var(--color-muted-soft)]">
                  상품 위험 {results.length}건
                </span>
                <span className="text-[12.5px] text-[var(--color-muted-soft)]">
                  GATE 대상 {gateRequiredCount}건
                </span>
              </div>

              <ul>
                {results.map((result) => {
                  const on = result.riskId === selected?.riskId;
                  const style = coverageStyle(result.coverageStatus);
                  return (
                    <li key={result.riskId}>
                      <button
                        type="button"
                        aria-current={on ? "true" : undefined}
                        onClick={() => selectRisk(result.riskId as string)}
                        className="flex w-full items-center gap-[12px] rounded-[8px] border-b border-[var(--color-line-faint)] px-[14px] py-[14px] text-left"
                        style={{
                          background: on ? "var(--color-accent-tint)" : "transparent",
                          boxShadow: on ? "inset 2px 0 0 var(--color-accent)" : "none",
                        }}
                      >
                        <span
                          className="font-mono text-[11.5px] font-semibold"
                          style={{
                            color: on
                              ? "var(--color-accent-code)"
                              : "var(--color-muted)",
                          }}
                        >
                          {result.riskId}
                        </span>
                        <span className="text-[15px] font-medium">{result.title}</span>
                        <span
                          className="ml-auto inline-flex shrink-0 items-center gap-[7px] rounded-full px-[11px] py-[5px] text-[13px] font-semibold"
                          style={{ background: style.bg, color: style.fg }}
                        >
                          <StatusDot shape={style.shape} color={style.dotColor} />
                          {style.label}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>

              <p className="mt-[16px] text-[12.5px] leading-[1.6] text-[var(--color-muted-faint)]">
                {DISCLAIMER}
              </p>
            </div>

            <div>
              {selected ? (
                <RiskDetail
                  result={selected}
                  risk={selectedRisk}
                  revisionText={revisionText}
                />
              ) : null}

              <div className="mt-[40px] flex items-center gap-[18px] border-t border-[var(--color-line)] pt-[24px]">
                {!canProceed ? (
                  <>
                    <button
                      type="button"
                      onClick={() =>
                        router.push(
                          `/session/${sessionId}/transcript?mode=supplement${querySuffix}`,
                        )
                      }
                      className="rounded-[10px] bg-[var(--color-accent)] px-[28px] py-[14px] text-[15.5px] font-semibold text-white hover:bg-[var(--color-accent-hover)]"
                    >
                      보완 설명 입력
                    </button>
                    <button
                      type="button"
                      onClick={() => reanalyze.mutate(undefined)}
                      disabled={reanalyze.isPending}
                      className="rounded-[10px] border border-[oklch(0.86_0.008_260)] bg-white px-[22px] py-[14px] text-[15.5px] font-semibold hover:bg-[oklch(0.96_0.004_85)] disabled:opacity-60"
                    >
                      재분석
                    </button>
                    <button
                      type="button"
                      onClick={() => setOverrideOpen(true)}
                      className="ml-auto px-[2px] py-[6px] text-[13.5px] text-[var(--color-muted-soft)] underline underline-offset-[3px] hover:text-[var(--color-ink-body)]"
                    >
                      직원 판단으로 진행
                    </button>
                  </>
                ) : (
                  <div className="w-full">
                    {needsWarningAck ? (
                      <label className="mb-[18px] flex max-w-[840px] items-start gap-[12px] rounded-[12px] border border-[var(--color-warn-line)] bg-[var(--color-warn-bg-soft)] px-[20px] py-[18px]">
                        <input
                          type="checkbox"
                          checked={warningAck}
                          onChange={(e) => setWarningAck(e.target.checked)}
                          className="mt-[4px]"
                        />
                        <span className="text-[15px] leading-[1.65]">
                          <b className="font-semibold">
                            참고 확인 항목 {warningRiskIds.length}건이 완전히 설명되지
                            않은 상태입니다.
                          </b>
                          <br />
                          내용을 확인했으며 고객 이해확인을 시작합니다. 이 확인은 AI
                          설명 충족도 판정을 변경하지 않습니다.
                          <span className="mt-[12px] grid gap-[8px]">
                            {warningItems.map((item) => (
                              <span
                                key={item.riskId}
                                className="grid grid-cols-[52px_1fr_auto] items-center gap-[10px] border-t border-[var(--color-warn-line)] pt-[8px] text-[13.5px]"
                              >
                                <span className="font-mono text-[11.5px] font-semibold text-[var(--color-muted)]">
                                  {item.riskId}
                                </span>
                                <span>{item.title}</span>
                                <span className="font-semibold text-[var(--color-warn-fg)]">
                                  {item.coverageStatus
                                    ? COVERAGE_STATUS_LABEL[item.coverageStatus]
                                    : "확인 필요"}
                                </span>
                              </span>
                            ))}
                          </span>
                        </span>
                      </label>
                    ) : null}

                    <div className="flex items-center gap-[18px]">
                      <button
                        type="button"
                        disabled={needsWarningAck && !warningAck}
                        onClick={() => router.push(`/session/${sessionId}/handoff${query}`)}
                        className="rounded-[10px] bg-[var(--color-accent)] px-[28px] py-[14px] text-[15.5px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
                      >
                        고객 이해확인 시작
                      </button>
                      <span className="text-[13.5px] text-[var(--color-muted-soft)]">
                        이해확인 대상{" "}
                        {(session.data.understanding ?? []).length ||
                          risks.filter((r) => r.understandingCheck).length}
                        건
                      </span>
                    </div>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>
      </div>
    </StaffShell>
  );
}

function GateBanner({
  blocked,
  withOverride,
  warningRiskIds,
  results,
  targetCount,
}: {
  blocked: boolean;
  withOverride: boolean;
  warningRiskIds: string[];
  results: CoverageResult[];
  targetCount: number;
}) {
  const tone = coverageBannerTone(!blocked, warningRiskIds, withOverride);

  if (blocked) {
    return (
      <div className="fade-in mt-[28px] flex items-start gap-[14px] rounded-[12px] border border-[var(--color-block-line)] bg-[var(--color-block-bg)] px-[24px] py-[20px]">
        <span
          aria-hidden
          className="mt-[2px] size-[20px] flex-none rounded-full bg-[var(--color-block-icon)] text-center text-[13px]/[20px] font-bold text-white"
        >
          !
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[16.5px] leading-[1.5] font-semibold">
            고객 이해확인을 진행하기 전에 확인이 필요한 설명 항목이 있습니다.
          </p>
          <CoverageCountSummary results={results} />
          <p className="mt-[12px] text-[13.5px] text-[var(--color-block-body)]">
            아래 상품 위험 목록에서 상태와 근거를 확인한 뒤 필요한 설명을 보완해주세요.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div
      className="fade-in mt-[28px] flex items-start gap-[14px] rounded-[12px] px-[24px] py-[18px]"
      style={
        tone === "warning"
          ? {
              background: "var(--color-warn-bg-soft)",
              border: "1px solid var(--color-override-line)",
              boxShadow: "inset 3px 0 0 var(--color-override-bar)",
            }
          : {
              background: "var(--color-ok-banner-bg)",
              border: "1px solid var(--color-ok-banner-line)",
            }
      }
    >
      <span
        aria-hidden
        className="mt-[2px] size-[20px] flex-none text-center text-[12px]/[20px] font-bold text-white"
        style={{
          borderRadius: tone === "warning" ? "5px" : "50%",
          background: tone === "warning"
            ? "var(--color-override-icon)"
            : "var(--color-ok-icon)",
        }}
      >
        {tone === "warning" ? "!" : "✓"}
      </span>
      <div className="flex-1">
        <p className="text-[16.5px] font-semibold">
          {withOverride
            ? "직원 판단으로 진행합니다. 설명 충족 확인은 완료되지 않았습니다."
            : tone === "warning"
              ? "고객 이해확인은 진행할 수 있지만, 확인이 필요한 설명이 남아 있습니다."
              : "설명 충족도 확인이 끝났습니다. 고객 이해확인을 진행할 수 있습니다."}
        </p>
        <p
          className="mt-[5px] text-[14px]"
          style={{
            color: tone === "warning"
              ? "var(--color-override-body)"
              : "var(--color-ok-fg)",
          }}
        >
          {withOverride
            ? "AI 판정은 변경되지 않았고 리포트에 그대로 남습니다"
            : tone === "warning"
              ? `참고 확인 항목 ${warningRiskIds.length}건 · 상담 종료 전 직원 확인 필요`
              : `이해확인 대상 ${targetCount}건 · 차단 항목 없음`}
        </p>
        <CoverageCountSummary results={results} />
      </div>
    </div>
  );
}

const COVERAGE_STATUS_ORDER = [
  "EXPLAINED",
  "INSUFFICIENT",
  "NOT_FOUND",
  "CONTRADICTED",
] as const;

function CoverageCountSummary({ results }: { results: CoverageResult[] }) {
  const counts = countCoverageStatuses(results);

  return (
    <dl className="mt-[14px] grid grid-cols-2 gap-x-[28px] gap-y-[10px] sm:grid-cols-4">
      {COVERAGE_STATUS_ORDER.map((status) => {
        const style = coverageStyle(status);
        return (
          <div key={status} className="border-l-2 pl-[10px]" style={{ borderColor: style.dotColor }}>
            <dt className="text-[12.5px] text-[var(--color-muted)]">
              {COVERAGE_STATUS_LABEL[status]}
            </dt>
            <dd className="mt-[2px] text-[16px] font-semibold text-[var(--color-ink)]">
              {counts[status]}건
            </dd>
          </div>
        );
      })}
    </dl>
  );
}

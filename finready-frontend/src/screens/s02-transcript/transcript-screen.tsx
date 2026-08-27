"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import {
  useDemoProduct,
  useSession,
  useSubmitTranscript,
} from "@/shared/api/queries";
import { INPUT_LIMITS } from "@/shared/constants/labels";
import { findPreset } from "@/shared/lib/demo-catalog";
import { AnalysisOverlay } from "@/shared/ui/analysis-overlay";
import { ErrorNote } from "@/shared/ui/error-note";
import { ScreenSkeleton } from "@/shared/ui/screen-skeleton";
import {
  StaffShell,
  useScenario,
  useScenarioQuery,
} from "@/shared/ui/staff-shell";

/**
 * S02 — the transcript boundary.
 *
 * The textarea stands in for an already-transcribed consultation arriving
 * from the firm's existing recording/STT system. P0 does not transcribe
 * anything itself; this screen simulates that hand-off.
 *
 * Saving never edits what is already there: the full text is posted and the
 * server writes a new immutable revision.
 */
export function TranscriptScreen({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  const params = useSearchParams();
  const scenario = useScenario();
  const query = useScenarioQuery();
  const demo = useDemoProduct();
  const session = useSession(sessionId);
  const submit = useSubmitTranscript(sessionId);

  const isSupplement = params.get("mode") === "supplement";
  const [draft, setDraft] = useState("");

  if (demo.isError || session.isError) {
    return (
      <div className="mx-auto max-w-[1040px] px-[40px] py-[64px]">
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

  const existingText = session.data.currentRevision?.text ?? "";
  const currentRevision = session.data.currentRevision?.revision ?? 0;
  const nextRevision = currentRevision + 1;
  const preset = findPreset(demo.data.demoPresets, scenario);
  const sampleText = isSupplement
    ? preset?.supplementTranscript
    : preset?.transcript;

  // Supplementing carries the previous transcript forward — the server
  // stores whole snapshots, never appended fragments.
  const fullText = isSupplement
    ? `${existingText}\n${draft.trim()}`
    : draft;
  const charCount = fullText.length;
  const overLimit = charCount > INPUT_LIMITS.transcript;
  const canSubmit = draft.trim().length > 0 && !overLimit && !submit.isPending;

  const analyze = () => {
    if (!canSubmit) return;
    submit.mutate(
      { text: fullText },
      { onSuccess: () => router.push(`/session/${sessionId}/coverage${query}`) },
    );
  };

  const fillSample = () => {
    if (sampleText) setDraft(sampleText);
  };

  return (
    <StaffShell
      sessionId={sessionId}
      revision={Math.max(currentRevision, 1)}
      current="s02"
    >
      {submit.isPending ? <AnalysisOverlay /> : null}

      <div className="screen-in mx-auto max-w-[1040px] px-[40px] pt-[56px] pb-[96px]">
        <p className="mb-[10px] text-[12.5px] font-semibold tracking-[0.1em] text-[var(--color-muted-soft)]">
          S02 · 상담 입력
        </p>

        <div className="flex items-end justify-between gap-[24px]">
          <div>
            <h2 className="text-[34px] leading-[1.25] font-bold tracking-[-0.025em]">
              {isSupplement ? "보완 설명 입력" : "상담 내용 확인"}
            </h2>
            <p className="mt-[12px] text-[17px] leading-[1.6] text-[var(--color-ink-muted)]">
              {isSupplement
                ? "추가로 설명한 내용을 입력하면 기존 내용과 합쳐 새 revision으로 저장됩니다."
                : "이미 텍스트로 정리된 상담 내용입니다. 확인 후 설명 충족도 분석을 시작하세요."}
            </p>
          </div>
          <span
            className="shrink-0 text-[13.5px]"
            style={{
              color: overLimit ? "var(--color-bad-fg)" : "var(--color-muted)",
            }}
          >
            {charCount.toLocaleString()} / {INPUT_LIMITS.transcript.toLocaleString()}자
          </span>
        </div>

        {isSupplement && existingText ? (
          <div className="mt-[32px] rounded-[12px] border border-[var(--color-line-soft)] bg-[var(--color-surface-muted)] px-[22px] py-[20px]">
            <p className="mb-[10px] font-mono text-[12px] font-semibold text-[var(--color-muted-soft)]">
              revision {currentRevision} · 기존 상담 내용
            </p>
            <p className="text-[15px] leading-[1.8] whitespace-pre-wrap text-[oklch(0.5_0.01_260)]">
              {existingText}
            </p>
          </div>
        ) : null}

        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          aria-label={isSupplement ? "보완 설명" : "상담 내용"}
          placeholder={
            isSupplement
              ? "고객에게 추가로 설명한 내용을 입력하세요"
              : "텍스트로 정리된 상담 내용을 붙여넣으세요"
          }
          className="mt-[24px] block min-h-[340px] w-full resize-y rounded-[14px] border border-[var(--color-line)] bg-white px-[28px] py-[26px] text-[16.5px] leading-[1.85] text-[oklch(0.24_0.008_260)]"
        />

        {submit.isError ? (
          <ErrorNote className="mt-[20px]" error={submit.error} onRetry={analyze} />
        ) : null}

        <div className="mt-[24px] flex items-center gap-[16px]">
          <button
            type="button"
            onClick={analyze}
            disabled={!canSubmit}
            className="rounded-[10px] bg-[var(--color-accent)] px-[28px] py-[14px] text-[15.5px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
          >
            {isSupplement ? "보완 내용 저장하고 재분석" : "설명 충족도 분석"}
          </button>
          {sampleText ? (
            <button
              type="button"
              onClick={fillSample}
              disabled={submit.isPending}
              className="rounded-[10px] border border-[oklch(0.86_0.008_260)] bg-white px-[22px] py-[14px] text-[15.5px] font-semibold hover:bg-[oklch(0.96_0.004_85)] disabled:opacity-60"
            >
              {isSupplement ? "샘플 보완 설명 채우기" : "샘플 상담 내용 채우기"}
            </button>
          ) : null}
          <span className="ml-auto text-[13.5px] text-[var(--color-muted-soft)]">
            저장 시 revision {nextRevision} 생성 · 기존 내용은 수정되지 않습니다
          </span>
        </div>
      </div>
    </StaffShell>
  );
}

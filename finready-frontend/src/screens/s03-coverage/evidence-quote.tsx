import type { Evidence } from "@/shared/types/domain";

const CONTEXT_CHARS = 70;

const FAILURE_COPY: Record<string, string> = {
  NOT_FOUND: "이 근거 문장을 상담 원문에서 확인하지 못했습니다.",
  AMBIGUOUS:
    "이 근거 문장이 상담 원문에 여러 번 등장해 위치를 특정할 수 없습니다.",
  TOO_SHORT: "근거 구간이 너무 짧아 원문 대조를 통과하지 못했습니다.",
  TOO_LONG: "근거 구간이 너무 길어 원문 대조를 통과하지 못했습니다.",
  EMPTY: "근거 구간이 비어 있습니다.",
};

/**
 * Renders the quoted span inside its surrounding transcript text.
 *
 * The highlight is placed with the offsets the server computed, never by
 * searching for the text again — re-finding it client-side is exactly how a
 * highlight ends up pointing at the wrong occurrence. When provenance
 * failed there is no trustworthy position, so this shows why instead of
 * guessing a location.
 */
export function EvidenceQuote({
  evidence,
  revisionText,
}: {
  evidence: Evidence;
  revisionText: string;
}) {
  const { startOffset, endOffset, provenanceValid, provenanceFailureReason } =
    evidence;

  if (!provenanceValid || startOffset == null || endOffset == null) {
    return (
      <div>
        <p className="text-[16px] leading-[1.7] text-[var(--color-ink-body)]">
          {FAILURE_COPY[provenanceFailureReason ?? ""] ??
            "근거 구간을 원문에서 확인하지 못했습니다."}
        </p>
        <p className="mt-[10px] text-[15px] leading-[1.7] text-[var(--color-muted)]">
          “{evidence.text}”
        </p>
        <p className="mt-[10px] font-mono text-[11.5px] text-[var(--color-muted-soft)]">
          PROVENANCE {provenanceFailureReason ?? "INVALID"}
        </p>
      </div>
    );
  }

  const from = Math.max(0, startOffset - CONTEXT_CHARS);
  const to = Math.min(revisionText.length, endOffset + CONTEXT_CHARS);
  const pre = revisionText.slice(from, startOffset);
  const highlighted = revisionText.slice(startOffset, endOffset);
  const post = revisionText.slice(endOffset, to);

  return (
    <>
      <p className="text-[17.5px] leading-[1.75] text-[var(--color-ink-soft)]">
        {from > 0 ? "… " : ""}
        {pre}
        <mark className="rounded-[3px] bg-[var(--color-ok-mark)] px-[3px] py-[1px]">
          {highlighted}
        </mark>
        {post}
        {to < revisionText.length ? " …" : ""}
      </p>
      <p className="mt-[12px] text-[13px] text-[var(--color-muted)]">
        상담 원문 {startOffset.toLocaleString()}~{endOffset.toLocaleString()}자 · 근거
        검증 통과
      </p>
    </>
  );
}

import { EvidenceQuote } from "@/screens/s03-coverage/evidence-quote";
import { COVERAGE_POLICY_LABEL } from "@/shared/constants/labels";
import type { CoverageResult, ProductRisk } from "@/shared/types/domain";
import { coverageStatusChangeLabel } from "@/shared/lib/coverage-summary";
import { CoverageStatusPill } from "@/shared/ui/status-pill";

/**
 * The selected risk, with evidence as a first-class part of the reasoning
 * rather than a footnote: what the transcript said, what the verified fact
 * says, and why the two were judged to agree or not.
 */
export function RiskDetail({
  result,
  risk,
  revisionText,
}: {
  result: CoverageResult;
  risk: ProductRisk | undefined;
  revisionText: string;
}) {
  const status = result.coverageStatus;
  const evidence = result.evidence;
  const page = risk?.sourcePage;

  const meta = [
    COVERAGE_POLICY_LABEL[result.coveragePolicy],
    risk?.understandingCheck ? "이해확인 대상" : null,
    result.override ? "직원 판단 기록됨" : null,
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <div>
      <div className="flex flex-wrap items-center gap-[12px]">
        <span className="font-mono text-[12px] font-semibold text-[var(--color-muted)]">
          {result.riskId}
        </span>
        <h3 className="text-[24px] font-semibold tracking-[-0.018em]">
          {result.title ?? risk?.title}
        </h3>
        <CoverageStatusPill status={status} />
        <span className="ml-auto font-mono text-[11.5px] font-medium text-[var(--color-muted)]">
          {meta}
        </span>
      </div>

      {/* Verification changed the classifier's call — show both, never one
          merged status. */}
      {result.downgraded ? (
        <p className="mt-[16px] rounded-[10px] border border-[var(--color-warn-line)] bg-[var(--color-warn-bg-soft)] px-[18px] py-[12px] text-[14px] leading-[1.6] text-[var(--color-ink-body)]">
          {coverageStatusChangeLabel(result.classifierStatus, status)}
        </p>
      ) : null}

      {status === "EXPLAINED" && evidence ? (
        <>
          <div className="fade-in mt-[22px] border-l-2 border-[var(--color-ok-dot)] pl-[20px]">
            <p className="mb-[10px] text-[12px] font-semibold tracking-[0.08em] text-[var(--color-ok-kicker)]">
              상담 원문에서 확인
            </p>
            <EvidenceQuote evidence={evidence} revisionText={revisionText} />
          </div>
          <VerifiedFact fact={risk?.fact} page={page} />
        </>
      ) : null}

      {status === "CONTRADICTED" && evidence ? (
        <div className="fade-in mt-[20px]">
          <p className="text-[15.5px] leading-[1.6] text-[var(--color-bad-body)]">
            누락이 아니라, 사실과 다르게 설명된 것으로 보입니다.
          </p>
          <div className="mt-[20px] grid grid-cols-2 overflow-hidden rounded-[14px] border border-[var(--color-bad-line)]">
            <div className="bg-white px-[24px] py-[22px]">
              <p className="mb-[10px] text-[12px] font-semibold tracking-[0.08em] text-[var(--color-muted)]">
                검수된 위험 사실
              </p>
              <p className="text-[17px] leading-[1.72]">{risk?.fact}</p>
              {page ? (
                <p className="mt-[12px] text-[12.5px] text-[var(--color-muted-soft)]">
                  상품설명서 {page}페이지
                </p>
              ) : null}
            </div>
            <div className="border-l border-[var(--color-bad-line)] bg-[var(--color-bad-panel)] px-[24px] py-[22px]">
              <p className="mb-[10px] text-[12px] font-semibold tracking-[0.08em] text-[var(--color-bad-kicker)]">
                상담 내용
              </p>
              <p className="text-[17px] leading-[1.72]">{evidence.text}</p>
              <p className="mt-[12px] text-[12.5px] text-[var(--color-bad-meta)]">
                의미 관계 {result.semanticRelation}
              </p>
            </div>
          </div>
        </div>
      ) : null}

      {status === "NOT_FOUND" ? (
        <div className="fade-in mt-[22px] rounded-[14px] border border-dashed border-[var(--color-none-panel-line)] bg-[var(--color-none-panel)] px-[24px] py-[22px]">
          <p className="text-[16.5px] leading-[1.65] text-[var(--color-none-body)]">
            상담 원문에서 이 위험에 대한 설명을 찾지 못했습니다. 설명하지 않았다는
            판정이 아니라, 상담 기록에서 확인되지 않았다는 의미입니다.
          </p>
          <div className="mt-[18px] border-t border-[var(--color-none-rule)] pt-[16px]">
            <p className="mb-[8px] text-[12px] font-semibold tracking-[0.08em] text-[var(--color-none-kicker)]">
              검수된 위험 사실
            </p>
            <p className="text-[16px] leading-[1.7]">{risk?.fact}</p>
            {page ? (
              <p className="mt-[8px] text-[12.5px] text-[var(--color-none-meta)]">
                상품설명서 {page}페이지
              </p>
            ) : null}
          </div>
        </div>
      ) : null}

      {status === "INSUFFICIENT" ? (
        <div className="fade-in mt-[22px]">
          <div className="border-l-2 border-[var(--color-warn-dot)] pl-[20px]">
            <p className="mb-[10px] text-[12px] font-semibold tracking-[0.08em] text-[var(--color-warn-kicker)]">
              상담 원문에서 확인된 부분
            </p>
            {evidence ? (
              <EvidenceQuote evidence={evidence} revisionText={revisionText} />
            ) : (
              <p className="text-[16px] leading-[1.7] text-[var(--color-muted)]">
                대조할 근거 구간이 없습니다.
              </p>
            )}
          </div>
          <div className="mt-[22px] border-t border-[var(--color-line-soft)] pt-[20px]">
            <p className="mb-[8px] text-[12px] font-semibold tracking-[0.08em] text-[var(--color-muted)]">
              검수된 위험 사실 대비 부족한 부분
            </p>
            <p className="text-[16px] leading-[1.7] text-[var(--color-ink-body)]">
              {risk?.fact}
            </p>
            <p className="mt-[10px] text-[13px] text-[var(--color-muted-soft)]">
              {result.coveragePolicy === "WARN_ONLY"
                ? "참고 확인 항목이므로 진행은 가능하며, 리포트에 기록됩니다."
                : "필수 확인 항목이므로 보완 후 재분석이 필요합니다."}
            </p>
          </div>
        </div>
      ) : null}

      <div className="mt-[26px] border-t border-[var(--color-line-soft)] pt-[20px]">
        <p className="mb-[8px] text-[12px] font-semibold tracking-[0.08em] text-[var(--color-muted)]">
          AI 판단 근거
        </p>
        <p className="text-[15px] leading-[1.7] text-[var(--color-ink-body)]">
          {result.classifierReason}
        </p>
        {result.verificationReason ? (
          <p className="mt-[8px] font-mono text-[11.5px] text-[var(--color-muted-soft)]">
            {result.verificationReason}
          </p>
        ) : null}
      </div>

      {result.override ? (
        <div className="mt-[22px] rounded-[12px] border border-[var(--color-override-line)] bg-[var(--color-warn-bg-soft)] px-[20px] py-[16px]">
          <p className="text-[13px] font-semibold text-[var(--color-override-body)]">
            직원 판단 기록 · {result.override.category}
          </p>
          <p className="mt-[6px] text-[15px] leading-[1.65]">
            {result.override.reason}
          </p>
          <p className="mt-[8px] font-mono text-[11.5px] text-[var(--color-muted-soft)]">
            {result.override.actor} · AI 판정은 변경되지 않았습니다
          </p>
        </div>
      ) : null}
    </div>
  );
}

function VerifiedFact({ fact, page }: { fact?: string; page?: number }) {
  return (
    <div className="mt-[26px] border-t border-[var(--color-line-soft)] pt-[20px]">
      <p className="mb-[8px] text-[12px] font-semibold tracking-[0.08em] text-[var(--color-muted)]">
        검수된 위험 사실
      </p>
      <p className="text-[16px] leading-[1.7] text-[var(--color-ink-body)]">{fact}</p>
      {page ? (
        <p className="mt-[8px] text-[13px] text-[var(--color-muted-soft)]">
          상품설명서 {page}페이지
        </p>
      ) : null}
    </div>
  );
}

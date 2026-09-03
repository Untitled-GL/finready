import type {
  CoverageResult,
  CoverageStatus,
} from "@/shared/types/domain";
import { COVERAGE_STATUS_LABEL } from "@/shared/constants/labels";

export type CoverageStatusCounts = Record<CoverageStatus, number>;
export type CoverageBannerTone = "blocked" | "warning" | "ready";

export interface WarningCoverageItem {
  riskId: string;
  title: string;
  coverageStatus: CoverageStatus | undefined;
}

export function countCoverageStatuses(
  results: CoverageResult[],
): CoverageStatusCounts {
  const counts: CoverageStatusCounts = {
    EXPLAINED: 0,
    INSUFFICIENT: 0,
    NOT_FOUND: 0,
    CONTRADICTED: 0,
  };

  for (const result of results) {
    counts[result.coverageStatus] += 1;
  }

  return counts;
}

export function coverageBannerTone(
  canProceedToUnderstanding: boolean,
  warningRiskIds: string[] | undefined,
  withOverride: boolean,
): CoverageBannerTone {
  if (!canProceedToUnderstanding) return "blocked";
  if (withOverride || (warningRiskIds?.length ?? 0) > 0) return "warning";
  return "ready";
}

export function warningCoverageItems(
  warningRiskIds: string[],
  results: CoverageResult[],
): WarningCoverageItem[] {
  const resultsByRiskId = new Map(
    results.map((result) => [result.riskId as string, result]),
  );

  return warningRiskIds.map((riskId) => {
    const result = resultsByRiskId.get(riskId);
    return {
      riskId,
      title: result?.title ?? riskId,
      coverageStatus: result?.coverageStatus,
    };
  });
}

export function coverageStatusChangeLabel(
  classifierStatus: CoverageStatus,
  coverageStatus: CoverageStatus,
): string {
  return `AI 최초 판정: ${COVERAGE_STATUS_LABEL[classifierStatus]} · 원문 확인 후: ${COVERAGE_STATUS_LABEL[coverageStatus]}`;
}

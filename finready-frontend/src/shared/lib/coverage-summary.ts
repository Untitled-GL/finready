import type {
  CoverageResult,
  CoverageStatus,
} from "@/shared/types/domain";

export type CoverageStatusCounts = Record<CoverageStatus, number>;

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

import { describe, expect, it } from "vitest";
import { countCoverageStatuses } from "@/shared/lib/coverage-summary";
import type { CoverageResult } from "@/shared/types/domain";

const results = [
  { riskId: "R01", coverageStatus: "CONTRADICTED" },
  { riskId: "R02", coverageStatus: "NOT_FOUND" },
  { riskId: "R03", coverageStatus: "NOT_FOUND" },
  { riskId: "R04", coverageStatus: "INSUFFICIENT" },
  { riskId: "R05", coverageStatus: "EXPLAINED" },
  { riskId: "R06", coverageStatus: "EXPLAINED" },
] as CoverageResult[];

describe("coverage status summary", () => {
  it("counts every canonical status including zero-count statuses", () => {
    expect(countCoverageStatuses(results)).toEqual({
      EXPLAINED: 2,
      INSUFFICIENT: 1,
      NOT_FOUND: 2,
      CONTRADICTED: 1,
    });

    expect(countCoverageStatuses([])).toEqual({
      EXPLAINED: 0,
      INSUFFICIENT: 0,
      NOT_FOUND: 0,
      CONTRADICTED: 0,
    });
  });
});

import { describe, expect, it } from "vitest";
import {
  coverageStatusChangeLabel,
  countCoverageStatuses,
  coverageBannerTone,
  warningCoverageItems,
} from "@/shared/lib/coverage-summary";
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

  it("uses only server gate fields to choose blocked, warning, and ready banners", () => {
    expect(coverageBannerTone(false, [], false)).toBe("blocked");
    expect(coverageBannerTone(true, ["R05"], false)).toBe("warning");
    expect(coverageBannerTone(true, [], true)).toBe("warning");
    expect(coverageBannerTone(true, [], false)).toBe("ready");
  });

  it("maps warning ids to human-readable coverage details without dropping unknown ids", () => {
    expect(warningCoverageItems(["R03", "R99", "R02"], results)).toEqual([
      { riskId: "R03", title: "R03", coverageStatus: "NOT_FOUND" },
      { riskId: "R99", title: "R99", coverageStatus: undefined },
      { riskId: "R02", title: "R02", coverageStatus: "NOT_FOUND" },
    ]);
  });

  it("describes both improving and worsening verification changes without reversing their meaning", () => {
    expect(coverageStatusChangeLabel("INSUFFICIENT", "EXPLAINED")).toBe(
      "AI 최초 판정: 설명 불충분 · 원문 확인 후: 설명 확인",
    );
    expect(coverageStatusChangeLabel("EXPLAINED", "INSUFFICIENT")).toBe(
      "AI 최초 판정: 설명 확인 · 원문 확인 후: 설명 불충분",
    );
  });
});

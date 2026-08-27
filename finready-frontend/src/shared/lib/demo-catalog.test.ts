import { describe, expect, it } from "vitest";
import {
  answersForRisk,
  findCustomer,
  findPreset,
  scenarioSearch,
} from "@/shared/lib/demo-catalog";
import type { CustomerProfile, DemoAnswer, DemoPreset } from "@/shared/types/domain";

const customers: CustomerProfile[] = [
  { id: "CUST_A", label: "대표 고객" },
  { id: "CUST_F", label: "쉬운 설명 고객" },
];

const presets: DemoPreset[] = [
  { id: "main", label: "대표", transcript: "대표 상담", supplementTranscript: "보완" },
  { id: "safety", label: "안전", transcript: "안전 상담", supplementTranscript: null },
];

const answers: DemoAnswer[] = [
  { riskId: "R01", expectedLabel: "UNDERSTOOD", answer: "이해 답변 1" },
  { riskId: "R02", expectedLabel: "UNDERSTOOD", answer: "다른 위험 답변" },
  { riskId: "R01", expectedLabel: "MISUNDERSTOOD", answer: "오해 답변" },
  { riskId: "R01", expectedLabel: "UNDERSTOOD", answer: "이해 답변 2" },
];

describe("demo catalog selection", () => {
  it("resolves the customer saved on the session and falls back to the first preset", () => {
    expect(findCustomer(customers, "CUST_F")?.label).toBe("쉬운 설명 고객");
    expect(findCustomer(customers, "missing")?.id).toBe("CUST_A");
  });

  it("preserves a null supplement from the selected server preset", () => {
    expect(findPreset(presets, "safety")?.supplementTranscript).toBeNull();
    expect(findPreset(presets, "missing")?.id).toBe("main");
  });

  it("returns every server answer for the current risk in server order", () => {
    expect(answersForRisk(answers, "R01").map((item) => item.answer)).toEqual([
      "이해 답변 1",
      "오해 답변",
      "이해 답변 2",
    ]);
  });

  it("encodes arbitrary server preset ids for routing", () => {
    expect(scenarioSearch("safety review")).toBe("?scenario=safety%20review");
    expect(scenarioSearch(null)).toBe("");
    expect(scenarioSearch("safety review", "&")).toBe("&scenario=safety%20review");
  });
});

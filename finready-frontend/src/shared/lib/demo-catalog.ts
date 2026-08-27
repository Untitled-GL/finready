import type {
  CustomerProfile,
  DemoAnswer,
  DemoPreset,
} from "@/shared/types/domain";

export function findCustomer(
  customers: CustomerProfile[] | undefined,
  customerId: string | null | undefined,
): CustomerProfile | undefined {
  if (!customers?.length) return undefined;
  return customers.find((customer) => customer.id === customerId) ?? customers[0];
}

export function findPreset(
  presets: DemoPreset[] | undefined,
  scenarioId: string | null | undefined,
): DemoPreset | undefined {
  if (!presets?.length) return undefined;
  return presets.find((preset) => preset.id === scenarioId) ?? presets[0];
}

export function answersForRisk(
  answers: DemoAnswer[] | undefined,
  riskId: string,
): DemoAnswer[] {
  return (answers ?? []).filter((answer) => answer.riskId === riskId);
}

export function scenarioSearch(
  scenarioId: string | null | undefined,
  prefix: "?" | "&" = "?",
): string {
  return scenarioId ? `${prefix}scenario=${encodeURIComponent(scenarioId)}` : "";
}

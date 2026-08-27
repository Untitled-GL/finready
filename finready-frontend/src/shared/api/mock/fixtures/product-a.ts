import type {
  CustomerProfile,
  DemoProductResponse,
  ProductRisk,
} from "@/shared/types/domain";

/**
 * Product A seed data for the mock backend.
 *
 * Synthetic. Composed for MVP verification from the structure and risk
 * factors of publicly available financial product disclosures; it copies no
 * real product's wording or figures.
 *
 * This lives in the mock layer, not in `entities/`, because it stands in for
 * data the server owns. Screens read it only through `getDemoProduct()`.
 */

export const PRODUCT_RISK_VERSION = "A-2026-08-11-01";

export const PRODUCT_A: NonNullable<DemoProductResponse["product"]> = {
  id: "PROD_A",
  name: "No-Knock-in Step-down ELS형",
  archetype: "NO_KNOCK_IN_STEP_DOWN",
  productRiskVersion: PRODUCT_RISK_VERSION,
  documentUrl: "/documents/PROD_A/v1.0.pdf",
  documentPageCount: 12,
  syntheticNotice:
    "본 상품은 대회 MVP 검증을 위해 공개 금융상품 설명자료의 구조와 위험요소를 참고하여 구성한 가상의 상품입니다.",
};

/** Shown on S01 alongside the product. */
export const PRODUCT_A_SUMMARY =
  "6개월마다 조건을 평가해 조건이 충족되면 조기상환되는 투자상품입니다. 예금이 아니며, 조건에 따라 원금 손실이 발생할 수 있습니다.";

export const CUSTOMERS: CustomerProfile[] = [
  {
    id: "CUST_A",
    label: "고령·저경험 고객 (대표 데모)",
    ageGroup: "70대",
    investmentExperience: "LOW",
    financialLiteracy: "BASIC",
    explanationLevel: "EASY",
  },
  {
    id: "CUST_B",
    label: "중간 경험 고객",
    ageGroup: "30대",
    investmentExperience: "MEDIUM",
    financialLiteracy: "NORMAL",
    explanationLevel: "NORMAL",
  },
  {
    id: "CUST_C",
    label: "용어는 알지만 구조 이해가 얕은 고객",
    ageGroup: "50대",
    investmentExperience: "MEDIUM",
    financialLiteracy: "NORMAL",
    explanationLevel: "NORMAL",
  },
  {
    id: "CUST_D",
    label: "파생상품 경험이 많은 고객",
    ageGroup: "40대",
    investmentExperience: "HIGH",
    financialLiteracy: "ADVANCED",
    explanationLevel: "NORMAL",
  },
  {
    id: "CUST_E",
    label: "첫 투자 고객",
    ageGroup: "20대",
    investmentExperience: "LOW",
    financialLiteracy: "BASIC",
    explanationLevel: "NORMAL",
  },
  {
    id: "CUST_F",
    label: "거래 경험은 있으나 용어가 어려운 고객",
    ageGroup: "60대",
    investmentExperience: "MEDIUM",
    financialLiteracy: "BASIC",
    explanationLevel: "EASY",
  },
];

/** Free-text profile line for S01. Not part of the API contract. */
export const CUSTOMER_PROFILE_LINE =
  "투자 경험 2년 · ELS 첫 가입 · 원금 보장 상품을 주로 이용";

export const PRODUCT_A_RISKS: ProductRisk[] = [
  {
    riskId: "R01",
    category: "PRINCIPAL_LOSS",
    title: "원금 손실 가능성",
    fact: "조건에 따라 투자원금의 전액 손실이 발생할 수 있으며, 예금자보호 대상이 아니다.",
    coveragePolicy: "GATE_REQUIRED",
    understandingCheck: true,
    sourcePage: 3,
    sourceText:
      "본 상품은 예금이 아니며 원금이 보장되지 않습니다. 기초자산 가격 변동에 따라 투자원금의 전액 손실이 발생할 수 있습니다.",
  },
  {
    riskId: "R02",
    category: "MAXIMUM_LOSS",
    title: "최대 손실 범위",
    fact: "만기평가일 기초자산이 최초기준가격의 65% 미만이면 하락률만큼 원금 손실이 확정된다.",
    coveragePolicy: "GATE_REQUIRED",
    understandingCheck: true,
    sourcePage: 4,
    sourceText:
      "만기평가일 기초자산 가격이 최초기준가격의 65% 미만인 경우, 하락률만큼 손실이 확정되어 상환금액이 결정됩니다.",
  },
  {
    riskId: "R03",
    category: "EARLY_REDEMPTION",
    title: "조기상환 조건",
    fact: "6개월 주기 조기상환 평가일에 기초자산이 해당 차수의 기준가격 이상이면 조기상환된다.",
    coveragePolicy: "GATE_REQUIRED",
    understandingCheck: true,
    sourcePage: 5,
    sourceText:
      "조기상환 평가는 6개월 주기로 실시하며, 평가일 기초자산 가격이 해당 차수의 기준가격 이상인 경우 조기상환됩니다. 1차 평가 기준가격은 최초기준가격의 90%입니다.",
  },
  {
    riskId: "R04",
    category: "MATURITY_REDEMPTION",
    title: "만기상환 조건",
    fact: "만기평가일 종가를 기준으로 상환금액이 결정된다.",
    coveragePolicy: "GATE_REQUIRED",
    understandingCheck: false,
    sourcePage: 5,
    sourceText: "만기상환금액은 만기평가일 기초자산 종가를 기준으로 산정됩니다.",
  },
  {
    riskId: "R05",
    category: "EARLY_TERMINATION",
    title: "중도상환·환매 위험",
    fact: "중도 환매 시 환매 기준가와 환매 수수료에 따라 원금 손실이 확대될 수 있다.",
    coveragePolicy: "WARN_ONLY",
    understandingCheck: false,
    sourcePage: 6,
    sourceText:
      "중도 환매 시 환매 기준가와 환매 수수료가 적용되어 손실이 확대될 수 있습니다.",
  },
  {
    riskId: "R06",
    category: "UNDERLYING_ASSET",
    title: "기초자산·가격변동 영향",
    fact: "기초자산 지수의 변동이 상환 조건 충족 여부를 결정한다.",
    coveragePolicy: "WARN_ONLY",
    understandingCheck: false,
    sourcePage: 7,
    sourceText:
      "기초자산 지수의 변동에 따라 각 평가일의 조건 충족 여부가 결정됩니다.",
  },
  {
    riskId: "R07",
    category: "ISSUER_CREDIT",
    title: "발행인 신용위험",
    fact: "발행인의 파산 시 원금과 수익 지급이 이행되지 않을 수 있다.",
    coveragePolicy: "WARN_ONLY",
    understandingCheck: false,
    sourcePage: 8,
    sourceText:
      "발행인의 신용상태 악화 또는 파산 시 원금과 수익 지급이 이행되지 않을 수 있습니다.",
  },
  {
    riskId: "R08",
    category: "DEPOSIT_PROTECTION",
    title: "예금자보호 여부",
    fact: "예금자보호법에 따른 보호 대상이 아니다.",
    coveragePolicy: "GATE_REQUIRED",
    understandingCheck: false,
    sourcePage: 3,
    sourceText: "본 상품은 예금자보호법에 따른 보호 대상이 아닙니다.",
  },
  {
    riskId: "R09",
    category: "FEE_COST",
    title: "수수료·비용",
    fact: "판매수수료와 자산운용 관련 비용이 상환금액에 반영된다.",
    coveragePolicy: "WARN_ONLY",
    understandingCheck: false,
    sourcePage: 9,
    sourceText: "판매수수료 및 자산운용 관련 비용이 상환금액 산정에 반영됩니다.",
  },
];

export const UNDERSTANDING_CHECK_RISK_IDS = PRODUCT_A_RISKS.filter(
  (r) => r.understandingCheck,
).map((r) => r.riskId);

export function findRisk(riskId: string): ProductRisk | undefined {
  return PRODUCT_A_RISKS.find((r) => r.riskId === riskId);
}

/**
 * Human-verified question and explanation text.
 *
 * Server-side only: the API never exposes these fields, it exposes the
 * *result* of using them (`Question.question`, `ReExplanationResponse`)
 * together with a `GenerationSource` of `FALLBACK`.
 */
export interface VerifiedRiskCopy {
  question: string;
  recheckQuestion: string;
  plainExplanation: string;
}

export const VERIFIED_RISK_COPY: Record<string, VerifiedRiskCopy> = {
  R01: {
    question:
      "이 상품을 만기까지 보유하면 원금은 항상 돌려받을 수 있다고 생각하시나요?",
    recheckQuestion: "만기까지 보유해도 원금이 줄어들 수 있는 경우는 어떤 경우일까요?",
    plainExplanation:
      "이 상품은 예금이 아니라 투자상품입니다. 노 녹인은 만기 전에 손실이 확정되지 않는다는 뜻이며, 원금을 보장한다는 뜻이 아닙니다. 만기까지 보유하셔도 만기평가일에 기초자산이 최초기준가격의 65% 미만이면 떨어진 비율만큼 원금이 줄어듭니다.",
  },
  R02: {
    question:
      "만기에 기초자산이 많이 떨어졌다면, 손실은 최대 어느 정도까지 발생할 수 있을까요?",
    recheckQuestion:
      "기초자산이 절반으로 떨어진 상태로 만기가 되면 원금은 얼마나 줄어들 수 있을까요?",
    plainExplanation:
      "이 상품에는 손실 한도가 따로 정해져 있지 않습니다. 만기평가일 기초자산이 최초기준가격의 65% 미만이면, 하락률만큼 손실이 확정되어 상환금액이 결정됩니다.",
  },
  R03: {
    question:
      "이 상품이 6개월마다 자동으로 상환될 수 있는 조건은 무엇이라고 이해하셨나요?",
    recheckQuestion: "조기상환 평가일에 조건을 충족하지 못하면 그다음에는 어떻게 되나요?",
    plainExplanation:
      "조기상환은 자동으로 정해진 것이 아니라 조건을 충족해야 합니다. 6개월 주기 평가일에 기초자산 가격이 해당 차수의 기준가격 이상이어야 조기상환되며, 1차 평가 기준가격은 최초기준가격의 90%입니다.",
  },
};

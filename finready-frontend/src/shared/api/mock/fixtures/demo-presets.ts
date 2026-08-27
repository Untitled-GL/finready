import type { DemoAnswer, DemoPreset } from "@/shared/types/domain";

/**
 * Mock-adapter copy of the public demo catalog.
 *
 * Production UI never imports this file. It consumes the same fields from
 * GET /products/demo, while the in-memory adapter exposes this measured
 * shape for offline tests and explicit mock mode.
 */
export const DEMO_PRESETS: DemoPreset[] = [
  {
    id: "main",
    label: "대표 데모 - 조기상환 조건 누락 + 원금보장 오해 유발",
    transcript:
      "안녕하세요, 오늘 안내드릴 상품은 FinReady 지수연계증권 ELS 제1호입니다. 코스피200, S&P 500, 유로스톡스50 이렇게 세 개 지수를 기초자산으로 하는 3년 만기 상품이고요, 조건이 충족되면 세전 연 7.2%의 수익을 드립니다. 이 상품은 원금이 보장되는 상품은 아닙니다. 시장 상황에 따라 원금 손실이 발생할 수 있다는 점 말씀드립니다. 다만 이 상품은 낙인이 없는 노낙인 구조라서 투자하시는 동안 지수가 잠깐 많이 떨어지더라도 그것만으로 손실이 확정되지는 않습니다. 만기에는 세 지수 중에 가장 많이 떨어진 지수를 기준으로 상환금액이 결정되고요, 세 지수가 모두 처음 기준가격의 65% 이상이면 3년치 수익을 다 받으실 수 있습니다. 지수가 세 개라서 조건이 조금 더 까다로울 수는 있습니다. 궁금한 점 있으시면 말씀해 주세요.",
    supplementTranscript:
      "추가로 말씀드리면, 이 상품은 6개월마다 조기상환 평가일이 돌아오는데 그때마다 세 지수가 모두 정해진 기준 이상이어야 조기상환이 됩니다. 90퍼센트, 90퍼센트, 85퍼센트 이렇게 단계적으로 기준이 낮아지고요. 조건이 안 맞으면 상환이 안 되고 다음 차수로 넘어가서 투자금이 만기까지 묶일 수 있습니다. 그리고 손실이 날 경우 최대 손실은 원금 전액, 그러니까 마이너스 100퍼센트까지 가능합니다. 손실에 하한선은 따로 없습니다. 한 가지 더 말씀드리면, 이 상품은 예금자보호법에 따라 보호되지 않습니다. 은행 예금과 달리 예금보험공사의 보호 대상이 아니어서, 발행회사가 파산하면 투자금을 돌려받지 못할 수 있습니다.",
  },
  {
    id: "safety",
    label: "잘못된 설명 - 노낙인을 원금보장처럼 설명",
    transcript:
      "안녕하세요. FinReady 지수연계증권 ELS 제1호 안내드리겠습니다. 코스피200, S&P 500, 유로스톡스50 세 지수를 기초자산으로 하는 3년 만기 상품이고 조건 충족 시 세전 연 7.2%입니다. 원금 손실 가능성이 아예 없는 상품은 아닙니다만, 이 상품은 낙인 배리어가 없는 노낙인 구조라서 투자 기간 중에 지수가 잠깐 크게 빠지더라도 그것만으로 손실이 확정되지 않습니다. 그래서 실무적으로 보면 사실상 원금은 지켜진다고 보셔도 크게 무리가 없습니다. 요즘 같은 장에서 세 지수가 동시에 반토막 나는 상황은 상정하기 어렵기도 하고요. 조기상환 조건을 말씀드리면, 6개월마다 평가일이 돌아오고 그 날 세 기초자산 모두의 종가가 해당 차수 배리어 이상이면 자동으로 조기상환됩니다. 배리어는 차수별로 90%, 90%, 85%, 85%, 80%로 점점 낮아집니다. 조건을 충족하지 못하면 상환 없이 다음 차수로 이월되기 때문에 특정 시점의 상환을 보장해 드리지는 못하고, 계속 충족되지 않으면 만기까지 자금이 묶이실 수 있습니다. 만기까지 가시는 경우 만기상환금액은 만기평가일 종가라는 단일 시점을 기준으로 하고, 세 기초자산 중 성과가 가장 낮은 하나를 기준으로 산정됩니다. 나머지 두 기초자산이 상승했더라도 그 손실을 상쇄하지는 않습니다. 그리고 상환 조건은 세 기초자산 모두를 기준으로 판단하므로 하나라도 조건을 충족하지 못하면 해당 차수의 조기상환은 이루어지지 않습니다. 기초자산 수가 많을수록 전부가 동시에 조건을 충족할 확률은 낮아진다는 점도 함께 말씀드립니다. 더 궁금하신 점 있으실까요?",
    supplementTranscript: null,
  },
];

export const DEMO_ANSWERS: DemoAnswer[] = [
  { riskId: "R01", expectedLabel: "UNDERSTOOD", answer: "네, 만기까지 가지고 있어도 지수가 많이 떨어져 있으면 원금 손실이 날 수 있습니다." },
  { riskId: "R01", expectedLabel: "UNDERSTOOD", answer: "원금이 보장되는 상품은 아니라고 하셨죠. 손실이 날 수도 있는 걸로 알고 있습니다." },
  { riskId: "R01", expectedLabel: "MISUNDERSTOOD", answer: "네. 중간에 팔지만 않으면 만기에 원금은 받을 수 있다고 이해했습니다." },
  { riskId: "R01", expectedLabel: "MISUNDERSTOOD", answer: "낙인이 없다고 하셨으니까 그만큼은 안전한 상품인 거죠." },
  { riskId: "R01", expectedLabel: "UNCERTAIN", answer: "글쎄요, 상황에 따라 다르긴 할 것 같은데 아마 대부분은 받지 않을까요." },
  { riskId: "R01", expectedLabel: "UNCERTAIN", answer: "그러면 수익률은 연 7.2%가 확정인 건가요?" },
  { riskId: "R02", expectedLabel: "UNDERSTOOD", answer: "최악의 경우에는 넣은 돈을 다 잃을 수도 있다는 뜻이군요." },
  { riskId: "R02", expectedLabel: "MISUNDERSTOOD", answer: "손해가 나봐야 한 10퍼센트, 20퍼센트 정도겠죠." },
  { riskId: "R02", expectedLabel: "UNCERTAIN", answer: "많이 잃을 수도 있다는 건 알겠는데 정확히 얼마까지인지는 잘 모르겠네요." },
  { riskId: "R03", expectedLabel: "UNDERSTOOD", answer: "6개월마다 평가일에 세 종목이 다 기준선 위에 있어야 상환되고, 하나라도 밑이면 다음으로 넘어가는 거네요." },
  { riskId: "R03", expectedLabel: "MISUNDERSTOOD", answer: "6개월 뒤에는 원금하고 이자를 받을 수 있는 거죠?" },
  { riskId: "R03", expectedLabel: "MISUNDERSTOOD", answer: "제가 6개월 뒤에 신청하면 찾을 수 있는 거 아닌가요?" },
  { riskId: "R03", expectedLabel: "UNCERTAIN", answer: "조건이 맞아야 상환되는 거고, 안 맞으면 그냥 계속 가는 거네요." },
];

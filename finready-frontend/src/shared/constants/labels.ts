import type {
  CoveragePolicy,
  CoverageStatus,
  OverrideCategory,
  UnderstandingAIStatus,
  UnderstandingFinalDisposition,
} from "@/shared/types/domain";

/**
 * Korean surface strings for canonical enums.
 *
 * Transcribed from the frozen design reference. Enum values never appear
 * raw in customer-facing copy; staff-facing tables may show both.
 */

export const COVERAGE_STATUS_LABEL: Record<CoverageStatus, string> = {
  EXPLAINED: "설명 확인",
  INSUFFICIENT: "설명 불충분",
  NOT_FOUND: "설명 미확인",
  CONTRADICTED: "잘못된 설명 가능성",
};

export const COVERAGE_POLICY_LABEL: Record<CoveragePolicy, string> = {
  GATE_REQUIRED: "필수 확인 항목",
  WARN_ONLY: "참고 확인 항목",
  NOT_APPLICABLE: "해당 없음",
};

export const UNDERSTANDING_AI_STATUS_LABEL: Record<
  UnderstandingAIStatus,
  string
> = {
  UNDERSTOOD: "이해 확인",
  MISUNDERSTOOD: "다르게 이해",
  UNCERTAIN: "한 번 더 확인",
};

export const FINAL_DISPOSITION_LABEL: Record<
  UnderstandingFinalDisposition,
  string
> = {
  AUTO_RESOLVED: "자동 확인",
  RESOLVED_BY_STAFF: "직원 확인 완료",
  UNRESOLVED: "미해결",
  SKIPPED_BY_OVERRIDE: "직원 판단으로 제외",
};

export const OVERRIDE_CATEGORY_LABEL: Record<OverrideCategory, string> = {
  AI_MISCLASSIFICATION: "AI 판정 오류",
  EXPLANATION_OUTSIDE_TRANSCRIPT: "기록 외 구두 설명",
  OPERATIONAL_EXCEPTION: "업무 예외",
  OTHER: "기타",
};

/** Ordered for the override modal's category picker. */
export const OVERRIDE_CATEGORY_OPTIONS: ReadonlyArray<{
  value: OverrideCategory;
  label: string;
}> = [
  { value: "AI_MISCLASSIFICATION", label: "AI 판정 오류" },
  { value: "EXPLANATION_OUTSIDE_TRANSCRIPT", label: "기록 외 구두 설명" },
  { value: "OPERATIONAL_EXCEPTION", label: "업무 예외" },
  { value: "OTHER", label: "기타" },
];

/** PRD §23 input limits. Enforced client-side and re-validated server-side. */
export const INPUT_LIMITS = {
  transcript: 8000,
  answer: 2000,
  reason: 500,
} as const;

/** Shown on Landing, S03 and S08. FinReady does not adjudicate compliance. */
export const DISCLAIMER =
  "FinReady는 금융상담을 보조하며 법적 준수 여부를 판정하지 않습니다.";

export const DISCLAIMER_REPORT =
  "FinReady는 금융상담을 보조하며 법적 준수 여부를 판정하지 않습니다. AI 판정과 직원 판단은 각각 별도로 보존됩니다.";

/** Analysis progress stages. Deliberately unnumbered — no fake percentages. */
export const ANALYSIS_STAGES = [
  "분석 준비",
  "상담 내용 확인",
  "위험 항목 비교",
  "결과 정리",
] as const;

/**
 * `AuditEventType`(백엔드 `audit/AuditEventType.java`)의 한글 표기.
 *
 * 계약상 `eventType`은 자유 문자열이라(값이 늘어나도 프론트가 깨지지 않게) 이
 * 맵에 없는 값이 와도 원문 그대로 보여준다 — {@link auditEventLabel} 사용.
 */
export const AUDIT_EVENT_LABEL: Record<string, string> = {
  SESSION_CREATED: "상담 세션 생성",
  REVISION_SAVED: "상담 내용 저장",
  COVERAGE_ANALYZED: "설명 충족도 분석",
  GATE_OVERRIDE_APPLIED: "직원 판단으로 진행 처리",
  QUESTIONS_ISSUED: "고객 확인 질문 발급",
  ANSWER_JUDGED: "고객 답변 판정",
  RE_EXPLANATION_GENERATED: "재설명 생성",
  STAFF_RESOLUTION_RECORDED: "직원 처리 기록",
  SESSION_CLOSED: "상담 종료",
};

export function auditEventLabel(eventType: string | undefined): string {
  if (!eventType) return "처리";
  return AUDIT_EVENT_LABEL[eventType] ?? eventType;
}

export const ACTOR_ROLE_LABEL: Record<string, string> = {
  STAFF: "직원",
  CUSTOMER: "고객",
  SYSTEM: "시스템",
  AI: "AI",
};

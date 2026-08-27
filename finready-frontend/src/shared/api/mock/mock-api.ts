import { FinReadyApiError, type FinReadyApi } from "@/shared/api/contract";
import { evaluateGate, runCoverage } from "@/shared/api/mock/coverage-engine";
import { SAMPLE_ANSWER_STATUS } from "@/shared/api/mock/fixtures/demo-scenario";
import { RISK_ANSWERS, type SampleAnswerKind } from "@/shared/constants/demo";
import {
  DEMO_ANSWERS,
  DEMO_PRESETS,
} from "@/shared/api/mock/fixtures/demo-presets";
import {
  CUSTOMERS,
  PRODUCT_A,
  PRODUCT_A_RISKS,
  PRODUCT_RISK_VERSION,
  UNDERSTANDING_CHECK_RISK_IDS,
  VERIFIED_RISK_COPY,
  findRisk,
} from "@/shared/api/mock/fixtures/product-a";
import { INPUT_LIMITS } from "@/shared/constants/labels";
import type {
  ActorRole,
  AnalyzeCoverageRequest,
  AuditEvent,
  CloseSessionRequest,
  CoverageResponse,
  CoverageResult,
  CreateRevisionRequest,
  CreateSessionRequest,
  DemoProductResponse,
  ErrorCode,
  GateOverrideRequest,
  NextAction,
  OverrideRecord,
  PendingQuestion,
  Question,
  QuestionsResponse,
  ReExplainRequest,
  ReExplanationResponse,
  ReportResponse,
  ResumePoint,
  RevisionResponse,
  RiskUnderstandingState,
  SessionResponse,
  SessionSnapshotResponse,
  SessionStatus,
  StaffResolutionRequest,
  StaffResolutionResponse,
  SubmitAnswerRequest,
  SubmitRecheckRequest,
  UnderstandingAIStatus,
  UnderstandingResponse,
} from "@/shared/types/domain";

/**
 * In-memory FinReady backend.
 *
 * It mirrors the server's responsibilities, not the screens' convenience:
 * revisions are immutable, AI verdicts are never rewritten, and every
 * branch decision (`nextAction`, `gateStatus`, `canProceedToUnderstanding`,
 * `closeEligibility`) is computed here so the client only has to obey it.
 *
 * Responses are shaped exactly like `contracts/openapi.yml` v1.4.2 — no
 * mock-only fields — so switching to the Spring service is a base-URL
 * change. State is mirrored into sessionStorage so a reload can be served
 * by `GET /sessions/{id}`, as PRD §13 expects of the real service.
 */

const STORAGE_KEY = "finready.mock.sessions.v2";
const LATENCY_MS = { fast: 160, analyze: 1500, ai: 700 } as const;
const MOCK_ACTOR = "staff-demo";

type Attempt = NonNullable<RiskUnderstandingState["attempts"]>[number];
type StaffResolution = NonNullable<RiskUnderstandingState["staffResolution"]>;

interface RiskUnderstanding {
  attempts: Attempt[];
  staffResolution: StaffResolution | null;
  workflowStatus: NonNullable<RiskUnderstandingState["workflowStatus"]>;
  finalDisposition: RiskUnderstandingState["finalDisposition"];
  /** Issued and awaiting an answer; cleared once the answer is stored. */
  pendingQuestion: PendingQuestion | null;
}

interface StoredRevision {
  revisionId: number;
  revision: number;
  text: string;
  charCount: number;
  createdAt: string;
}

interface MockSession {
  sessionId: string;
  productId: string;
  customerId: string;
  sessionStatus: SessionStatus;
  createdAt: string;
  closedAt: string | null;
  revisions: StoredRevision[];
  /** Revision the current coverage belongs to; null before first analysis. */
  coverageRevisionId: number | null;
  coverage: CoverageResult[];
  overrides: OverrideRecord[];
  questions: Question[];
  understanding: Record<string, RiskUnderstanding>;
  audit: AuditEvent[];
  acknowledgedWarnings: string[];
  unresolvedReason: string | null;
}

let store: Map<string, MockSession> | null = null;

function loadStore(): Map<string, MockSession> {
  if (store) return store;
  store = new Map();
  if (typeof window !== "undefined") {
    try {
      const raw = window.sessionStorage.getItem(STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw) as Record<string, MockSession>;
        for (const [k, v] of Object.entries(parsed)) store.set(k, v);
      }
    } catch {
      store = new Map();
    }
  }
  return store;
}

function persist(): void {
  if (typeof window === "undefined" || !store) return;
  try {
    window.sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify(Object.fromEntries(store)),
    );
  } catch {
    // Blocked or full storage: the in-memory copy still serves this tab.
  }
}

/** Tests run the same code path without waiting out the simulated latency. */
const SKIP_LATENCY = process.env.NEXT_PUBLIC_MOCK_LATENCY === "0";
const wait = (ms: number) =>
  SKIP_LATENCY ? Promise.resolve() : new Promise<void>((r) => setTimeout(r, ms));
const now = () => new Date().toISOString();

let seq = 0;
const nextSessionId = () => `sess_${Date.now().toString(36)}_${++seq}`;

function fail(
  code: ErrorCode,
  message: string,
  status = 400,
  recoverable = false,
): never {
  throw new FinReadyApiError({ code, message, recoverable }, status);
}

function getOrFail(sessionId: string): MockSession {
  const session = loadStore().get(sessionId);
  if (!session) fail("SESSION_NOT_FOUND", "세션을 찾을 수 없습니다.", 404);
  return session;
}

function assertOpen(session: MockSession): void {
  if (
    session.sessionStatus === "SESSION_CLOSED_BY_STAFF" ||
    session.sessionStatus === "SESSION_CLOSED_WITH_UNRESOLVED"
  ) {
    fail("INVALID_STATE_TRANSITION", "이미 종료된 세션은 변경할 수 없습니다.", 409);
  }
}

function audit(
  session: MockSession,
  eventType: string,
  payloadSummary: string,
  actorRole: ActorRole,
): void {
  session.audit.push({
    eventType,
    actor: actorRole === "STAFF" ? MOCK_ACTOR : actorRole.toLowerCase(),
    actorRole,
    payloadSummary,
    createdAt: now(),
  });
}

function save(session: MockSession): void {
  loadStore().set(session.sessionId, session);
  persist();
}

const latestRevision = (s: MockSession): StoredRevision | undefined =>
  s.revisions[s.revisions.length - 1];

function understandingOf(session: MockSession, riskId: string): RiskUnderstanding {
  session.understanding[riskId] ??= {
    attempts: [],
    staffResolution: null,
    workflowStatus: "NOT_STARTED",
    finalDisposition: null,
    pendingQuestion: null,
  };
  return session.understanding[riskId];
}

/**
 * Every understandingCheck risk, always all three.
 *
 * Kept separate from the question list on purpose: a risk the staff member
 * excluded still has to appear in the report as SKIPPED_BY_OVERRIDE and
 * still counts as unresolved. Reusing one array for "ask the customer" and
 * "account for in the report" is what made an excluded risk vanish.
 */
function reportableRiskIds(): string[] {
  return [...UNDERSTANDING_CHECK_RISK_IDS];
}

/** Risks the customer will actually be asked about. */
function questionEligibleRiskIds(session: MockSession): string[] {
  const skipped = new Set(
    session.overrides
      .filter((o) => o.staffExplanationConfirmed === false)
      .map((o) => o.riskId as string),
  );
  return UNDERSTANDING_CHECK_RISK_IDS.filter((id) => !skipped.has(id));
}

/** Risks still awaiting a disposition. Skipped ones are already COMPLETE. */
function pendingTargets(session: MockSession): string[] {
  return reportableRiskIds().filter(
    (id) => understandingOf(session, id).workflowStatus !== "COMPLETE",
  );
}

function resolveNextAction(session: MockSession, riskId: string): NextAction {
  const remaining = pendingTargets(session).filter((id) => id !== riskId);
  return remaining.length > 0 ? "NEXT_RISK" : "GO_TO_REPORT";
}

/** Single place the session status transition table lives (TRD §5.1). */
function syncStatus(session: MockSession): void {
  if (
    session.sessionStatus === "SESSION_CLOSED_BY_STAFF" ||
    session.sessionStatus === "SESSION_CLOSED_WITH_UNRESOLVED"
  ) {
    return;
  }

  if (session.coverageRevisionId === null) {
    session.sessionStatus = "DRAFT";
    return;
  }

  const gate = evaluateGate(session.coverage, true);
  if (gate.gateStatus === "GATE_BLOCKED") {
    session.sessionStatus = "GATE_BLOCKED";
    return;
  }

  // Progress is measured against the risks the customer can be asked
  // about. An override-excluded risk is already COMPLETE and must not, by
  // itself, make the session look like the customer step has begun.
  const eligible = questionEligibleRiskIds(session);
  const started = eligible.some(
    (id) => understandingOf(session, id).workflowStatus !== "NOT_STARTED",
  );
  if (!started && eligible.length > 0) {
    session.sessionStatus = "COVERAGE_ANALYZED";
    return;
  }

  session.sessionStatus =
    pendingTargets(session).length > 0
      ? "UNDERSTANDING_IN_PROGRESS"
      : "AWAITING_STAFF_REVIEW";
}

function resumePointOf(session: MockSession): ResumePoint {
  switch (session.sessionStatus) {
    case "DRAFT":
      return session.revisions.length > 0 ? "S02" : "S01";
    case "COVERAGE_ANALYZED":
    case "GATE_BLOCKED":
      return "S03";
    case "UNDERSTANDING_IN_PROGRESS":
      // A risk that ran out of attempts is the staff member's to settle, so
      // the session resumes on their screen rather than back at the
      // customer's question.
      return reportableRiskIds().some(
        (id) => understandingOf(session, id).workflowStatus === "MANUAL_REVIEW_REQUIRED",
      )
        ? "S07"
        : "S04";
    default:
      return "S08";
  }
}

/** Re-runs coverage for the current revision and refreshes derived state. */
function analyse(session: MockSession): void {
  const revision = latestRevision(session);
  if (!revision) return;

  session.coverage = runCoverage(revision.text, PRODUCT_A_RISKS, session.overrides);
  session.coverageRevisionId = revision.revisionId;

  // A risk excluded by an unconfirmed override is closed out immediately —
  // it must never be put to the customer as a question.
  for (const override of session.overrides) {
    if (override.staffExplanationConfirmed !== false) continue;
    const riskId = override.riskId as string;
    if (!findRisk(riskId)?.understandingCheck) continue;
    const state = understandingOf(session, riskId);
    if (state.attempts.length === 0) {
      state.workflowStatus = "COMPLETE";
      state.finalDisposition = "SKIPPED_BY_OVERRIDE";
    }
  }

  syncStatus(session);
}

/* ── response serialisers ────────────────────────────────────────── */

function toSessionResponse(session: MockSession): SessionResponse {
  return {
    sessionId: session.sessionId,
    productId: session.productId,
    customerId: session.customerId,
    productRiskVersion: PRODUCT_RISK_VERSION,
    sessionStatus: session.sessionStatus,
    createdAt: session.createdAt,
    closedAt: session.closedAt,
  };
}

function toCoverageResponse(session: MockSession): CoverageResponse {
  const gate = evaluateGate(session.coverage, session.coverageRevisionId !== null);
  return {
    sessionId: session.sessionId,
    revisionId: session.coverageRevisionId ?? 0,
    sessionStatus: session.sessionStatus,
    gateStatus: gate.gateStatus,
    canProceedToUnderstanding: gate.canProceedToUnderstanding,
    blockingRiskIds: gate.blockingRiskIds,
    warningRiskIds: gate.warningRiskIds,
    risks: session.coverage,
    analysis: {
      classifierLatencyMs: 880,
      verifierLatencyMs: 410,
      promptVersion: "coverage-mock-v1",
      ambiguityRetryCount: session.coverage.filter(
        (r) => r.evidence?.provenanceFailureReason === "AMBIGUOUS",
      ).length,
    },
  };
}

function toRevisionResponse(revision: StoredRevision): RevisionResponse {
  return { ...revision };
}

function toUnderstandingState(
  session: MockSession,
  riskId: string,
): RiskUnderstandingState {
  const state = understandingOf(session, riskId);
  return {
    riskId,
    riskTitle: findRisk(riskId)?.title,
    attempts: state.attempts,
    staffResolution: state.staffResolution,
    workflowStatus: state.workflowStatus,
    finalDisposition: state.finalDisposition,
    // Issued-but-unanswered question. This is what lets a reload restore the
    // exact wording instead of the client inventing a replacement.
    pendingQuestion: state.pendingQuestion,
  };
}

/**
 * The flow value for a session mid-understanding.
 *
 * Null during coverage and after close, where `resumePoint` is the answer
 * instead — the contract says the two are used at different times, not
 * interchangeably.
 */
function currentNextAction(session: MockSession): NextAction | null {
  if (session.coverageRevisionId === null) return null;
  if (
    session.sessionStatus === "SESSION_CLOSED_BY_STAFF" ||
    session.sessionStatus === "SESSION_CLOSED_WITH_UNRESOLVED"
  ) {
    return null;
  }

  const manualReview = reportableRiskIds().find(
    (id) => understandingOf(session, id).workflowStatus === "MANUAL_REVIEW_REQUIRED",
  );
  if (manualReview) return "STAFF_RESOLUTION_REQUIRED";

  const open = questionEligibleRiskIds(session).filter(
    (id) => understandingOf(session, id).workflowStatus !== "COMPLETE",
  );
  if (open.length === 0) {
    return pendingTargets(session).length === 0 ? "GO_TO_REPORT" : null;
  }

  // A risk with an unanswered attempt-2 question is mid-recheck.
  const state = understandingOf(session, open[0]);
  return state.pendingQuestion?.attempt === 2 ? "RECHECK" : "NEXT_RISK";
}

/* ── answer classification ───────────────────────────────────────── */

/**
 * Stand-in for the LLM answer classifier.
 *
 * Deliberately conservative: a demo sample answer maps to its designed
 * status, an answer carrying a known misunderstanding marker is called
 * MISUNDERSTOOD, and anything else is UNCERTAIN rather than optimistically
 * passed (PRD §20, Safe Failure).
 */
const MISUNDERSTANDING_MARKERS: Record<string, string[]> = {
  R01: ["원금은 보장", "원금이 보장", "보장된다", "보장되는", "보장됩니"],
  R02: ["한도", "10%", "정도까지만", "상한"],
  R03: ["무조건", "자동으로 상환", "바로 손실"],
};

function classifyAnswer(
  riskId: string,
  answer: string,
): { aiStatus: UnderstandingAIStatus; reason: string } {
  const fixture = RISK_ANSWERS[riskId];
  const trimmed = answer.trim();
  const serverSample = DEMO_ANSWERS.find(
    (sample) => sample.riskId === riskId && sample.answer === trimmed,
  );

  if (serverSample) {
    return {
      aiStatus: serverSample.expectedLabel,
      reason: reasonFor(riskId, serverSample.expectedLabel),
    };
  }

  if (fixture) {
    for (const bucket of ["initial", "recheck"] as const) {
      for (const [kind, text] of Object.entries(fixture[bucket]) as Array<
        [SampleAnswerKind, string]
      >) {
        if (text === trimmed) {
          const aiStatus = SAMPLE_ANSWER_STATUS[kind];
          return { aiStatus, reason: reasonFor(riskId, aiStatus) };
        }
      }
    }
  }

  const markers = MISUNDERSTANDING_MARKERS[riskId] ?? [];
  if (markers.some((m) => trimmed.includes(m))) {
    return { aiStatus: "MISUNDERSTOOD", reason: reasonFor(riskId, "MISUNDERSTOOD") };
  }
  return { aiStatus: "UNCERTAIN", reason: reasonFor(riskId, "UNCERTAIN") };
}

function reasonFor(riskId: string, aiStatus: UnderstandingAIStatus): string {
  if (aiStatus === "UNDERSTOOD") return "검수된 위험 사실과 의미상 일치";
  if (aiStatus === "MISUNDERSTOOD") {
    return (
      RISK_ANSWERS[riskId]?.misunderstandingSummary ??
      "검수된 위험 사실과 다르게 이해"
    );
  }
  return "답변만으로 이해 여부를 확정할 수 없음";
}

/** Shared by `/understanding` (attempt 1) and `/recheck` (attempt 2). */
function recordAnswer(
  session: MockSession,
  riskId: string,
  answer: string,
  answerSource: NonNullable<SubmitAnswerRequest>["answerSource"],
  expectedAttempt: 1 | 2,
): UnderstandingResponse {
  assertOpen(session);

  const risk = findRisk(riskId);
  if (!risk) fail("RISK_NOT_FOUND", "위험 항목을 찾을 수 없습니다.", 404);

  const trimmed = answer.trim();
  if (!trimmed) fail("INVALID_REQUEST", "답변을 입력해주세요.");
  if (trimmed.length > INPUT_LIMITS.answer) {
    fail(
      "INVALID_REQUEST",
      `답변은 ${INPUT_LIMITS.answer.toLocaleString()}자를 넘을 수 없습니다.`,
    );
  }

  const state = understandingOf(session, riskId);
  if (state.workflowStatus === "COMPLETE") {
    fail("RISK_ALREADY_FINALIZED", "이미 종결된 위험 항목입니다.", 409);
  }

  const attempt = state.attempts.length + 1;
  if (attempt > 2) {
    fail("ATTEMPT_LIMIT_EXCEEDED", "이 위험 항목의 확인 횟수를 초과했습니다.", 409);
  }
  if (attempt !== expectedAttempt) {
    fail(
      "INVALID_STATE_TRANSITION",
      attempt === 1
        ? "첫 번째 답변은 understanding으로 제출해야 합니다."
        : "재확인 답변은 recheck으로 제출해야 합니다.",
      409,
    );
  }

  const copy = VERIFIED_RISK_COPY[riskId];
  // Prefer the wording actually issued, so an answer is always graded against
  // the question the customer saw.
  const question =
    state.pendingQuestion?.attempt === attempt
      ? state.pendingQuestion.question
      : attempt === 1
        ? copy?.question
        : copy?.recheckQuestion;
  const { aiStatus, reason } = classifyAnswer(riskId, trimmed);

  state.attempts.push({
    attempt,
    question,
    questionSource: "FALLBACK",
    answer: trimmed,
    answerSource,
    aiStatus,
    reason,
  });
  // Answered, so nothing is outstanding for this risk until we issue a
  // follow-up below.
  state.pendingQuestion = null;

  let nextAction: NextAction;
  if (aiStatus === "UNDERSTOOD") {
    state.workflowStatus = "COMPLETE";
    state.finalDisposition = "AUTO_RESOLVED";
    syncStatus(session);
    nextAction = resolveNextAction(session, riskId);
  } else if (attempt >= 2) {
    state.workflowStatus = "MANUAL_REVIEW_REQUIRED";
    state.finalDisposition = null;
    nextAction = "STAFF_RESOLUTION_REQUIRED";
  } else {
    state.workflowStatus = "IN_PROGRESS";
    state.finalDisposition = null;
    // UNCERTAIN goes straight to a follow-up question; only MISUNDERSTOOD
    // earns a grounded re-explanation first (PRD §7.5).
    nextAction = aiStatus === "MISUNDERSTOOD" ? "REEXPLAIN" : "RECHECK";
  }

  syncStatus(session);
  audit(session, "UNDERSTANDING_CLASSIFIED", `${riskId} attempt ${attempt} · ${aiStatus}`, "AI");
  if (nextAction === "STAFF_RESOLUTION_REQUIRED") {
    audit(session, "MANUAL_REVIEW_REQUIRED", `${riskId} 직원 확인 필요`, "SYSTEM");
  }
  save(session);

  // UNCERTAIN never passes through /reexplain, so this response is the only
  // place a follow-up question can come from. Issuing it here (and persisting
  // it as attempt 2) is what keeps the client from ever writing its own.
  let recheckQuestion: string | null = null;
  if (nextAction === "RECHECK") {
    recheckQuestion = copy?.recheckQuestion ?? "";
    state.pendingQuestion = {
      attempt: attempt + 1,
      question: recheckQuestion,
      source: "FALLBACK",
    };
  }

  syncStatus(session);
  save(session);

  const targets = questionEligibleRiskIds(session);
  return {
    riskId,
    question,
    answer: trimmed,
    answerSource,
    attempt,
    remainingAttempts: Math.max(0, 2 - attempt),
    aiStatus,
    reason,
    workflowStatus: state.workflowStatus,
    finalDisposition: state.finalDisposition,
    nextAction,
    recheckQuestion,
    recheckQuestionSource: recheckQuestion ? "FALLBACK" : null,
    progress: {
      currentRiskIndex: targets.indexOf(riskId) + 1,
      totalRiskCount: targets.length,
    },
  };
}

/* ── the adapter ─────────────────────────────────────────────────── */

export class MockFinReadyApi implements FinReadyApi {
  async getDemoProduct(): Promise<DemoProductResponse> {
    await wait(LATENCY_MS.fast);
    return {
      product: PRODUCT_A,
      risks: PRODUCT_A_RISKS,
      understandingCheckRiskIds: UNDERSTANDING_CHECK_RISK_IDS,
      customers: CUSTOMERS,
      demoPresets: DEMO_PRESETS,
      demoAnswers: DEMO_ANSWERS,
    };
  }

  async createSession(req: CreateSessionRequest): Promise<SessionResponse> {
    await wait(LATENCY_MS.fast);
    const session: MockSession = {
      sessionId: nextSessionId(),
      productId: req.productId,
      customerId: req.customerId,
      sessionStatus: "DRAFT",
      createdAt: now(),
      closedAt: null,
      revisions: [],
      coverageRevisionId: null,
      coverage: [],
      overrides: [],
      questions: [],
      understanding: {},
      audit: [],
      acknowledgedWarnings: [],
      unresolvedReason: null,
    };
    audit(session, "SESSION_CREATED", `${PRODUCT_A.name} · 합성 고객 프리셋으로 세션 생성`, "SYSTEM");
    save(session);
    return toSessionResponse(session);
  }

  async getSession(sessionId: string): Promise<SessionSnapshotResponse> {
    await wait(LATENCY_MS.fast);
    const session = getOrFail(sessionId);
    const revision = latestRevision(session);
    return {
      ...toSessionResponse(session),
      resumePoint: resumePointOf(session),
      nextAction: currentNextAction(session),
      currentRevision: revision ? toRevisionResponse(revision) : undefined,
      coverage: session.coverageRevisionId !== null ? toCoverageResponse(session) : null,
      understanding: reportableRiskIds().map((id) =>
        toUnderstandingState(session, id),
      ),
    };
  }

  async createRevision(
    sessionId: string,
    req: CreateRevisionRequest,
  ): Promise<RevisionResponse> {
    await wait(LATENCY_MS.fast);
    const session = getOrFail(sessionId);
    assertOpen(session);

    const text = req.text.trim();
    if (!text) fail("TRANSCRIPT_EMPTY", "상담 내용을 입력해주세요.");
    if (text.length > INPUT_LIMITS.transcript) {
      fail(
        "TRANSCRIPT_TOO_LONG",
        `상담 내용은 ${INPUT_LIMITS.transcript.toLocaleString()}자를 넘을 수 없습니다.`,
      );
    }

    // Identical text creates no new revision (TRD §5.2).
    const current = latestRevision(session);
    if (current && current.text === text) return toRevisionResponse(current);

    const revisionNo = session.revisions.length + 1;
    const revision: StoredRevision = {
      revisionId: revisionNo,
      revision: revisionNo,
      text,
      charCount: text.length,
      createdAt: now(),
    };
    session.revisions.push(revision);
    audit(
      session,
      "REVISION_CREATED",
      `revision ${revisionNo} 생성 · ${text.length.toLocaleString()}자`,
      "STAFF",
    );
    syncStatus(session);
    save(session);
    return toRevisionResponse(revision);
  }

  async analyzeCoverage(
    sessionId: string,
    req?: AnalyzeCoverageRequest,
  ): Promise<CoverageResponse> {
    const session = getOrFail(sessionId);
    assertOpen(session);

    const revision = latestRevision(session);
    if (!revision) {
      fail("INVALID_STATE_TRANSITION", "분석할 상담 내용이 없습니다.", 409);
    }

    // Idempotent: a completed analysis for the same revision is reused.
    const targetId = req?.revisionId ?? revision.revisionId;
    if (session.coverageRevisionId === targetId) {
      await wait(LATENCY_MS.fast);
      return toCoverageResponse(session);
    }

    await wait(LATENCY_MS.analyze);
    analyse(session);

    const gate = evaluateGate(session.coverage, true);
    audit(
      session,
      "COVERAGE_ANALYZED",
      `${session.coverage.length}개 위험 분석 완료 · ${
        gate.blockingRiskIds.length ? `차단 ${gate.blockingRiskIds.join(", ")}` : "차단 없음"
      }`,
      "SYSTEM",
    );
    if (gate.blockingRiskIds.length) {
      audit(session, "GATE_BLOCKED", `GATE_BLOCKED · ${gate.blockingRiskIds.join(", ")}`, "SYSTEM");
    }
    save(session);
    return toCoverageResponse(session);
  }

  async overrideGate(
    sessionId: string,
    req: GateOverrideRequest,
  ): Promise<CoverageResponse> {
    await wait(LATENCY_MS.fast);
    const session = getOrFail(sessionId);
    assertOpen(session);

    const reason = req.reason.trim();
    if (reason.length < 5) fail("OVERRIDE_REASON_REQUIRED", "사유를 5자 이상 입력해주세요.");
    if (reason.length > INPUT_LIMITS.reason) {
      fail("INVALID_REQUEST", `사유는 ${INPUT_LIMITS.reason}자를 넘을 수 없습니다.`);
    }
    if (!req.riskIds?.length) fail("INVALID_REQUEST", "대상 항목이 없습니다.");

    const touchesUnderstanding = req.riskIds.some(
      (id) => findRisk(id)?.understandingCheck,
    );
    if (touchesUnderstanding && req.staffExplanationConfirmed === undefined) {
      fail(
        "STAFF_EXPLANATION_CONFIRMATION_REQUIRED",
        "이해확인 대상 항목은 실제 설명 여부를 확인해야 합니다.",
      );
    }

    const createdAt = now();
    for (const riskId of req.riskIds) {
      session.overrides.push({
        riskId,
        category: req.category,
        reason,
        staffExplanationConfirmed: req.staffExplanationConfirmed ?? null,
        actor: req.actor,
        createdAt,
      });
    }

    // The AI's coverage verdict is untouched; only the gate moves.
    analyse(session);
    audit(
      session,
      "GATE_OVERRIDE_CREATED",
      `${req.riskIds.join(", ")} · ${req.category} · ${reason} (AI 판정 보존)`,
      "STAFF",
    );
    save(session);
    return toCoverageResponse(session);
  }

  async getOrCreateQuestions(sessionId: string): Promise<QuestionsResponse> {
    await wait(LATENCY_MS.fast);
    const session = getOrFail(sessionId);

    const gate = evaluateGate(session.coverage, session.coverageRevisionId !== null);
    if (!gate.canProceedToUnderstanding) {
      fail("GATE_NOT_OPEN", "설명 충족도 확인이 끝나지 않았습니다.", 409);
    }

    const targets = questionEligibleRiskIds(session);

    // Idempotent: regenerating would change the wording mid-demo.
    if (session.questions.length === 0) {
      session.questions = targets.map((riskId, index) => ({
        riskId,
        riskTitle: findRisk(riskId)?.title ?? riskId,
        question: VERIFIED_RISK_COPY[riskId]?.question ?? "",
        source: "FALLBACK",
        // This endpoint only ever hands out the opening question.
        attempt: 1,
        orderIndex: index + 1,
      }));
      for (const riskId of targets) {
        const state = understandingOf(session, riskId);
        if (state.workflowStatus === "NOT_STARTED") state.workflowStatus = "IN_PROGRESS";
        if (state.attempts.length === 0 && !state.pendingQuestion) {
          state.pendingQuestion = {
            attempt: 1,
            question: VERIFIED_RISK_COPY[riskId]?.question ?? "",
            source: "FALLBACK",
          };
        }
      }
      syncStatus(session);
      audit(session, "QUESTION_SHOWN", `이해확인 질문 ${targets.length}건 생성`, "SYSTEM");
      save(session);
    }

    return {
      sessionId: session.sessionId,
      totalRiskCount: targets.length,
      questions: session.questions,
    };
  }

  async submitAnswer(
    sessionId: string,
    req: SubmitAnswerRequest,
  ): Promise<UnderstandingResponse> {
    await wait(LATENCY_MS.ai);
    const session = getOrFail(sessionId);
    return recordAnswer(session, req.riskId, req.answer, req.answerSource, 1);
  }

  async submitRecheck(
    sessionId: string,
    req: SubmitRecheckRequest,
  ): Promise<UnderstandingResponse> {
    await wait(LATENCY_MS.ai);
    const session = getOrFail(sessionId);
    return recordAnswer(session, req.riskId, req.answer, req.answerSource, 2);
  }

  async reexplain(
    sessionId: string,
    req: ReExplainRequest,
  ): Promise<ReExplanationResponse> {
    await wait(LATENCY_MS.ai);
    const session = getOrFail(sessionId);
    assertOpen(session);

    const risk = findRisk(req.riskId);
    if (!risk) fail("RISK_NOT_FOUND", "위험 항목을 찾을 수 없습니다.", 404);

    const state = understandingOf(session, req.riskId);
    const last = state.attempts[state.attempts.length - 1];
    if (last?.aiStatus !== "MISUNDERSTOOD") {
      fail("INVALID_STATE_TRANSITION", "재설명 대상 상태가 아닙니다.", 409);
    }

    const copy = VERIFIED_RISK_COPY[req.riskId];
    // The follow-up ships with the re-explanation and is persisted as attempt
    // 2, so a reload mid-recheck restores this exact wording.
    state.pendingQuestion = {
      attempt: state.attempts.length + 1,
      question: copy?.recheckQuestion ?? "",
      source: "FALLBACK",
    };
    audit(
      session,
      "REEXPLANATION_SHOWN",
      `${req.riskId} 근거 기반 재설명 (상품설명서 ${risk.sourcePage}페이지)`,
      "SYSTEM",
    );
    save(session);

    return {
      riskId: req.riskId,
      customerAnswer: last.answer,
      riskFact: risk.fact ?? "",
      sourcePage: risk.sourcePage,
      sourceText: risk.sourceText ?? "",
      documentUrl: PRODUCT_A.documentUrl,
      // Grounded in the verified schema only.
      explanation: copy?.plainExplanation ?? "",
      source: "FALLBACK",
      guardrail: { retried: false, violations: [] },
      recheckQuestion: copy?.recheckQuestion ?? "",
      recheckQuestionSource: "FALLBACK",
      nextAction: "RECHECK",
    };
  }

  async resolveByStaff(
    sessionId: string,
    riskId: string,
    req: StaffResolutionRequest,
  ): Promise<StaffResolutionResponse> {
    await wait(LATENCY_MS.fast);
    const session = getOrFail(sessionId);
    assertOpen(session);

    if (!findRisk(riskId)) fail("RISK_NOT_FOUND", "위험 항목을 찾을 수 없습니다.", 404);

    const reason = req.reason.trim();
    if (reason.length < 5) fail("INVALID_REQUEST", "처리 사유를 5자 이상 입력해주세요.");
    if (reason.length > INPUT_LIMITS.reason) {
      fail("INVALID_REQUEST", `사유는 ${INPUT_LIMITS.reason}자를 넘을 수 없습니다.`);
    }

    // Stored as its own record; the AI attempts above stay untouched.
    const state = understandingOf(session, riskId);
    state.staffResolution = {
      disposition: req.disposition,
      reason,
      actor: req.actor,
      createdAt: now(),
    };
    state.workflowStatus = "COMPLETE";
    state.finalDisposition = req.disposition;

    syncStatus(session);
    audit(
      session,
      "STAFF_RESOLUTION_CREATED",
      `${riskId} · ${
        req.disposition === "UNRESOLVED" ? "미해결로 종결" : "직원 확인으로 해결"
      } · ${reason} (AI 판정 보존)`,
      "STAFF",
    );
    save(session);

    const targets = questionEligibleRiskIds(session);
    return {
      riskState: toUnderstandingState(session, riskId),
      // The server decides where the staff member goes next; the client does
      // not count remaining risks to work it out.
      nextAction: resolveNextAction(session, riskId),
      progress: {
        currentRiskIndex: Math.max(targets.indexOf(riskId) + 1, 1),
        totalRiskCount: targets.length,
      },
      sessionStatus: session.sessionStatus,
    };
  }

  async getReport(sessionId: string): Promise<ReportResponse> {
    await wait(LATENCY_MS.fast);
    const session = getOrFail(sessionId);
    const gate = evaluateGate(session.coverage, session.coverageRevisionId !== null);

    const unresolvedRiskIds = reportableRiskIds().filter((id) => {
      const d = understandingOf(session, id).finalDisposition;
      return d === "UNRESOLVED" || d === "SKIPPED_BY_OVERRIDE";
    });

    const pendingWarnings = gate.warningRiskIds.filter(
      (id) => !session.acknowledgedWarnings.includes(id),
    );
    const closed =
      session.sessionStatus === "SESSION_CLOSED_BY_STAFF" ||
      session.sessionStatus === "SESSION_CLOSED_WITH_UNRESOLVED";

    return {
      sessionId: session.sessionId,
      sessionStatus: session.sessionStatus,
      product: {
        id: session.productId,
        name: PRODUCT_A.name,
        productRiskVersion: PRODUCT_RISK_VERSION,
      },
      coverage: {
        finalRevisionId: session.coverageRevisionId ?? 0,
        gateStatus: gate.gateStatus,
        results: session.coverage,
      },
      understanding: reportableRiskIds().map((id) =>
        toUnderstandingState(session, id),
      ),
      overrides: session.overrides,
      revisions: session.revisions.map(toRevisionResponse),
      auditEvents: session.audit,
      unresolvedRiskIds,
      closeEligibility: {
        canClose: !closed && pendingTargets(session).length === 0,
        requiresUnresolvedReason: unresolvedRiskIds.length > 0,
        requiresWarningAcknowledgement: pendingWarnings,
        expectedCloseStatus:
          unresolvedRiskIds.length > 0
            ? "SESSION_CLOSED_WITH_UNRESOLVED"
            : "SESSION_CLOSED_BY_STAFF",
      },
      disclaimer:
        "FinReady는 금융상담을 보조하며 법적 준수 여부를 판정하지 않습니다. AI 판정과 직원 판단은 각각 별도로 보존됩니다.",
    };
  }

  async closeSession(
    sessionId: string,
    req: CloseSessionRequest,
  ): Promise<SessionResponse> {
    await wait(LATENCY_MS.fast);
    const session = getOrFail(sessionId);

    // Idempotent: closing an already-closed session repeats the response.
    if (
      session.sessionStatus === "SESSION_CLOSED_BY_STAFF" ||
      session.sessionStatus === "SESSION_CLOSED_WITH_UNRESOLVED"
    ) {
      return toSessionResponse(session);
    }

    // TRD §5.1: the only transition into a closed state starts at
    // AWAITING_STAFF_REVIEW. Closing mid-flow would strand the customer's
    // outstanding risks with no disposition at all.
    if (session.sessionStatus !== "AWAITING_STAFF_REVIEW") {
      fail(
        "INVALID_STATE_TRANSITION",
        "고객 이해 확인이 끝나야 상담을 종료할 수 있습니다.",
        409,
      );
    }

    const gate = evaluateGate(session.coverage, session.coverageRevisionId !== null);
    const acknowledged = req.acknowledgedWarnings ?? [];
    const missing = gate.warningRiskIds.filter((id) => !acknowledged.includes(id));
    if (missing.length > 0) {
      fail("WARNING_ACKNOWLEDGEMENT_REQUIRED", "참고 확인 항목을 확인해주세요.");
    }

    const unresolved = reportableRiskIds().filter((id) => {
      const d = understandingOf(session, id).finalDisposition;
      return d === "UNRESOLVED" || d === "SKIPPED_BY_OVERRIDE";
    });
    const unresolvedReason = req.unresolvedReason?.trim() ?? "";
    if (unresolved.length > 0 && !unresolvedReason) {
      fail("UNRESOLVED_REASON_REQUIRED", "미해결 항목이 있어 종료 사유가 필요합니다.");
    }

    session.acknowledgedWarnings = acknowledged;
    session.unresolvedReason = unresolvedReason || null;
    session.closedAt = now();
    session.sessionStatus =
      unresolved.length > 0
        ? "SESSION_CLOSED_WITH_UNRESOLVED"
        : "SESSION_CLOSED_BY_STAFF";

    if (gate.warningRiskIds.length > 0) {
      audit(
        session,
        "WARN_ACKNOWLEDGED",
        `참고 확인 항목 ${gate.warningRiskIds.length}건 확인 · AI 판정 변경 없음`,
        "STAFF",
      );
    }
    audit(
      session,
      "SESSION_CLOSED",
      unresolved.length > 0
        ? `미해결 ${unresolved.length}건 · ${unresolvedReason}`
        : "미해결 없음",
      "STAFF",
    );
    save(session);
    return toSessionResponse(session);
  }
}

/** Test/demo helper — clears every mock session. */
export function resetMockStore(): void {
  store = new Map();
  if (typeof window !== "undefined") {
    try {
      window.sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      // ignore
    }
  }
}

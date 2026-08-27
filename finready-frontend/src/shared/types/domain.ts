import type { components, operations } from "@/shared/api/generated/openapi";

/**
 * Domain vocabulary for the app, aliased straight off the generated
 * OpenAPI types.
 *
 * There are no hand-written shapes here on purpose: `../docs/openapi.yml`
 * (v1.4.4) is the contract, `pnpm gen:api` regenerates from it, and this file
 * only gives the generated schemas readable names. If the backend
 * changes a field, typecheck breaks here rather than at runtime.
 *
 * Two invariants the contract encodes and the UI must preserve:
 *  1. `classifierStatus` (AI's original call) and `coverageStatus`
 *     (post-verification) are separate. Never synthesise an
 *     `effectiveStatus` from them.
 *  2. `aiStatus`, `workflowStatus` and `finalDisposition` are three
 *     independent fields. Staff action never rewrites the AI's verdict.
 */

type Schemas = components["schemas"];

/* ── enums ───────────────────────────────────────────────────────── */

export type CoveragePolicy = Schemas["CoveragePolicy"];
export type CoverageStatus = Schemas["CoverageStatus"];
export type SemanticRelation = Schemas["SemanticRelation"];
export type ProvenanceFailureReason = Schemas["ProvenanceFailureReason"];
export type GateStatus = Schemas["GateStatus"];
export type SessionStatus = Schemas["SessionStatus"];
/** AI's first and only judgement of an answer. */
export type UnderstandingAIStatus = Schemas["UnderstandingStatus"];
export type UnderstandingWorkflowStatus = Schemas["UnderstandingWorkflowStatus"];
export type UnderstandingFinalDisposition =
  Schemas["UnderstandingFinalDisposition"];
export type StaffDisposition = Schemas["StaffDisposition"];
export type OverrideCategory = Schemas["OverrideCategory"];
export type AnswerSource = Schemas["AnswerSource"];
export type ActorRole = Schemas["ActorRole"];
export type GenerationSource = Schemas["GenerationSource"];
/** Server-decided routing. The client branches on this and nothing else. */
export type NextAction = Schemas["NextAction"];
export type ResumePoint = Schemas["ResumePoint"];

/* ── entities ────────────────────────────────────────────────────── */

export type DemoProductResponse = Schemas["DemoProductResponse"];
export type DemoPreset = Schemas["DemoPreset"];
export type DemoAnswer = Schemas["DemoAnswer"];
export type ProductRisk = Schemas["ProductRisk"];
export type CustomerProfile = Schemas["CustomerProfile"];
export type SessionResponse = Schemas["SessionResponse"];
export type SessionSnapshotResponse = Schemas["SessionSnapshotResponse"];
export type RevisionResponse = Schemas["RevisionResponse"];
export type CoverageResponse = Schemas["CoverageResponse"];
export type CoverageResult = Schemas["CoverageResult"];
export type Evidence = Schemas["Evidence"];
export type OverrideRecord = Schemas["OverrideRecord"];
export type Question = Schemas["Question"];
export type UnderstandingResponse = Schemas["UnderstandingResponse"];
export type ReExplanationResponse = Schemas["ReExplanationResponse"];
export type RiskUnderstandingState = Schemas["RiskUnderstandingState"];
/** Wraps the risk state with the flow values the report form has no place for. */
export type StaffResolutionResponse = Schemas["StaffResolutionResponse"];
export type ReportResponse = Schemas["ReportResponse"];
export type ApiErrorBody = Schemas["Error"];
/** The contract's closed set of error codes. */
export type ErrorCode = ApiErrorBody["code"];

/** Attempt rows nested inside `RiskUnderstandingState`. */
export type UnderstandingAttempt = NonNullable<
  RiskUnderstandingState["attempts"]
>[number];

/** Staff resolution as it appears on a risk's understanding state. */
export type StaffResolutionRecord = NonNullable<
  RiskUnderstandingState["staffResolution"]
>;

/**
 * A question that was issued but not yet answered.
 *
 * This is what makes a mid-step reload recoverable: the server re-serves the
 * exact wording it already issued, so the client never has to reconstruct a
 * question it did not receive.
 */
export type PendingQuestion = NonNullable<
  RiskUnderstandingState["pendingQuestion"]
>;

export type AuditEvent = NonNullable<ReportResponse["auditEvents"]>[number];

export type CloseEligibility = NonNullable<ReportResponse["closeEligibility"]>;

/* ── request bodies ──────────────────────────────────────────────── */

type JsonBody<T> = T extends { requestBody: { content: { "application/json": infer B } } }
  ? B
  : T extends {
        requestBody?: { content: { "application/json": infer B } };
      }
    ? B
    : never;

export type CreateSessionRequest = JsonBody<operations["createSession"]>;
export type CreateRevisionRequest = JsonBody<operations["createRevision"]>;
export type AnalyzeCoverageRequest = JsonBody<operations["analyzeCoverage"]>;
export type GateOverrideRequest = JsonBody<operations["overrideGate"]>;
export type SubmitAnswerRequest = JsonBody<operations["submitAnswer"]>;
export type ReExplainRequest = JsonBody<operations["reexplain"]>;
export type SubmitRecheckRequest = JsonBody<operations["submitRecheckAnswer"]>;
export type StaffResolutionRequest = JsonBody<operations["resolveByStaff"]>;
export type CloseSessionRequest = JsonBody<operations["closeSession"]>;

/** `POST /questions` takes no body; its response carries the question set. */
export type QuestionsResponse =
  operations["getOrCreateQuestions"]["responses"]["200"]["content"]["application/json"];

/* ── runtime enum values ─────────────────────────────────────────── */

/**
 * Iterable copies for UI that needs to enumerate values. Typed against the
 * generated unions, so dropping or renaming a value fails to compile.
 */
export const OVERRIDE_CATEGORIES = [
  "AI_MISCLASSIFICATION",
  "EXPLANATION_OUTSIDE_TRANSCRIPT",
  "OPERATIONAL_EXCEPTION",
  "OTHER",
] as const satisfies readonly OverrideCategory[];

export const UNDERSTANDING_AI_STATUSES = [
  "UNDERSTOOD",
  "MISUNDERSTOOD",
  "UNCERTAIN",
] as const satisfies readonly UnderstandingAIStatus[];

export const COVERAGE_STATUSES = [
  "EXPLAINED",
  "INSUFFICIENT",
  "NOT_FOUND",
  "CONTRADICTED",
] as const satisfies readonly CoverageStatus[];

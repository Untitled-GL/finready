"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { AnswerForm } from "@/screens/understanding/answer-form";
import { ReExplanationView } from "@/screens/understanding/reexplanation-view";
import { ResultView } from "@/screens/understanding/result-view";
import {
  useQuestions,
  useReexplain,
  useDemoProduct,
  useSession,
  useSubmitAnswer,
  useSubmitRecheck,
} from "@/shared/api/queries";
import { answersForRisk } from "@/shared/lib/demo-catalog";
import type {
  NextAction,
  ReExplanationResponse,
  UnderstandingResponse,
} from "@/shared/types/domain";
import { CustomerShell } from "@/shared/ui/customer-shell";
import { ErrorNote } from "@/shared/ui/error-note";
import { ScreenSkeleton } from "@/shared/ui/screen-skeleton";
import { useScenarioQuery } from "@/shared/ui/staff-shell";

/**
 * S04–S07, the customer's side of the session.
 *
 * One route, because to the customer it is one continuous conversation.
 * The view within it is chosen by the server's `nextAction` — this screen
 * never inspects `aiStatus` and `attempt` to decide where to go next, which
 * is how a second, divergent copy of the branch rules would get built.
 *
 * Reload behaviour (contract v1.4.2).
 * `pendingQuestion` restores the open question — including its attempt — so a
 * reload resumes on the right question and posts to the right endpoint. The
 * transient S05 result and S06 re-explanation are not part of the snapshot, so
 * a reload lands on the question for that risk rather than back on those
 * screens; re-issuing `/reexplain` would be a second grounded generation the
 * customer never asked for, and inventing the content locally is worse.
 */

/**
 * The question view carries no attempt of its own: which attempt is open, and
 * the exact wording, come from the session's `pendingQuestion`. That is what
 * makes a mid-step reload land on attempt 2 instead of restarting at attempt 1
 * and posting to the wrong endpoint.
 */
type View =
  | { kind: "question" }
  | { kind: "result"; result: UnderstandingResponse }
  | { kind: "reexplain"; data: ReExplanationResponse; misunderstanding?: string }
  | { kind: "done" };

/**
 * The result screen describes the answer that was just graded, so it is
 * labelled with the progress the server returned alongside that answer —
 * not with whichever risk the client has since moved on to.
 */
function resultKicker(result: UnderstandingResponse, total: number): string {
  const index = result.progress?.currentRiskIndex;
  const of = result.progress?.totalRiskCount ?? total;
  return index ? `핵심 위험 ${index} / ${of}` : `핵심 위험 ${of} 중`;
}

export function UnderstandingScreen({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  const query = useScenarioQuery();

  const demo = useDemoProduct();
  const questions = useQuestions(sessionId, true);
  const session = useSession(sessionId);
  const submitAnswer = useSubmitAnswer(sessionId);
  const submitRecheck = useSubmitRecheck(sessionId);
  const reexplain = useReexplain(sessionId);

  /**
   * Which risk is on screen. Null means "whatever the server says is still
   * open" — that way re-entering (after a staff resolution, or a reload)
   * resumes at the right risk instead of restarting at the first one.
   */
  const [activeRiskId, setActiveRiskId] = useState<string | null>(null);
  const [answer, setAnswer] = useState("");
  const [view, setView] = useState<View>({ kind: "question" });
  /**
   * True while NEXT_RISK/RECHECK are waiting on a fresh session snapshot
   * before showing the next question. See `follow` for why this wait can't
   * be skipped.
   */
  const [advancing, setAdvancing] = useState(false);
  /**
   * Guards against a double-click firing two mutations for the same
   * (risk, attempt) before `pending` has re-rendered the disabled button.
   * `mutate` itself doesn't dedupe, so without this a fast double-click can
   * send the same attempt twice — the second reaches the server as a
   * duplicate and comes back 409 ATTEMPT_LIMIT_EXCEEDED.
   */
  const submittedKeys = useRef(new Set<string>());

  const allQuestions = questions.data?.questions ?? [];
  // Settled, or waiting on a staff decision — either way the customer has
  // nothing left to answer for that risk.
  const closedToCustomer = new Set(
    (session.data?.understanding ?? [])
      .filter(
        (s) =>
          s.workflowStatus === "COMPLETE" ||
          s.workflowStatus === "MANUAL_REVIEW_REQUIRED",
      )
      .map((s) => s.riskId as string),
  );
  const openQuestions = allQuestions.filter(
    (q) => !closedToCustomer.has(q.riskId as string),
  );
  const customerDone =
    Boolean(questions.data) &&
    Boolean(session.data) &&
    openQuestions.length === 0;

  // Nothing left for the customer to answer — hand the device back. Done in
  // an effect because navigating during render updates the router mid-render.
  useEffect(() => {
    if (customerDone) router.replace(`/session/${sessionId}/return${query}`);
  }, [customerDone, router, sessionId, query]);

  if (demo.isError || questions.isError) {
    return (
      <CustomerShell currentIndex={1} totalCount={3}>
        <div className="mx-auto max-w-[760px] px-[40px] py-[72px]">
          <ErrorNote
            error={demo.error ?? questions.error}
            onRetry={() => {
              void demo.refetch();
              void questions.refetch();
            }}
          />
        </div>
      </CustomerShell>
    );
  }
  if (!demo.data || !questions.data || !session.data) return <ScreenSkeleton />;
  if (customerDone) return <ScreenSkeleton label="직원 화면으로 이동합니다" />;

  const total = questions.data.totalRiskCount ?? allQuestions.length;
  const current =
    openQuestions.find((q) => q.riskId === activeRiskId) ?? openQuestions[0];

  if (!current) return <ScreenSkeleton />;

  const riskId = current.riskId as string;
  const kicker = `핵심 위험 ${current.orderIndex ?? 1} / ${total}`;
  const samples = answersForRisk(demo.data.demoAnswers, riskId);
  const pending =
    submitAnswer.isPending ||
    submitRecheck.isPending ||
    reexplain.isPending ||
    advancing;

  // The server re-serves whatever question it already issued, so this survives
  // a reload and is the only source of the attempt-2 wording. The client never
  // composes a question of its own.
  const riskState = (session.data.understanding ?? []).find(
    (u) => u.riskId === riskId,
  );
  const pendingQuestion = riskState?.pendingQuestion ?? null;
  const attempt = pendingQuestion?.attempt ?? 1;
  const isRecheck = attempt >= 2;
  const questionText = pendingQuestion?.question ?? current.question ?? "";

  /** Advance to whatever the server said comes next. */
  const follow = async (
    nextAction: NextAction,
    result?: UnderstandingResponse,
  ) => {
    switch (nextAction) {
      case "NEXT_RISK":
      case "RECHECK": {
        // Both land back on the question view, and both read state that only
        // exists in the GET /sessions/{id} snapshot: NEXT_RISK needs the risk
        // just answered to show COMPLETE (closedToCustomer), RECHECK needs the
        // attempt-2 pendingQuestion. The mutation's onSuccess only invalidates
        // that query, it doesn't wait for it — so without this await, a slow
        // refetch (Render/Singapore, ~1s) leaves the just-answered attempt 1
        // question on screen, the customer answers again, and the server
        // rejects the repeat with 409 ATTEMPT_LIMIT_EXCEEDED.
        setAdvancing(true);
        await session.refetch();
        setAdvancing(false);
        if (nextAction === "NEXT_RISK") {
          // Clearing the pin, rather than picking a successor here, is the
          // whole point: `current` is already derived from the server's open
          // list, so choosing "the one after current" advanced twice and
          // skipped a risk.
          setActiveRiskId(null);
        }
        setAnswer("");
        setView({ kind: "question" });
        return;
      }
      case "REEXPLAIN":
        reexplain.mutate(
          { riskId },
          {
            onSuccess: (data) =>
              setView({
                kind: "reexplain",
                data,
                misunderstanding: result?.reason,
              }),
          },
        );
        return;
      case "STAFF_RESOLUTION_REQUIRED":
      case "GO_TO_REPORT":
        setView({ kind: "done" });
        router.push(`/session/${sessionId}/return${query}`);
        return;
    }
  };

  const submit = () => {
    if (view.kind !== "question") return;
    // See `submittedKeys` above — blocks a same-attempt resend that beats the
    // pending-disabled button to the click.
    const key = `${riskId}:${attempt}`;
    if (submittedKeys.current.has(key)) return;
    submittedKeys.current.add(key);
    const req = {
      riskId,
      answer: answer.trim(),
      answerSource: "CUSTOMER_DIRECT_DEMO" as const,
    };
    // attempt 1 goes to /understanding, attempt 2 to /recheck. Choosing by the
    // server's attempt (rather than by how this screen was reached) is what
    // keeps a reload from posting attempt 2 to the attempt-1 endpoint.
    const mutation = isRecheck ? submitRecheck : submitAnswer;
    mutation.mutate(req, {
      onSuccess: (result) => setView({ kind: "result", result }),
      // A failed send never reached the server as a recorded attempt, so the
      // key is released — otherwise "다시 시도" from ErrorNote would silently
      // no-op forever.
      onError: () => submittedKeys.current.delete(key),
    });
  };

  const error = submitAnswer.error ?? submitRecheck.error ?? reexplain.error;

  return (
    <CustomerShell
      currentIndex={current.orderIndex ?? 1}
      totalCount={total}
    >
      {error ? (
        <div className="mx-auto max-w-[760px] px-[40px] pt-[40px]">
          <ErrorNote error={error} onRetry={submit} />
        </div>
      ) : null}

      {view.kind === "question" ? (
        <AnswerForm
          question={questionText}
          kicker={kicker}
          note={
            isRecheck
              ? "두 번째 확인입니다. 이번에도 판단이 어려우면 담당 직원이 함께 확인합니다."
              : "답변은 상담 기록과 함께 보관됩니다."
          }
          answer={answer}
          onAnswerChange={setAnswer}
          samples={samples}
          pending={pending}
          onSubmit={submit}
        />
      ) : null}

      {view.kind === "result" ? (
        <ResultView
          aiStatus={view.result.aiStatus}
          reason={view.result.reason}
          answer={view.result.answer ?? answer}
          kicker={resultKicker(view.result, total)}
          nextAction={view.result.nextAction}
          pending={pending}
          onContinue={() => follow(view.result.nextAction, view.result)}
        />
      ) : null}

      {view.kind === "reexplain" ? (
        <ReExplanationView
          data={view.data}
          misunderstanding={view.misunderstanding}
          kicker={kicker}
          pending={pending}
          onContinue={() => follow(view.data.nextAction ?? "RECHECK")}
        />
      ) : null}

      {view.kind === "done" ? <ScreenSkeleton label="직원 화면으로 이동합니다" /> : null}
    </CustomerShell>
  );
}

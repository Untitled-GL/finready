"use client";

import { useRouter } from "next/navigation";
import { api } from "@/shared/api";
import { useMutation } from "@tanstack/react-query";
import { ErrorNote } from "@/shared/ui/error-note";
import { useScenarioQuery } from "@/shared/ui/staff-shell";

/**
 * The staff → customer hand-off.
 *
 * Not a feature of its own: a deliberate pause that makes the change of
 * hands legible before the customer starts answering in their own words.
 * The screen surface changes here too — warm background, no staff chrome —
 * so it is obvious who the next screens are addressing.
 */
export function HandoffScreen({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  const query = useScenarioQuery();

  // Asking the server for the question set is what actually proves the gate
  // is open — it answers 409 GATE_NOT_OPEN otherwise.
  const start = useMutation({
    mutationFn: () => api.getOrCreateQuestions(sessionId),
    onSuccess: () => router.push(`/session/${sessionId}/understanding${query}`),
  });

  return (
    <main className="flex min-h-screen items-center justify-center bg-[var(--color-cust-canvas)] px-[40px]">
      <div className="screen-in w-full max-w-[560px] text-center">
        <div className="mx-auto flex size-[44px] items-center justify-center rounded-[13px] bg-[var(--color-accent)]">
          <span className="text-[20px] font-semibold text-white">→</span>
        </div>

        <h1 className="mt-[32px] text-[32px] leading-[1.35] font-semibold tracking-[-0.022em] text-pretty">
          이제 고객님이 직접 확인합니다
        </h1>
        <p className="mt-[18px] text-[17px] leading-[1.7] text-[var(--color-cust-body)]">
          화면을 고객에게 전달해주세요. 핵심 위험 3건을 고객님이 직접 읽고, 이해한
          내용을 자신의 말로 답변합니다.
        </p>

        {start.isError ? (
          <ErrorNote
            className="mt-[28px] text-left"
            error={start.error}
            onRetry={() => start.mutate()}
          />
        ) : null}

        <button
          type="button"
          onClick={() => start.mutate()}
          disabled={start.isPending}
          className="mt-[36px] rounded-[11px] bg-[var(--color-accent)] px-[30px] py-[15px] text-[16px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
        >
          {start.isPending ? "준비 중…" : "고객 확인 시작"}
        </button>

        <p className="mt-[24px] text-[13px] text-[var(--color-cust-muted-soft)]">
          답변은 정답을 맞히는 것이 아니라, 설명이 충분했는지 확인하기 위한 것입니다.
        </p>
      </div>
    </main>
  );
}

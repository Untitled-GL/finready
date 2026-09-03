"use client";

import { useRouter } from "next/navigation";
import { useSession } from "@/shared/api/queries";
import { useScenarioQuery } from "@/shared/ui/staff-shell";

/**
 * The customer → staff hand-back.
 *
 * The mirror of the outbound hand-off, and a state rather than a canonical
 * screen: the customer's part is over and the device goes back before
 * anything is decided. Nothing is concluded here — the staff member reviews
 * the result on S08 and closes the session there.
 */
export function ReturnScreen({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  const query = useScenarioQuery();
  const querySuffix = useScenarioQuery("&");
  const session = useSession(sessionId);

  // If the AI could not settle a risk, the staff member's next stop is that
  // risk, not the report. The server's workflowStatus says which.
  const pendingReview = (session.data?.understanding ?? []).find(
    (s) => s.workflowStatus === "MANUAL_REVIEW_REQUIRED",
  );
  const destination = pendingReview
    ? `/session/${sessionId}/review?risk=${pendingReview.riskId}${querySuffix}`
    : `/session/${sessionId}/report${query}`;

  return (
    <main className="flex min-h-screen items-center justify-center bg-[var(--color-cust-canvas)] px-[20px] sm:px-[40px]">
      <div className="screen-in w-full max-w-[560px] text-center">
        <div className="mx-auto flex size-[44px] items-center justify-center rounded-full bg-[var(--color-ok-icon)]">
          <span className="text-[18px] font-bold text-white">✓</span>
        </div>

        <h1 className="mt-[32px] text-[28px] leading-[1.35] font-semibold tracking-[-0.022em] text-pretty sm:text-[32px]">
          고객 확인이 끝났습니다
        </h1>
        <p className="mt-[18px] text-[17px] leading-[1.7] text-[var(--color-cust-body)]">
          {pendingReview
            ? "화면을 담당 직원에게 전달해주세요. 함께 확인이 필요한 항목이 있습니다."
            : "화면을 담당 직원에게 전달해주세요. 확인 결과는 담당 직원이 검토한 뒤 상담을 종료합니다."}
        </p>

        <button
          type="button"
          onClick={() => router.push(destination)}
          disabled={session.isLoading}
          className="mt-[36px] rounded-[11px] bg-[var(--color-accent)] px-[30px] py-[15px] text-[16px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
        >
          {pendingReview ? "직원 확인 화면으로" : "직원 결과 화면으로"}
        </button>

        <p className="mt-[28px] text-[13px] leading-[1.7] text-[var(--color-cust-muted-soft)]">
          FinReady는 금융상담을 보조하며 법적 준수 여부를 판정하지 않습니다.
        </p>
      </div>
    </main>
  );
}

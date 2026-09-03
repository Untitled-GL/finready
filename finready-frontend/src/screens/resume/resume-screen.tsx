"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useSession } from "@/shared/api/queries";
import { resumeHref } from "@/shared/lib/resume";
import { ErrorNote } from "@/shared/ui/error-note";
import { ScreenSkeleton } from "@/shared/ui/screen-skeleton";
import { useScenarioQuery } from "@/shared/ui/staff-shell";

/**
 * `/session/{id}` — recovery entry point.
 *
 * Restores a session from the server and sends the browser wherever the
 * server says the session actually is. This is what makes a reload or a
 * bookmarked session id land on the right screen instead of trusting
 * whatever the client last remembered.
 */
export function ResumeScreen({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  const query = useScenarioQuery();
  const session = useSession(sessionId);

  const resumePoint = session.data?.resumePoint;

  useEffect(() => {
    if (!resumePoint) return;
    router.replace(resumeHref(sessionId, resumePoint, query));
  }, [resumePoint, router, sessionId, query]);

  if (session.isError) {
    return (
      <div className="mx-auto max-w-[760px] px-[20px] py-[72px] sm:px-[40px] sm:py-[96px]">
        <ErrorNote
          error={session.error}
          onRetry={() => void session.refetch()}
        />
        <button
          type="button"
          onClick={() => router.push("/")}
          className="mt-[20px] rounded-[10px] border border-[oklch(0.86_0.008_260)] bg-white px-[22px] py-[13px] text-[15px] font-semibold hover:bg-[oklch(0.96_0.004_85)]"
        >
          처음으로
        </button>
      </div>
    );
  }

  return <ScreenSkeleton label="상담 상태를 불러오는 중" />;
}

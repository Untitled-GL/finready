"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useCreateSession, useDemoProduct } from "@/shared/api/queries";
import { DISCLAIMER } from "@/shared/constants/labels";
import { scenarioSearch } from "@/shared/lib/demo-catalog";
import { ErrorNote } from "@/shared/ui/error-note";

const PILLARS = [
  {
    title: "상품 위험",
    body: "검수된 9개 위험 사실과 근거 페이지",
  },
  {
    title: "상담 설명",
    body: "각 위험이 실제로 설명됐는지 원문으로 확인",
  },
  {
    title: "고객 이해",
    body: "핵심 위험 3건을 고객이 직접 확인",
  },
  {
    title: "근거 기반 재확인",
    body: "상품설명서 원문으로 다시 설명하고 재확인",
  },
];

export function LandingScreen() {
  const router = useRouter();
  const demo = useDemoProduct();
  const createSession = useCreateSession();
  const [selectedCustomerId, setSelectedCustomerId] = useState("");
  const [selectedScenarioId, setSelectedScenarioId] = useState("");
  const [pending, setPending] = useState<string | null>(null);

  const customers = demo.data?.customers ?? [];
  const presets = demo.data?.demoPresets ?? [];
  const customerId = selectedCustomerId || customers[0]?.id || "";
  const scenarioId = selectedScenarioId || presets[0]?.id || "";

  const start = () => {
    if (createSession.isPending) return;
    const productId = demo.data?.product?.id;
    if (!productId || !customerId || !scenarioId) return;

    setPending(scenarioId);
    createSession.mutate(
      { productId, customerId },
      {
        onSuccess: (session) => {
          router.push(
            `/session/${session.sessionId}/prepare${scenarioSearch(scenarioId)}`,
          );
        },
        onError: () => setPending(null),
      },
    );
  };

  // The demo product must be loaded before a session can name its ids.
  const busy =
    createSession.isPending ||
    demo.isLoading ||
    !customerId ||
    !scenarioId;

  return (
    <div className="screen-in">
      <header className="flex items-center justify-between gap-[16px] border-b border-[var(--color-line)] px-[20px] py-[20px] sm:px-[40px] lg:px-[120px] lg:py-[24px]">
        <div className="flex items-center gap-[10px]">
          <span
            aria-hidden
            className="size-[18px] rounded-[5px] bg-[var(--color-accent)]"
          />
          <span className="text-[16px] font-semibold tracking-[-0.01em]">
            FinReady
          </span>
        </div>
        <span className="text-right text-[13px] text-[var(--color-muted)]">
          Finance, Ready for You.
        </span>
      </header>

      <main className="mx-auto max-w-[1040px] px-[20px] pt-[64px] sm:px-[40px] sm:pt-[88px] lg:pt-[112px]">
        <h1 className="text-[42px] leading-[1.18] font-bold tracking-[-0.035em] text-pretty sm:text-[56px] lg:text-[68px]">
          당신이 이해할 때까지,
          <br />
          금융을 더 명확하게.
        </h1>
        <p className="mt-[32px] max-w-[620px] text-[19px] leading-[1.65] text-pretty text-[var(--color-ink-muted)]">
          금융상품을 설명하는 AI가 아니라, 고객이 핵심 위험을 제대로 이해했는지
          확인하는 AI.
        </p>

        <section className="mt-[48px] grid max-w-[960px] gap-[24px] border-y border-[var(--color-line)] py-[28px] md:grid-cols-2">
          <label className="block">
            <span className="mb-[9px] block text-[13px] font-semibold text-[var(--color-ink-muted)]">
              고객 유형
            </span>
            <select
              value={customerId}
              onChange={(event) => setSelectedCustomerId(event.target.value)}
              disabled={demo.isLoading || customers.length === 0}
              className="block h-[48px] w-full rounded-[10px] border border-[var(--color-line)] bg-white px-[14px] text-[15px] text-[var(--color-ink)] focus:outline-2 focus:outline-offset-2 focus:outline-[var(--color-accent)] disabled:opacity-60"
            >
              {customers.map((customer) => (
                <option key={customer.id} value={customer.id}>
                  {customer.label}
                </option>
              ))}
            </select>
          </label>

          <label className="block">
            <span className="mb-[9px] block text-[13px] font-semibold text-[var(--color-ink-muted)]">
              상담 시나리오
            </span>
            <select
              value={scenarioId}
              onChange={(event) => setSelectedScenarioId(event.target.value)}
              disabled={demo.isLoading || presets.length === 0}
              className="block h-[48px] w-full rounded-[10px] border border-[var(--color-line)] bg-white px-[14px] text-[15px] text-[var(--color-ink)] focus:outline-2 focus:outline-offset-2 focus:outline-[var(--color-accent)] disabled:opacity-60"
            >
              {presets.map((preset) => (
                <option key={preset.id} value={preset.id}>
                  {preset.label}
                </option>
              ))}
            </select>
          </label>
        </section>

        <div className="mt-[28px] flex flex-col items-start gap-[12px] sm:flex-row sm:items-center sm:gap-[24px]">
          <button
            type="button"
            onClick={start}
            disabled={busy}
            className="whitespace-nowrap rounded-[11px] bg-[var(--color-accent)] px-[30px] py-[15px] text-[16px] font-semibold text-white hover:bg-[var(--color-accent-hover)] disabled:bg-[var(--color-accent-disabled)]"
          >
            {pending === scenarioId ? "세션 준비 중…" : "선택한 데모 시작"}
          </button>
          <span className="text-[14px] text-[var(--color-muted)]">
            {demo.data?.product?.name ?? "데모 상품"} · 약 3분
          </span>
        </div>

        {createSession.isError || demo.isError ? (
          <ErrorNote
            className="mt-[20px] max-w-[620px]"
            error={createSession.error ?? demo.error}
            onRetry={() =>
              demo.isError ? demo.refetch() : start()
            }
          />
        ) : null}

        <div className="mt-[80px] grid grid-cols-2 gap-y-[28px] border-t border-[var(--color-line)] pt-[28px] md:grid-cols-4">
          {PILLARS.map((pillar, index) => (
            <div
              key={pillar.title}
              className={index === PILLARS.length - 1 ? "" : "pr-[24px]"}
            >
              <p className="text-[15px] font-semibold">{pillar.title}</p>
              <p className="mt-[8px] text-[14px] leading-[1.6] text-[oklch(0.5_0.01_260)]">
                {pillar.body}
              </p>
            </div>
          ))}
        </div>

        <p className="mt-[36px] mb-[96px] text-[12.5px] text-[var(--color-muted-faint)]">
          {DISCLAIMER}
        </p>
      </main>
    </div>
  );
}

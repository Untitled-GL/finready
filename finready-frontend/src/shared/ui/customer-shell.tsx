/**
 * Customer-side chrome.
 *
 * A warmer surface and a much quieter header than the staff side: no step
 * nav, no revision badge, no scenario switch. Progress counts *risks*, not
 * questions — a follow-up question must not read as falling behind.
 */
export function CustomerShell({
  currentIndex,
  totalCount,
  children,
}: {
  /** 1-based position of the risk being confirmed. */
  currentIndex: number;
  totalCount: number;
  children: React.ReactNode;
}) {
  return (
    <div className="min-h-screen bg-[var(--color-cust-canvas)]">
      <header className="sticky top-0 z-20 border-b border-[var(--color-cust-line)] bg-[oklch(0.977_0.014_75/0.92)] backdrop-blur-[12px]">
        <div className="flex items-center justify-between gap-[12px] px-[16px] py-[14px] sm:px-[40px] sm:py-[16px]">
          <div className="flex min-w-0 items-center gap-[8px] sm:gap-[12px]">
            <span
              aria-hidden
              className="size-[16px] rounded-[5px] bg-[var(--color-accent)]"
            />
            <span className="text-[14px] font-semibold">FinReady</span>
            <span className="hidden rounded-full bg-[var(--color-cust-chip)] px-[10px] py-[4px] text-[13px] text-[var(--color-cust-body)] sm:inline">
              고객 확인 화면
            </span>
          </div>

          <div className="flex shrink-0 items-center gap-[8px] sm:gap-[12px]">
            <span className="text-[14px] font-semibold whitespace-nowrap">
              핵심 위험 {currentIndex} / {totalCount}
            </span>
            <div className="flex gap-[5px]" aria-hidden>
              {Array.from({ length: totalCount }, (_, i) => (
                <span
                  key={i}
                  className="size-[7px] rounded-full"
                  style={{
                    background:
                      i < currentIndex - 1
                        ? "var(--color-ok-dot)"
                        : i === currentIndex - 1
                          ? "var(--color-accent)"
                          : "oklch(0.88 0.014 75)",
                  }}
                />
              ))}
            </div>
          </div>
        </div>
      </header>
      {children}
    </div>
  );
}

/**
 * Quiet placeholder while a screen's data loads.
 *
 * Deliberately plain: no spinner, no shimmer, no progress percentage. The
 * long wait in this product is coverage analysis, which has its own honest
 * stage list.
 */
export function ScreenSkeleton({ label = "불러오는 중" }: { label?: string }) {
  return (
    <div
      role="status"
      aria-live="polite"
      className="fade-in mx-auto max-w-[1040px] px-[20px] py-[72px] text-[14px] text-[var(--color-muted-soft)] sm:px-[40px] sm:py-[96px]"
    >
      {label}
    </div>
  );
}

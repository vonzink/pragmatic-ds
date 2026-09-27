/** First focusable element: lets keyboard users jump past the header. */
export function SkipLink() {
  return (
    <a
      href="#main"
      className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-50 focus:rounded-sm focus:bg-ink focus:px-4 focus:py-3 focus:text-sm focus:font-semibold focus:text-on-ink"
    >
      Skip to content
    </a>
  );
}

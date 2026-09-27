import type { ReactNode } from "react";

/** Centers content at the 1200px max width with the standard gutters (40px desktop, 20px mobile). */
export function Container({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <div className={`mx-auto w-full max-w-content px-5 md:px-10 ${className}`}>{children}</div>;
}

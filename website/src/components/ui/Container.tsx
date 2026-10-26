import type { ReactNode } from "react";

/** Centers content at the 1200px max width. Side gutters come from the enclosing band. */
export function Container({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <div className={`mx-auto w-full max-w-content ${className}`}>{children}</div>;
}

"use client";

import { useState } from "react";
import type { Toc } from "./ServicePage";

/** Collapsible "On this page" box shown above the article under 900px. */
export function TocBox({ toc }: { toc: Toc }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="flex-[1_1_100%] rounded-sm border border-line min-[900px]:hidden">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className="eyebrow flex h-12 w-full cursor-pointer items-center justify-between px-4 text-ink"
      >
        On this page{" "}
        <span aria-hidden className={`text-xl font-normal transition-transform duration-150 ${open ? "rotate-45" : ""}`}>
          +
        </span>
      </button>
      {open && (
        <div className="flex flex-col border-t border-line-soft px-4 pb-2">
          {toc.map((t, i) => (
            <a key={t.id} href={`#${t.id}`} className="flex min-h-11 items-center gap-2.5 text-[15px] text-body">
              <span className="font-mono text-xs font-medium text-accent">{String(i + 1).padStart(2, "0")}</span>
              {t.label}
            </a>
          ))}
        </div>
      )}
    </div>
  );
}

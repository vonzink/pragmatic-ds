"use client";

import { useId, useState } from "react";

export type Faq = { q: string; a: string };

/** Single-open FAQ list. `initialOpen` -1 = all closed. */
export function Accordion({ items, initialOpen = -1 }: { items: Faq[]; initialOpen?: number }) {
  const [open, setOpen] = useState(initialOpen);
  const id = useId();
  return (
    <div>
      {items.map((f, i) => {
        const isOpen = open === i;
        return (
          <div key={f.q} className="border-b border-line">
            <button
              type="button"
              aria-expanded={isOpen}
              aria-controls={`${id}-${i}`}
              onClick={() => setOpen(isOpen ? -1 : i)}
              className="flex min-h-14 w-full cursor-pointer items-center justify-between gap-4 py-3.5 text-left text-base font-semibold text-ink"
            >
              {f.q}
              <span
                aria-hidden
                className={`flex-none text-[22px] font-normal transition-transform duration-150 ${isOpen ? "rotate-45" : ""}`}
              >
                +
              </span>
            </button>
            <div id={`${id}-${i}`} hidden={!isOpen}>
              <p className="pr-10 pb-[18px] text-[15px] leading-[1.6]">{f.a}</p>
            </div>
          </div>
        );
      })}
    </div>
  );
}

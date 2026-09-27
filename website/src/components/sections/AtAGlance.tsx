type Row = { label: string; value: string };

/** Small white fact table. Every value must be true and provable. */
export function AtAGlance({ rows }: { rows: Row[] }) {
  return (
    <div className="rounded-sm border border-line bg-surface-raised">
      <p className="eyebrow border-b border-line px-4 py-2.5 text-muted">At a glance</p>
      <dl className="divide-y divide-line-soft">
        {rows.map((row) => (
          <div key={row.label} className="flex justify-between gap-4 px-3.5 py-2.5 text-[13px] md:px-4 md:py-3 md:text-sm">
            <dt className="text-muted">{row.label}</dt>
            <dd className="text-right font-medium text-ink">{row.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

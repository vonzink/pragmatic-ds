/**
 * The boxes. This component is the visible half of the product's whole claim:
 * that every extracted value can be pointed at on the page it came from.
 *
 * It does no arithmetic. Every number it emits comes out of
 * {@link pdfBoxToViewport}, which is the only module allowed to know how PDF
 * points become CSS pixels — see the header of `coordinates.ts` for why that
 * rule is absolute rather than stylistic.
 */

import { pdfBoxToViewport, CoordinateError, type PageGeometry } from './coordinates.ts';
import { px } from './css.ts';
import type { EvidenceRole, EvidenceView, Uuid } from '../../lib/api/types.ts';

export type EvidenceOverlayProps = {
  /**
   * A field's complete evidence chain, across every page. The overlay filters
   * to `pageId` itself — per-page attribution lives here so no caller can
   * forget it and paint page 2's box onto page 1.
   */
  evidence: readonly EvidenceView[];
  /** The page currently on screen. */
  pageId: Uuid;
  /** That page's geometry, straight from `GET /v1/packages/{id}/pages`. */
  page: PageGeometry;
  /** Zoom, as a multiplier on PDF points. */
  scale: number;
  /** Extra rotation the reviewer applied, on top of the page's own `/Rotate`. */
  extraRotation?: number;
  /**
   * The selected occurrence's coordinate — `rentsReceived#B`, or a bare field
   * name when the field does not repeat. Only for `data-occurrence`, so a test
   * (or a human in devtools) can tell B's boxes from A's.
   *
   * A field name alone cannot do that job: three occurrences of
   * `rentsReceived` would tag their boxes identically, and "only B's boxes are
   * drawn" would be unassertable.
   */
  occurrence?: string;
};

/**
 * Per-role appearance.
 *
 * VALUE and LABEL must be distinguishable at a glance, and by more than hue —
 * colour alone fails for the ~8% of male reviewers with a red-green deficiency,
 * and fails entirely in a greyscale screenshot pasted into a ticket. So the
 * roles differ in border STYLE (solid vs dashed) as well as colour, and each
 * box carries a corner tag naming its role.
 */
const ROLE_STYLES: Record<string, { box: string; tag: string; label: string }> = {
  VALUE: {
    box: 'border-2 border-solid border-amber-500 bg-amber-400/25',
    tag: 'bg-amber-500 text-white',
    label: 'Value',
  },
  LABEL: {
    box: 'border-2 border-dashed border-sky-600 bg-sky-400/15',
    tag: 'bg-sky-600 text-white',
    label: 'Label',
  },
  CONTEXT: {
    box: 'border border-dotted border-slate-500 bg-slate-400/10',
    tag: 'bg-slate-500 text-white',
    label: 'Context',
  },
};

/** An unknown future role still gets a box — a neutral one — rather than nothing. */
const UNKNOWN_ROLE_STYLE = {
  box: 'border border-dotted border-slate-400 bg-slate-300/10',
  tag: 'bg-slate-400 text-white',
  label: 'Other',
};

function styleForRole(role: EvidenceRole) {
  return ROLE_STYLES[role] ?? UNKNOWN_ROLE_STYLE;
}

/**
 * The role key, rendered where the reviewer is looking.
 *
 * Two colours over a document mean nothing without this. "The label is why the
 * value was read that way" is the thesis; a legend is how a first-time reviewer
 * learns it in one glance instead of guessing.
 */
export function EvidenceLegend({ className = '' }: { className?: string }) {
  return (
    <ul className={`flex items-center gap-3 text-xs text-slate-600 ${className}`} data-testid="evidence-legend">
      {(['VALUE', 'LABEL'] as const).map((role) => (
        <li key={role} className="flex items-center gap-1.5">
          <span
            aria-hidden="true"
            className={`inline-block h-3 w-5 rounded-xs ${ROLE_STYLES[role].box}`}
          />
          <span>
            {ROLE_STYLES[role].label}
            {role === 'LABEL' ? ' (why)' : ''}
          </span>
        </li>
      ))}
    </ul>
  );
}

export default function EvidenceOverlay({
  evidence,
  pageId,
  page,
  scale,
  extraRotation = 0,
  occurrence,
}: EvidenceOverlayProps) {
  const onThisPage = evidence.filter((item) => item.pageId === pageId);

  let boxes: { item: EvidenceView; left: number; top: number; width: number; height: number }[];
  try {
    boxes = onThisPage.map((item) => ({
      item,
      ...pdfBoxToViewport(item, page, scale, extraRotation),
    }));
  } catch (error) {
    // A malformed page row (a non-quarter-turn rotation, a zero width) must not
    // take the whole viewer down, and must not silently draw nothing either —
    // an evidence overlay that quietly renders empty is the failure mode this
    // whole subsystem exists to prevent.
    if (!(error instanceof CoordinateError)) throw error;
    return (
      <div
        data-testid="evidence-overlay-error"
        role="status"
        className="absolute left-2 top-2 rounded-sm bg-red-600 px-2 py-1 text-xs text-white shadow"
      >
        Cannot place evidence on this page: {error.message}
      </div>
    );
  }

  return (
    // aria-hidden: the boxes are a visual echo of the field panel, which already
    // states in text which page and role each piece of evidence is on. Announcing
    // a stack of unlabelled rectangles would be noise, not access.
    <div
      data-testid="evidence-overlay"
      aria-hidden="true"
      className="pointer-events-none absolute inset-0"
    >
      {boxes.map(({ item, left, top, width, height }, index) => {
        const style = styleForRole(item.role);
        // One tag per ROLE, on its first box. A multi-span value ("Jordan Q. Fixture") or label
        // ("Net Pay") is several adjacent boxes, and tagging each one stacked the captions into
        // an unreadable smear directly over the words being highlighted — the highlight
        // obscuring the thing it highlights. The toolbar legend carries the key.
        const firstOfRole = boxes.findIndex((candidate) => candidate.item.role === item.role);
        const showTag = firstOfRole === index;
        return (
          <div
            key={`${item.role}-${String(item.ordinal)}-${item.pageId}-${String(item.x)}-${String(item.y)}`}
            data-testid="evidence-box"
            data-role={item.role}
            data-occurrence={occurrence}
            data-ordinal={item.ordinal}
            className={`absolute rounded-xs ${style.box}`}
            style={{ left: px(left), top: px(top), width: px(width), height: px(height) }}
          >
            {showTag ? (
              <span
                className={`absolute -top-4 left-0 rounded-xs px-1 text-[10px] leading-4 font-medium whitespace-nowrap ${style.tag}`}
              >
                {style.label}
              </span>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}

/**
 * Builds the PDS logo SVGs from code so every variant shares one geometry.
 *
 * Outputs:
 *   public/brand/*.svg                         standalone logo files (text converted to paths)
 *   src/components/brand/mark.generated.ts     the same mark geometry, for the <Mark> component
 *
 * Run from website/:  npm run brand
 * Re-run only when the logo itself changes. Outputs are committed.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import opentype from "opentype.js";

const require = createRequire(import.meta.url);
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT_DIR = join(ROOT, "public", "brand");
const GENERATED_TS = join(ROOT, "src", "components", "brand", "mark.generated.ts");

// ---------------------------------------------------------------------------
// Brand palette (must match src/app/globals.css)
// ---------------------------------------------------------------------------
const COLOR = {
  ink: "#16181A",
  accent: "#1F5EA8",
  muted: "#5A5F5F",
  onInk: "#FFFFFF",
  onInkMuted: "#D8D4CB",
};

// ---------------------------------------------------------------------------
// Fonts (used only to convert logo lettering to outlines)
// ---------------------------------------------------------------------------
const loadFont = (pkg, file) =>
  opentype.parse(readFileSync(require.resolve(`@fontsource/${pkg}/files/${file}`)).buffer);

const FONT = {
  display: loadFont("inter-tight", "inter-tight-latin-800-normal.woff"),
  llc: loadFont("inter-tight", "inter-tight-latin-400-normal.woff"),
  mono: loadFont("ibm-plex-mono", "ibm-plex-mono-latin-400-normal.woff"),
};

/** Lays out a line of text as a single SVG path, with optional tracking (em). */
function textPath(font, text, { x = 0, y = 0, size, tracking = 0 }) {
  // Plain char -> glyph mapping. Logo text needs no ligatures, and full
  // shaping trips on GSUB lookups opentype.js doesn't support.
  const glyphs = [...text].map((ch) => font.charToGlyph(ch));
  const scale = size / font.unitsPerEm;
  const path = new opentype.Path();
  let cursor = x;
  glyphs.forEach((glyph, i) => {
    path.extend(glyph.getPath(cursor, y, size));
    cursor += glyph.advanceWidth * scale + tracking * size;
    const next = glyphs[i + 1];
    if (next) cursor += font.getKerningValue(glyph, next) * scale;
  });
  const width = cursor - x - (glyphs.length ? tracking * size : 0);
  return { d: path.toPathData(2), bbox: path.getBoundingBox(), width };
}

// ---------------------------------------------------------------------------
// Mark geometry: pointy-top hexagon, blue overdraw on the top-right two edges
// ---------------------------------------------------------------------------
const MARK_W = 100;
const MARK_H = 110;
const CX = 50;
const CY = 55;
const RADIUS = 47;
const STROKE = 9;

const vertex = (deg) => {
  const rad = (deg * Math.PI) / 180;
  return [+(CX + RADIUS * Math.cos(rad)).toFixed(2), +(CY + RADIUS * Math.sin(rad)).toFixed(2)];
};
// Clockwise from the top vertex.
const HEX = [-90, -30, 30, 90, 150, 210].map(vertex);
const ACCENT = [HEX[0], HEX[1], HEX[2]];
const pts = (list) => list.map(([x, y]) => `${x},${y}`).join(" ");

// "PD" centred optically inside the hexagon.
const GLYPH = (() => {
  const size = 44;
  const raw = textPath(FONT.display, "PD", { size, tracking: -0.02 });
  const b = raw.bbox;
  const dx = CX - (b.x1 + b.x2) / 2;
  const dy = CY - (b.y1 + b.y2) / 2;
  return textPath(FONT.display, "PD", { x: dx, y: dy, size, tracking: -0.02 }).d;
})();

/** Mark as SVG markup, positioned at (x, y) and scaled to `height`. */
function markGroup({ x = 0, y = 0, height = MARK_H, stroke, letters }) {
  const s = height / MARK_H;
  return `<g transform="translate(${x} ${y}) scale(${+s.toFixed(4)})">
    <polygon points="${pts(HEX)}" fill="none" stroke="${stroke}" stroke-width="${STROKE}" stroke-linejoin="miter"/>
    <polyline points="${pts(ACCENT)}" fill="none" stroke="${COLOR.accent}" stroke-width="${STROKE}" stroke-linejoin="miter"/>
    <path d="${GLYPH}" fill="${letters}"/>
  </g>`;
}

const svg = (viewBox, title, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}" role="img" aria-labelledby="t">
  <title id="t">${title}</title>
  ${body}
</svg>
`;

// ---------------------------------------------------------------------------
// Variants
// ---------------------------------------------------------------------------
const PAD = 6; // room for the stroke's miter points

function buildMark({ stroke, letters }) {
  return svg(
    `${-PAD} ${-PAD} ${MARK_W + PAD * 2} ${MARK_H + PAD * 2}`,
    "Pragmatic Defense Solutions",
    markGroup({ stroke, letters }),
  );
}

function buildTile() {
  const size = 512;
  const h = 300;
  const w = (h / MARK_H) * MARK_W;
  return svg(
    `0 0 ${size} ${size}`,
    "Pragmatic Defense Solutions",
    `<rect width="${size}" height="${size}" fill="${COLOR.ink}"/>
  ${markGroup({ x: (size - w) / 2, y: (size - h) / 2, height: h, stroke: COLOR.onInk, letters: COLOR.onInk })}`,
  );
}

function buildLockup({ primary, secondary }) {
  const markH = 260;
  const markW = (markH / MARK_H) * MARK_W;
  const divX = markW + 56;
  const textX = divX + 56;
  const size = 104;
  const lead = 88;

  const lines = ["Pragmatic", "Defense", "Solutions"].map((word, i) =>
    textPath(FONT.display, word, { x: textX, y: 88 + i * lead, size, tracking: -0.03 }),
  );
  const last = lines[2];
  const llc = textPath(FONT.llc, "LLC", { x: textX + last.width + 22, y: 88 + 2 * lead, size: size * 0.8 });
  const wordRight = Math.max(...lines.map((l) => l.bbox.x2), llc.bbox.x2);

  const tagSize = 34;
  const tagTracking = 0.32;
  const tagProbe = textPath(FONT.mono, "PEOPLE / STRATEGY / IMPACT", { size: tagSize, tracking: tagTracking });
  const tag = textPath(FONT.mono, "PEOPLE / STRATEGY / IMPACT", {
    x: (wordRight - tagProbe.width) / 2,
    y: markH + 96,
    size: tagSize,
    tracking: tagTracking,
  });

  const width = Math.ceil(wordRight) + PAD * 2;
  const height = Math.ceil(tag.bbox.y2) + PAD * 2;

  return svg(
    `${-PAD} ${-PAD * 2} ${width} ${height + PAD}`,
    "Pragmatic Defense Solutions LLC — People / Strategy / Impact",
    `${markGroup({ height: markH, stroke: primary, letters: primary })}
  <rect x="${divX}" y="16" width="4" height="${markH - 32}" fill="${primary}"/>
  <path d="${lines.map((l) => l.d).join(" ")}" fill="${primary}"/>
  <path d="${llc.d}" fill="${secondary}"/>
  <path d="${tag.d}" fill="${secondary}"/>`,
  );
}

// ---------------------------------------------------------------------------
// Write
// ---------------------------------------------------------------------------
mkdirSync(OUT_DIR, { recursive: true });

const files = {
  "pds-mark.svg": buildMark({ stroke: COLOR.ink, letters: COLOR.ink }),
  "pds-mark-reverse.svg": buildMark({ stroke: COLOR.onInk, letters: COLOR.onInk }),
  "pds-mark-tile.svg": buildTile(),
  "pds-logo.svg": buildLockup({ primary: COLOR.ink, secondary: COLOR.muted }),
  "pds-logo-reverse.svg": buildLockup({ primary: COLOR.onInk, secondary: COLOR.onInkMuted }),
};

for (const [name, content] of Object.entries(files)) {
  writeFileSync(join(OUT_DIR, name), content);
  console.log(`wrote public/brand/${name}`);
}

writeFileSync(
  GENERATED_TS,
  `// Generated by scripts/build-brand-assets.mjs. Do not edit by hand.
export const MARK = {
  viewBox: "${-PAD} ${-PAD} ${MARK_W + PAD * 2} ${MARK_H + PAD * 2}",
  strokeWidth: ${STROKE},
  hexagon: "${pts(HEX)}",
  accent: "${pts(ACCENT)}",
  letters: "${GLYPH}",
} as const;
`,
);
console.log("wrote src/components/brand/mark.generated.ts");

import { expect, test } from '@playwright/test';
import { fileURLToPath } from 'node:url';

/**
 * Spec 5b in a real browser: a Schedule E's 67 occurrences, rendered as the
 * form's own tables, with the overlay following the occurrence rather than the
 * field name.
 *
 * Needs a live stack — `docker compose up -d` from the repository root, with
 * the API on 9090 — the same requirement `review.spec.ts` documents. It
 * uploads the committed synthetic fixture; no real borrower data is involved.
 *
 * ```bash
 * docker compose up -d      # from the repository root
 * npm run test:e2e --prefix ui
 * ```
 */

const SCHEDULE_E = fileURLToPath(new URL('../../fixtures/schedule_e.pdf', import.meta.url));

/** Processing is several worker round trips; generous, and still bounded. */
const PROCESSED_TIMEOUT = 120_000;

async function openScheduleE(page: import('@playwright/test').Page) {
  await page.goto('/');
  await page.getByTestId('upload-input').setInputFiles(SCHEDULE_E);
  await expect(page.getByTestId('job-status')).toHaveAttribute(
    'data-job-status',
    /COMPLETED|HUMAN_REVIEW_REQUIRED/,
    { timeout: PROCESSED_TIMEOUT },
  );
  await expect(page.getByTestId('group-cluster').first()).toBeVisible({
    timeout: PROCESSED_TIMEOUT,
  });
}

test('a Schedule E renders as five group tables, not 67 flat rows', async ({ page }) => {
  await openScheduleE(page);

  const clusters = page.getByTestId('group-cluster');
  await expect(clusters).toHaveCount(5);

  // The same five sections, in the same order, that `GET /fields.md` renders —
  // one read model, one story on both surfaces.
  const labels = await clusters.evaluateAll((nodes) =>
    nodes.map((node) => node.getAttribute('data-cluster')),
  );
  expect(labels).toEqual([
    'Group A–C (3)',
    'Estate Or Trust A–B (2)',
    'Partnership A–D (4)',
    'Property Address 01–03 (3)',
    'Remic 01 (1)',
  ]);

  // Only the eight ungrouped fields keep a flat row.
  await expect(page.getByTestId('field-row')).toHaveCount(8);
});

test('a COLUMN group reads across and a ROW group reads down', async ({ page }) => {
  await openScheduleE(page);

  const clusters = page.getByTestId('group-cluster');
  await expect(clusters.nth(0)).toHaveAttribute('data-orientation', 'columns');
  await expect(clusters.nth(0)).toHaveAttribute('data-group-kind', 'COLUMN');
  await expect(clusters.nth(2)).toHaveAttribute('data-orientation', 'rows');
  await expect(clusters.nth(2)).toHaveAttribute('data-group-kind', 'ROW');

  // A's cell sits to the LEFT of B's in the money grid: the orientation is a
  // claim about the layout, so this asserts geometry, not an attribute.
  const a = await page
    .locator('[data-testid="occurrence-cell"][data-occurrence="rentsReceived#A"]')
    .boundingBox();
  const b = await page
    .locator('[data-testid="occurrence-cell"][data-occurrence="rentsReceived#B"]')
    .boundingBox();
  expect(a).not.toBeNull();
  expect(b).not.toBeNull();
  if (!a || !b) return;
  expect(b.x).toBeGreaterThan(a.x);
  expect(Math.abs(b.y - a.y)).toBeLessThan(2);

  // In the entity table, row B sits BELOW row A.
  const nameA = await page
    .locator('[data-testid="occurrence-cell"][data-occurrence="partnershipName#A"]')
    .boundingBox();
  const nameB = await page
    .locator('[data-testid="occurrence-cell"][data-occurrence="partnershipName#B"]')
    .boundingBox();
  expect(nameA).not.toBeNull();
  expect(nameB).not.toBeNull();
  if (!nameA || !nameB) return;
  expect(nameB.y).toBeGreaterThan(nameA.y);
  expect(Math.abs(nameB.x - nameA.x)).toBeLessThan(2);
});

test('selecting occurrence B highlights B’s boxes and none of A’s', async ({ page }) => {
  await openScheduleE(page);

  await page
    .locator('[data-testid="occurrence-cell"][data-occurrence="rentsReceived#B"]')
    .getByTestId('occurrence-select')
    .click();

  const drawn = page.locator('[data-testid="evidence-box"]');
  await expect(drawn.first()).toBeVisible();
  const attributed = await drawn.evaluateAll((nodes) =>
    nodes.map((node) => node.getAttribute('data-occurrence')),
  );
  expect(new Set(attributed)).toEqual(new Set(['rentsReceived#B']));

  // And the box is really over B's column, not A's: B is further right.
  const valueBox = await page
    .locator('[data-testid="evidence-box"][data-role="VALUE"]')
    .first()
    .boundingBox();
  const surface = await page.getByTestId('page-surface').boundingBox();
  expect(valueBox).not.toBeNull();
  expect(surface).not.toBeNull();
  if (!valueBox || !surface) return;
  // A is at x=386pt, B at x=456pt on a 612pt page — B is past the midpoint.
  expect(valueBox.x - surface.x).toBeGreaterThan(surface.width * 0.6);
});

test('a multi-span value highlights every one of its boxes', async ({ page }) => {
  await openScheduleE(page);

  // `( 18,470 )` — the two parenthesis glyphs are their own spans.
  await page
    .locator('[data-testid="occurrence-cell"][data-occurrence="incomeOrLoss#B"]')
    .getByTestId('occurrence-select')
    .click();

  const values = page.locator('[data-testid="evidence-box"][data-role="VALUE"]');
  await expect(values).toHaveCount(3);
  const attributed = await page
    .locator('[data-testid="evidence-box"]')
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-occurrence')));
  expect(new Set(attributed)).toEqual(new Set(['incomeOrLoss#B']));
});

test('a missing occurrence is visible, selectable, and draws no boxes', async ({ page }) => {
  await openScheduleE(page);

  const missing = page.locator('[data-testid="occurrence-cell"][data-occurrence="rentsReceived#C"]');
  await expect(missing).toBeVisible();
  await expect(missing).toHaveAttribute('data-missing', 'true');
  await expect(missing.getByTestId('occurrence-missing')).toContainText('missing');

  await missing.getByTestId('occurrence-select').click();

  await expect(missing).toHaveAttribute('data-selected', 'true');
  await expect(page.locator('[data-testid="evidence-box"]')).toHaveCount(0);
  // The detail strip still answers, with the components visible as text.
  await expect(page.getByTestId('occurrence-detail')).toHaveAttribute(
    'data-occurrence',
    'rentsReceived#C',
  );
  await expect(page.getByTestId('occurrence-detail').getByTestId('extraction-method')).toHaveText(
    'NONE',
  );
});

test('the never-read region is a banner, in neither the field list nor any table', async ({
  page,
}) => {
  await openScheduleE(page);

  const banner = page.getByTestId('region-not-read');
  await expect(banner).toHaveCount(1);
  await expect(banner).toHaveAttribute('data-field-name', 'remicExcessInclusion');
  await expect(banner).toHaveAttribute('data-group-kind', 'ROW');
  await expect(banner).toContainText('Manual review required');

  await expect(
    page.locator('[data-testid="field-row"][data-field-name="remicExcessInclusion"]'),
  ).toHaveCount(0);
  await expect(
    page.locator('[data-testid="occurrence-cell"][data-field-name="remicExcessInclusion"]'),
  ).toHaveCount(0);
});

test('the three confidence components are readable text, and ROW_CELL is named', async ({
  page,
}) => {
  await openScheduleE(page);

  await page
    .locator('[data-testid="occurrence-cell"][data-occurrence="partnershipName#A"]')
    .getByTestId('occurrence-select')
    .click();

  const detail = page.getByTestId('occurrence-detail');
  await expect(detail.getByTestId('extraction-method')).toHaveText('ROW_CELL');
  await expect(detail.getByTestId('confidence-components')).toHaveText(/\d.*·.*·/);
});

test('no duplicate React keys are warned about while rendering 67 occurrences', async ({
  page,
}) => {
  const warnings: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') warnings.push(message.text());
  });

  await openScheduleE(page);

  expect(warnings.filter((text) => /same key|duplicate key|unique "key"/i.test(text))).toEqual([]);
});

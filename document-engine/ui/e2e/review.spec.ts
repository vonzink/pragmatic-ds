import { expect, test } from '@playwright/test';
import { fileURLToPath } from 'node:url';

/**
 * ⚠️ **This spec has never been executed.** It was written against the same
 * contract the component tests use, but it needs a live stack — `docker compose
 * up -d` from the repository root, with the API on 9090 — and no CI workflow
 * runs it today (nothing under `.github/workflows/` invokes `test:e2e`). Treat
 * a first run as a debugging session, not as a regression.
 *
 * It exists because it is the only artifact that can demonstrate acceptance
 * criterion 1 — "upload through review works end to end in a browser" — which
 * component tests structurally cannot: happy-dom has no canvas, no Worker, and
 * no layout engine, so pdf.js never runs and nothing is ever really *painted*.
 *
 * Run it with:
 *
 * ```bash
 * docker compose up -d      # from the repository root
 * npm run test:e2e --prefix ui
 * ```
 */

const PAYSTUB = fileURLToPath(new URL('../../fixtures/paystub_twopage.pdf', import.meta.url));

/** Processing is several worker round trips; generous, and still bounded. */
const PROCESSED_TIMEOUT = 120_000;

test('upload, wait for processing, select a field, and see its evidence', async ({ page }) => {
  await page.goto('/');

  await page.getByTestId('upload-input').setInputFiles(PAYSTUB);

  // The job reaches a terminal state; the stage trail is on screen throughout.
  await expect(page.getByTestId('job-status')).toHaveAttribute(
    'data-job-status',
    /COMPLETED|HUMAN_REVIEW_REQUIRED/,
    { timeout: PROCESSED_TIMEOUT },
  );

  const netPay = page.locator('[data-testid="field-row"][data-field-name="netPay"]');
  await expect(netPay).toBeVisible({ timeout: PROCESSED_TIMEOUT });
  await netPay.getByTestId('field-select').click();

  // At least one VALUE box, and it is inside the page surface — the check that
  // catches a coordinate space mix-up, which a pure count would not.
  const value = page.locator('[data-testid="evidence-box"][data-role="VALUE"]').first();
  await expect(value).toBeVisible();

  const box = await value.boundingBox();
  const surface = await page.getByTestId('page-surface').boundingBox();
  expect(box).not.toBeNull();
  expect(surface).not.toBeNull();
  if (!box || !surface) return;

  expect(box.x).toBeGreaterThanOrEqual(surface.x - 1);
  expect(box.y).toBeGreaterThanOrEqual(surface.y - 1);
  expect(box.x + box.width).toBeLessThanOrEqual(surface.x + surface.width + 1);
  expect(box.y + box.height).toBeLessThanOrEqual(surface.y + surface.height + 1);

  // Zoom must move the box in proportion, not leave it behind.
  await page.getByRole('button', { name: 'Zoom in' }).click();
  await expect(page.getByTestId('zoom-level')).toHaveAttribute('data-scale', '1.25');

  const zoomed = await value.boundingBox();
  expect(zoomed).not.toBeNull();
  if (!zoomed) return;
  expect(zoomed.width).toBeCloseTo(box.width * 1.25, 0);
});

test('a field on page 2 navigates the viewer to page 2', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('upload-input').setInputFiles(PAYSTUB);
  await expect(page.getByTestId('job-status')).toHaveAttribute(
    'data-job-status',
    /COMPLETED|HUMAN_REVIEW_REQUIRED/,
    { timeout: PROCESSED_TIMEOUT },
  );

  const payFrequency = page.locator('[data-testid="field-row"][data-field-name="payFrequency"]');
  await expect(payFrequency).toBeVisible({ timeout: PROCESSED_TIMEOUT });
  await payFrequency.getByTestId('field-select').click();

  await expect(page.getByTestId('page-position')).toHaveText(/Page 2 of/);
  await expect(page.locator('[data-testid="evidence-box"]').first()).toBeVisible();
});

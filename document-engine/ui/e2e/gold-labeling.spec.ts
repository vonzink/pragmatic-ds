import { expect, test } from '@playwright/test';
import { fileURLToPath } from 'node:url';

/**
 * The gold-set labeling loop in a real browser: upload the committed synthetic
 * W-2, confirm a field, correct one with a page, confirm the rest, mark the
 * document reviewed, then read the ADMIN-only gold export and check it carries
 * the correction unmasked. No real borrower data is involved.
 *
 * Needs a live stack built from THIS branch (the gold endpoint), reachable by
 * the Vite dev server's `/v1` proxy — `docker compose up -d --build` from the
 * repository root, or an alternate-port stack with `VITE_API_PROXY_TARGET`:
 *
 * ```bash
 * VITE_API_PROXY_TARGET=http://localhost:8095 npx playwright test e2e/gold-labeling.spec.ts
 * ```
 */

const W2 = fileURLToPath(new URL('../../fixtures/w2_form.pdf', import.meta.url));

/** Processing is several worker round trips; generous, and still bounded. */
const PROCESSED_TIMEOUT = 120_000;

/** The engine behind the dev server's `/v1` proxy — the same origin the page talks to. */
const API = '/v1';

test('label a synthetic W-2 and read its gold export', async ({ page, request, baseURL }) => {
  await page.goto('/');
  // The upload's answer is where the package id comes from; catch it on the wire.
  const uploaded = page.waitForResponse(
    (response) => response.url().includes('/v1/packages') && response.request().method() === 'POST',
  );
  await page.getByTestId('upload-input').setInputFiles(W2);
  const packageId = ((await (await uploaded).json()) as { packageId: string }).packageId;
  expect(packageId).toBeTruthy();
  await expect(page.getByTestId('job-status')).toHaveAttribute(
    'data-job-status',
    /COMPLETED|HUMAN_REVIEW_REQUIRED/,
    { timeout: PROCESSED_TIMEOUT },
  );
  await expect(page.getByTestId('field-row').first()).toBeVisible({ timeout: PROCESSED_TIMEOUT });

  const row = (name: string) => page.locator(`[data-testid="field-row"][data-field-name="${name}"]`);

  // taxYear is the one W-2 field the deterministic path reads at high confidence on this
  // fixture through the real worker; employeeName comes back MISSING (no Confirm button, by
  // design), so it is not the field to confirm here.
  await row('taxYear').getByRole('button', { name: 'Confirm' }).click();
  await expect(row('taxYear').getByTestId('review-status')).toContainText(/confirmed/i);

  await row('employeeSsn').getByRole('button', { name: 'Correct' }).click();
  await page.getByLabel('Value as printed').fill('987-65-4321');
  await page.getByRole('button', { name: 'Save correction' }).click();
  await expect(row('employeeSsn').getByTestId('review-status')).toContainText(/corrected/i);

  await page.getByRole('button', { name: 'Confirm all remaining' }).click();
  await page.getByRole('button', { name: 'Mark reviewed' }).click();
  await expect(page.getByRole('button', { name: 'Mark reviewed' })).toBeDisabled();

  const origin = baseURL ?? 'http://localhost:6173';
  const documents = await (
    await request.get(`${origin}${API}/packages/${packageId}/documents`, {
      headers: { 'X-Dev-Role': 'ADMIN' },
    })
  ).json();
  const gold = await (
    await request.get(`${origin}${API}/documents/${documents.documents[0].id}/gold`, {
      headers: { 'X-Dev-Role': 'ADMIN' },
    })
  ).json();
  const ssn = gold.fields.find((field: { field: string }) => field.field === 'employeeSsn');
  expect(ssn.decision).toBe('CORRECTED');
  expect(ssn.displayedText).toBe('987-65-4321');
  const taxYear = gold.fields.find((field: { field: string }) => field.field === 'taxYear');
  expect(taxYear.decision).toBe('CONFIRMED');
});

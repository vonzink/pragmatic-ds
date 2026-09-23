import { expect, test } from '@playwright/test';
import { fileURLToPath } from 'node:url';

/**
 * Select-and-act, end to end in a browser — the one thing component tests
 * structurally cannot prove, because the regroup path only closes once a real
 * package has been classified, split, and re-extracted by the live stack.
 *
 * Needs the stack up (`docker compose up -d` from the repository root, API on
 * 9090, which the Vite dev server proxies at `/v1`). Mirrors `review.spec.ts`;
 * no CI workflow runs `test:e2e`, so treat a first run as a debugging session.
 *
 * The flow follows a reviewer fixing the split of `combined_package.pdf`:
 * override a page the engine called a duplicate, promote it into a new PAYSTUB
 * document, and watch the affected documents re-extract.
 *
 * ```bash
 * docker compose up -d      # from the repository root
 * npm run test:e2e --prefix ui
 * ```
 */

const COMBINED = fileURLToPath(new URL('../../fixtures/combined_package.pdf', import.meta.url));

/** Processing is several worker round trips; generous, and still bounded. */
const PROCESSED_TIMEOUT = 120_000;

/** A regroup's refetch and its re-extraction are quick, but still a few round trips. */
const REGROUP_TIMEOUT = 30_000;

/** The non-terminal stages a re-extraction passes back through (design §8). */
const REPROCESSING =
  /UPLOADED|VALIDATING|NORMALIZING|RENDERING|TEXT_EXTRACTION|OCR_PROCESSING|PARSING|CLASSIFYING|SPLITTING|EXTRACTING|AI_EXTRACTION|FINALIZING|VALIDATING_DATA|AI_REVIEW/;

test('override a duplicate, promote it to a new document, and see it re-extract', async ({
  page,
}) => {
  await page.goto('/');
  await page.getByTestId('upload-input').setInputFiles(COMBINED);

  // The package processes to a terminal state; the split projection is on screen.
  await expect(page.getByTestId('job-status')).toHaveAttribute(
    'data-job-status',
    /COMPLETED|HUMAN_REVIEW_REQUIRED/,
    { timeout: PROCESSED_TIMEOUT },
  );
  await expect(page.getByTestId('document-row').first()).toBeVisible({ timeout: PROCESSED_TIMEOUT });

  // A duplicate page landed in no document; the tray offers to override that.
  const notDuplicate = page.locator('[data-testid^="verdict-not-duplicate-"]').first();
  await expect(notDuplicate).toBeVisible({ timeout: PROCESSED_TIMEOUT });
  const verdictTestId = (await notDuplicate.getAttribute('data-testid')) ?? '';
  const pageId = verdictTestId.replace('verdict-not-duplicate-', '');

  // The chip the page will wear once it is in a document: "Page N ·" in the tray
  // becomes "pN" on the document chip (both are packagePageIndex + 1). Scope the
  // row by this page's own verdict button — there are several duplicate rows.
  const trayRow = page.locator('[data-testid="unassigned-page"]', {
    has: page.getByTestId(`verdict-not-duplicate-${pageId}`),
  });
  const trayText = (await trayRow.innerText()).match(/Page (\d+)/);
  expect(trayText).not.toBeNull();
  const pageNumber = Number(trayText?.[1]);

  const documentsBefore = await page.getByTestId('document-row').count();

  // Override the verdict → the page becomes assignable.
  await notDuplicate.click();
  const checkbox = page.getByTestId(`page-checkbox-${pageId}`);
  await expect(checkbox).toBeEnabled({ timeout: REGROUP_TIMEOUT });
  await checkbox.check();

  // Promote the selection into a new PAYSTUB document.
  await page.getByTestId('regroup-new').click();
  await page.getByTestId('move-dialog-type').selectOption('PAYSTUB');
  await page.getByTestId('move-dialog-submit').click();

  // (b) The processing indicator re-enters: the regroup re-extracts the affected
  // documents through the stage machine, so the job leaves its terminal state.
  await expect(page.getByTestId('job-status')).toHaveAttribute('data-job-status', REPROCESSING, {
    timeout: REGROUP_TIMEOUT,
  });

  // (a) A new document row appears, and the promoted page now sits in a document.
  await expect(page.getByTestId('document-row')).toHaveCount(documentsBefore + 1, {
    timeout: REGROUP_TIMEOUT,
  });
  await expect(
    page
      .locator('[data-testid="document-page-chip"]')
      .filter({ hasText: new RegExp(`^p${String(pageNumber)}$`) }),
  ).toBeVisible({ timeout: REGROUP_TIMEOUT });

  // It settles back to a terminal state once re-extraction finishes.
  await expect(page.getByTestId('job-status')).toHaveAttribute(
    'data-job-status',
    /COMPLETED|HUMAN_REVIEW_REQUIRED/,
    { timeout: PROCESSED_TIMEOUT },
  );
});

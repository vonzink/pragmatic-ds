import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

/**
 * Testing Library only auto-registers its cleanup when `globals: true`, which
 * this project deliberately does not set — tests import `describe`/`it`
 * explicitly. Without this hook every render accumulates in `document.body`
 * and queries start matching elements from previous tests, which fails as
 * "found multiple elements" if you are lucky and passes wrongly if you are not.
 */
afterEach(() => {
  cleanup();
});

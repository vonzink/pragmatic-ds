import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  apiUrl,
  fileContentUrl,
  getDocumentFields,
  getJob,
  overridePageVerdict,
  pageRenderUrl,
  regroup,
  uploadPackage,
} from './client.ts';
import { buildUnassignDelta } from '../../features/review/regroup.ts';

/*
 * The contract under test is narrow and worth stating: whatever the server or
 * the network does, a component receives either a parsed body or an ApiError
 * with a displayable `code`. Never a bare Response, never a rejected promise
 * carrying a stack.
 */

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('apiUrl', () => {
  it('builds on the configured base', () => {
    expect(apiUrl('/jobs/abc')).toBe('/v1/jobs/abc');
  });

  it('tolerates a path without a leading slash', () => {
    expect(apiUrl('jobs/abc')).toBe('/v1/jobs/abc');
  });
});

describe('request paths', () => {
  it('calls the spec’s path for a job', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 'abc' }));
    await getJob('abc');
    expect(fetchMock.mock.calls[0][0]).toBe('/v1/jobs/abc');
  });

  it('calls the spec’s path for document fields', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ documentId: 'doc-1', fields: [] }));
    await getDocumentFields('doc-1');
    expect(fetchMock.mock.calls[0][0]).toBe('/v1/documents/doc-1/fields');
  });

  it('escapes an id rather than letting it alter the path', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}));
    await getJob('a/b');
    expect(fetchMock.mock.calls[0][0]).toBe('/v1/jobs/a%2Fb');
  });

  it('exposes render and content as URLs, for the browser to fetch', () => {
    expect(pageRenderUrl('page-1')).toBe('/v1/pages/page-1/render');
    expect(fileContentUrl('file-1')).toBe('/v1/files/file-1/content');
  });
});

describe('regroup', () => {
  it('POSTs the delta as JSON to the package’s regroup path and returns the view', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ packageId: 'pkg-1', documents: [], unassignedPages: [] }),
    );

    const view = await regroup('pkg-1', buildUnassignDelta(['page-1']));

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/v1/packages/pkg-1/regroup');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toMatchObject({
      intent: 'UNASSIGN',
      moves: [{ pageId: 'page-1', toDocumentId: null }],
    });
    expect(view.packageId).toBe('pkg-1');
  });
});

describe('overridePageVerdict', () => {
  it('POSTs the verdict and tolerates the endpoint’s empty 200 body', async () => {
    // The endpoint returns 200 with no body; parsing it as JSON would throw, so
    // the client must not try. A silent resolve is the whole contract.
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }));

    await expect(
      overridePageVerdict('page-9', 'NOT_DUPLICATE', 'reviewer says distinct'),
    ).resolves.toBeUndefined();

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/v1/pages/page-9/verdict');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({
      verdict: 'NOT_DUPLICATE',
      reason: 'reviewer says distinct',
    });
  });

  it('still raises an ApiError when the verdict is rejected', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 409 }));

    const error = (await overridePageVerdict('page-9', 'NOT_BLANK').catch(
      (caught: unknown) => caught,
    )) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('CONFLICT');
  });
});

describe('uploadPackage', () => {
  it('sends every file under the part name the spec declares', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ packageId: 'p', jobId: 'j', files: [], warnings: [] }));

    await uploadPackage([
      new File(['%PDF-1.7'], 'first.pdf', { type: 'application/pdf' }),
      new File(['%PDF-1.7'], 'second.pdf', { type: 'application/pdf' }),
    ]);

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const body = init.body as FormData;
    expect(body.getAll('files')).toHaveLength(2);
    // The browser must set the multipart boundary; a hand-set Content-Type
    // would produce a body the server cannot parse.
    expect(init.headers).not.toHaveProperty('Content-Type');
  });

  it('passes the optional query parameters and idempotency key through', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ packageId: 'p', jobId: 'j', files: [], warnings: [] }));

    await uploadPackage([new File(['x'], 'a.pdf')], {
      loanId: 'loan-9',
      name: 'Smith package',
      idempotencyKey: 'key-1',
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('loanId=loan-9');
    expect(url).toContain('name=Smith+package');
    expect(init.headers).toMatchObject({ 'Idempotency-Key': 'key-1' });
  });
});

describe('error handling', () => {
  it('surfaces the RFC 9457 code', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        {
          type: 'about:blank',
          title: 'PAGE_LIMIT_EXCEEDED',
          status: 422,
          code: 'PAGE_LIMIT_EXCEEDED',
          params: { limit: 500 },
        },
        422,
      ),
    );

    const error = await getJob('abc').catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.code).toBe('PAGE_LIMIT_EXCEEDED');
    expect(apiError.status).toBe(422);
    expect(apiError.params).toEqual({ limit: 500 });
  });

  it('derives a code when a proxy swallows the body', async () => {
    fetchMock.mockResolvedValue(new Response('<html>502</html>', { status: 502 }));

    const error = (await getJob('abc').catch((caught: unknown) => caught)) as ApiError;
    expect(error.code).toBe('INTERNAL');
    expect(error.status).toBe(502);
  });

  it.each([
    [404, 'NOT_FOUND'],
    [409, 'CONFLICT'],
    [413, 'FILE_TOO_LARGE'],
    [400, 'INVALID_REQUEST'],
  ])('derives %s as %s', async (status, code) => {
    fetchMock.mockResolvedValue(new Response('', { status }));
    const error = (await getJob('abc').catch((caught: unknown) => caught)) as ApiError;
    expect(error.code).toBe(code);
  });

  it('turns a transport failure into an ApiError rather than a raw rejection', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    const error = (await getJob('abc').catch((caught: unknown) => caught)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('NETWORK');
    expect(error.status).toBe(0);
  });

  it('never carries a server exception message, because the server never sends one', async () => {
    // GlobalExceptionHandler logs the message and serialises only the code:
    // a message may quote document content. Nothing here may reintroduce it.
    fetchMock.mockResolvedValue(jsonResponse({ code: 'INTERNAL', title: 'INTERNAL' }, 500));

    const error = (await getJob('abc').catch((caught: unknown) => caught)) as ApiError;
    expect(error.message).toBe('INTERNAL (HTTP 500)');
  });
});

import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError, fetchSourceSnippet } from './api';

describe('api error handling', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('parses application/problem+json responses into readable ApiError messages', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: 'about:blank',
            title: 'Source snippet unavailable',
            status: 404,
            detail: 'Source snippet is no longer available or the requested file could not be found.'
          }),
          {
            status: 404,
            headers: { 'content-type': 'application/problem+json' }
          }
        )
      )
    );

    await expect(fetchSourceSnippet('analysis-1', 'src/main/resources/application.properties', 30, 30))
      .rejects.toMatchObject({
        name: 'ApiError',
        status: 404,
        message: 'Request failed (404): Source snippet is no longer available or the requested file could not be found.'
      } satisfies Partial<ApiError>);
  });

  it('does not leak nested raw ProblemDetail JSON when a server sends it as text', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: 'about:blank',
            title: 'Source snippet unavailable',
            status: 404,
            detail: 'Source snippet is no longer available or the requested file could not be found.'
          }),
          {
            status: 404,
            headers: { 'content-type': 'text/plain' }
          }
        )
      )
    );

    await expect(fetchSourceSnippet('analysis-1', 'src/main/resources/application.properties', 30, 30))
      .rejects.toMatchObject({
        status: 404,
        message: 'Request failed (404): Source snippet is no longer available or the requested file could not be found.'
      });
  });
});

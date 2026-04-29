import { describe, expect, it } from 'vitest';

import { expandSnippetHighlightRanges } from './highlightRanges';

describe('expandSnippetHighlightRanges', () => {
  it('expands a single-line fluent Java chain into multiple highlighted lines', () => {
    const ranges = expandSnippetHighlightRanges(
      'java',
      [
        { lineNumber: 10, text: '        resendClient.post()' },
        { lineNumber: 11, text: '            .uri("/emails")' },
        { lineNumber: 12, text: '            .body(payload)' },
        { lineNumber: 13, text: '            .retrieve();' },
        { lineNumber: 14, text: '        otherCall();' }
      ],
      [{ startLine: 10, endLine: 10, kind: 'issue' }]
    );

    expect(ranges[0]).toMatchObject({ startLine: 10, endLine: 13, kind: 'issue' });
  });

  it('does not expand multi-line ranges already supplied by the backend', () => {
    const ranges = expandSnippetHighlightRanges(
      'java',
      [{ lineNumber: 3, text: 'catch (Exception e) {' }, { lineNumber: 4, text: '}' }],
      [{ startLine: 3, endLine: 4, kind: 'issue' }]
    );

    expect(ranges[0]).toMatchObject({ startLine: 3, endLine: 4 });
  });

  it('does not expand non-java snippets', () => {
    const ranges = expandSnippetHighlightRanges(
      'properties',
      [{ lineNumber: 1, text: 'spring.datasource.password=[redacted]' }],
      [{ startLine: 1, endLine: 1, kind: 'issue' }]
    );

    expect(ranges[0]).toMatchObject({ startLine: 1, endLine: 1 });
  });
});

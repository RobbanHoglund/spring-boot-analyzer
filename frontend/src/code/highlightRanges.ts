import type { HighlightRange, SourceSnippetLine } from '../types';

const JAVA_CHAIN_MAX_LINES = 12;
const RELATED_RANGE_KIND = 'related';

export function expandSnippetHighlightRanges(
  language: string | null | undefined,
  lines: SourceSnippetLine[] | undefined,
  ranges: HighlightRange[] | undefined
): HighlightRange[] {
  const normalizedRanges = ranges ?? [];
  if ((language ?? 'text') !== 'java' || normalizedRanges.length === 0 || !lines || lines.length === 0) {
    return normalizedRanges;
  }
  return normalizedRanges.map((range) => expandJavaIssueRange(lines, range));
}

function expandJavaIssueRange(lines: SourceSnippetLine[], range: HighlightRange): HighlightRange {
  const kind = (range.kind ?? 'issue').toLowerCase();
  const startLine = range.startLine ?? 0;
  const endLine = range.endLine ?? startLine;
  if (kind === RELATED_RANGE_KIND || !startLine || endLine > startLine) {
    return range;
  }

  const startIndex = lines.findIndex((line) => line.lineNumber === startLine);
  if (startIndex < 0) {
    return range;
  }

  const baseIndent = indentationWidth(lines[startIndex].text);
  let currentIndex = startIndex;
  while (currentIndex + 1 < lines.length && currentIndex - startIndex + 1 < JAVA_CHAIN_MAX_LINES) {
    const currentTrimmed = lines[currentIndex].text.trimEnd();
    if (endsStatement(currentTrimmed)) {
      break;
    }

    const nextIndex = currentIndex + 1;
    const nextText = lines[nextIndex].text;
    const nextTrimmed = nextText.trim();
    if (!nextTrimmed || isStandaloneComment(nextTrimmed)) {
      break;
    }
    if (!isLikelyContinuation(nextText, nextTrimmed, baseIndent)) {
      break;
    }

    currentIndex = nextIndex;
    if (endsStatement(nextTrimmed)) {
      break;
    }
  }

  if (currentIndex === startIndex) {
    return range;
  }
  return {
    ...range,
    endLine: lines[currentIndex].lineNumber
  };
}

function isLikelyContinuation(rawLine: string, trimmed: string, baseIndent: number): boolean {
  if (trimmed.startsWith('.') || trimmed.startsWith('?.') || trimmed.startsWith('::')) {
    return true;
  }
  if (trimmed.startsWith(')') || trimmed.startsWith(',') || trimmed.startsWith('+') || trimmed.startsWith('?')) {
    return true;
  }

  const indent = indentationWidth(rawLine);
  if (indent <= baseIndent) {
    return false;
  }
  return !startsNewStatement(trimmed);
}

function startsNewStatement(trimmed: string): boolean {
  return /^(?:if|for|while|switch|case|default|return|throw|catch|finally|try|public|private|protected|class|record|enum|interface|@|new\s+\w)/.test(trimmed);
}

function endsStatement(trimmed: string): boolean {
  return trimmed.endsWith(';') || trimmed.endsWith('{') || trimmed.endsWith('}');
}

function indentationWidth(line: string): number {
  const match = line.match(/^\s*/);
  return match?.[0]?.length ?? 0;
}

function isStandaloneComment(trimmed: string): boolean {
  return trimmed.startsWith('//') || trimmed.startsWith('/*') || trimmed.startsWith('*');
}

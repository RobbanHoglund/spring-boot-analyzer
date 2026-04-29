export type JavaTokenType =
  | 'keyword'
  | 'contextual-keyword'
  | 'literal'
  | 'string'
  | 'char'
  | 'comment'
  | 'number'
  | 'annotation'
  | 'type-name'
  | 'method-name'
  | 'operator'
  | 'plain';

export interface JavaToken {
  type: JavaTokenType;
  text: string;
}

export interface TokenizedJavaLine {
  tokens: JavaToken[];
}

interface TokenizerState {
  inBlockComment: boolean;
  inTextBlock: boolean;
}

const JAVA_KEYWORDS = new Set([
  'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const', 'continue',
  'default', 'do', 'double', 'else', 'enum', 'extends', 'final', 'finally', 'float', 'for', 'goto', 'if',
  'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'native', 'new', 'package',
  'private', 'protected', 'public', 'return', 'short', 'static', 'strictfp', 'super', 'switch',
  'synchronized', 'this', 'throw', 'throws', 'transient', 'try', 'void', 'volatile', 'while', '_'
]);

const JAVA_CONTEXTUAL_KEYWORDS = new Set([
  'exports', 'module', 'non-sealed', 'open', 'opens', 'permits', 'provides', 'record', 'requires',
  'sealed', 'to', 'transitive', 'uses', 'var', 'when', 'with', 'yield'
]);

const JAVA_LITERAL_KEYWORDS = new Set(['true', 'false', 'null']);

export function tokenizeJavaLines(lines: string[]): TokenizedJavaLine[] {
  const state: TokenizerState = {
    inBlockComment: false,
    inTextBlock: false
  };
  return lines.map((line) => tokenizeJavaLine(line, state));
}

function tokenizeJavaLine(line: string, state: TokenizerState): TokenizedJavaLine {
  const tokens: JavaToken[] = [];
  let index = 0;

  while (index < line.length) {
    if (state.inBlockComment) {
      const closeIndex = line.indexOf('*/', index);
      if (closeIndex < 0) {
        tokens.push({ type: 'comment', text: line.slice(index) });
        index = line.length;
        continue;
      }
      tokens.push({ type: 'comment', text: line.slice(index, closeIndex + 2) });
      index = closeIndex + 2;
      state.inBlockComment = false;
      continue;
    }

    if (state.inTextBlock) {
      const closeIndex = line.indexOf('"""', index);
      if (closeIndex < 0) {
        tokens.push({ type: 'string', text: line.slice(index) });
        index = line.length;
        continue;
      }
      tokens.push({ type: 'string', text: line.slice(index, closeIndex + 3) });
      index = closeIndex + 3;
      state.inTextBlock = false;
      continue;
    }

    if (line.startsWith('//', index)) {
      tokens.push({ type: 'comment', text: line.slice(index) });
      break;
    }

    if (line.startsWith('/*', index)) {
      const closeIndex = line.indexOf('*/', index + 2);
      if (closeIndex < 0) {
        tokens.push({ type: 'comment', text: line.slice(index) });
        state.inBlockComment = true;
        break;
      }
      tokens.push({ type: 'comment', text: line.slice(index, closeIndex + 2) });
      index = closeIndex + 2;
      continue;
    }

    if (line.startsWith('"""', index)) {
      const closeIndex = line.indexOf('"""', index + 3);
      if (closeIndex < 0) {
        tokens.push({ type: 'string', text: line.slice(index) });
        state.inTextBlock = true;
        break;
      }
      tokens.push({ type: 'string', text: line.slice(index, closeIndex + 3) });
      index = closeIndex + 3;
      continue;
    }

    const current = line[index];

    if (current === '"') {
      const endIndex = findStringEnd(line, index + 1, '"');
      tokens.push({ type: 'string', text: line.slice(index, endIndex) });
      index = endIndex;
      continue;
    }

    if (current === '\'') {
      const endIndex = findStringEnd(line, index + 1, '\'');
      tokens.push({ type: 'char', text: line.slice(index, endIndex) });
      index = endIndex;
      continue;
    }

    if (current === '@') {
      const endIndex = consumeAnnotation(line, index + 1);
      tokens.push({ type: 'annotation', text: line.slice(index, endIndex) });
      index = endIndex;
      continue;
    }

    if (isIdentifierStart(current)) {
      const nonSealed = tryConsumeNonSealed(line, index);
      if (nonSealed > index) {
        tokens.push({ type: 'contextual-keyword', text: line.slice(index, nonSealed) });
        index = nonSealed;
        continue;
      }
      const endIndex = consumeIdentifier(line, index + 1);
      const value = line.slice(index, endIndex);
      if (JAVA_KEYWORDS.has(value)) {
        tokens.push({ type: 'keyword', text: value });
      } else if (JAVA_CONTEXTUAL_KEYWORDS.has(value)) {
        tokens.push({ type: 'contextual-keyword', text: value });
      } else if (JAVA_LITERAL_KEYWORDS.has(value)) {
        tokens.push({ type: 'literal', text: value });
      } else if (looksLikeMethodCall(line, endIndex)) {
        tokens.push({ type: 'method-name', text: value });
      } else if (looksLikeTypeName(value)) {
        tokens.push({ type: 'type-name', text: value });
      } else {
        tokens.push({ type: 'plain', text: value });
      }
      index = endIndex;
      continue;
    }

    if (isDigit(current)) {
      const endIndex = consumeNumber(line, index + 1);
      tokens.push({ type: 'number', text: line.slice(index, endIndex) });
      index = endIndex;
      continue;
    }

    const operatorEnd = consumeOperator(line, index);
    if (operatorEnd > index + 1 || isOperatorChar(current)) {
      tokens.push({ type: 'operator', text: line.slice(index, operatorEnd) });
      index = operatorEnd;
      continue;
    }

    tokens.push({ type: 'plain', text: current });
    index += 1;
  }

  return { tokens };
}

function findStringEnd(line: string, startIndex: number, quote: '"' | '\''): number {
  let escaped = false;
  for (let index = startIndex; index < line.length; index++) {
    const current = line[index];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (current === '\\') {
      escaped = true;
      continue;
    }
    if (current === quote) {
      return index + 1;
    }
  }
  return line.length;
}

function consumeAnnotation(line: string, startIndex: number): number {
  let index = startIndex;
  while (index < line.length) {
    const current = line[index];
    if (isIdentifierPart(current) || current === '.') {
      index += 1;
      continue;
    }
    break;
  }
  return index;
}

function tryConsumeNonSealed(line: string, index: number): number {
  const token = 'non-sealed';
  if (!line.startsWith(token, index)) {
    return index;
  }
  const before = index === 0 ? '' : line[index - 1];
  const after = index + token.length >= line.length ? '' : line[index + token.length];
  if ((before && isIdentifierPart(before)) || (after && isIdentifierPart(after))) {
    return index;
  }
  return index + token.length;
}

function consumeIdentifier(line: string, startIndex: number): number {
  let index = startIndex;
  while (index < line.length && isIdentifierPart(line[index])) {
    index += 1;
  }
  return index;
}

function consumeNumber(line: string, startIndex: number): number {
  let index = startIndex;
  while (index < line.length) {
    const current = line[index];
    if (isDigit(current) || current === '_' || current === '.' || current === 'x' || current === 'X'
      || current === 'b' || current === 'B'
      || current === 'l' || current === 'L'
      || current === 'f' || current === 'F'
      || current === 'd' || current === 'D'
      || current === 'e' || current === 'E'
      || current === '+' || current === '-') {
      index += 1;
      continue;
    }
    break;
  }
  return index;
}

function consumeOperator(line: string, index: number): number {
  if (index + 1 < line.length) {
    const pair = line.slice(index, index + 2);
    if (['->', '::', '==', '!=', '>=', '<=', '&&', '||', '+=', '-=', '*=', '/=', '%=', '++', '--'].includes(pair)) {
      return index + 2;
    }
  }
  return index + 1;
}

function isIdentifierStart(value: string): boolean {
  return /[A-Za-z_$]/.test(value);
}

function isIdentifierPart(value: string): boolean {
  return /[A-Za-z0-9_$]/.test(value);
}

function isDigit(value: string): boolean {
  return /[0-9]/.test(value);
}

function isOperatorChar(value: string): boolean {
  return /[=+\-*/%<>!&|^~?:;,.()[\]{}]/.test(value);
}

function looksLikeTypeName(value: string): boolean {
  return /^[A-Z][A-Za-z0-9_$]*$/.test(value);
}

function looksLikeMethodCall(line: string, startIndex: number): boolean {
  let index = startIndex;
  while (index < line.length && /\s/.test(line[index])) {
    index += 1;
  }
  return line[index] === '(';
}

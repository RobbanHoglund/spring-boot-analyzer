import { describe, expect, it } from 'vitest';

import { tokenizeJavaLines } from './javaHighlighter';

function tokenTypes(line: string): Array<string> {
  return tokenizeJavaLines([line])[0].tokens.map((token) => `${token.type}:${token.text}`);
}

describe('tokenizeJavaLines', () => {
  it('highlights public class Foo', () => {
    expect(tokenTypes('public class Foo {')).toContain('keyword:public');
    expect(tokenTypes('public class Foo {')).toContain('keyword:class');
  });

  it('does not highlight class inside classification', () => {
    expect(tokenTypes('String classification = value;')).not.toContain('keyword:class');
  });

  it('does not highlight keywords inside line comments', () => {
    expect(tokenTypes('// if else catch')[0]).toBe('comment:// if else catch');
  });

  it('does not highlight keywords inside block comments across lines', () => {
    const tokenized = tokenizeJavaLines(['/* if', 'else return */', 'class Demo {}']);
    expect(tokenized[0].tokens[0].type).toBe('comment');
    expect(tokenized[1].tokens[0].type).toBe('comment');
    expect(tokenized[2].tokens.some((token) => token.type === 'keyword' && token.text === 'class')).toBe(true);
  });

  it('does not highlight keywords inside strings and chars', () => {
    expect(tokenTypes('String value = "return class";').some((token) => token === 'keyword:return')).toBe(false);
    expect(tokenTypes("char value = 'c';").some((token) => token === 'keyword:char')).toBe(true);
    expect(tokenTypes("String value = \"return class\";").some((token) => token.startsWith('keyword:'))).toBe(false);
  });

  it('does not highlight keywords inside text blocks', () => {
    const tokenized = tokenizeJavaLines(['String value = """', 'if return class', '""";']);
    expect(tokenized[1].tokens.every((token) => token.type === 'string')).toBe(true);
  });

  it('highlights contextual keywords and literals', () => {
    const line = 'record Demo(var value) permits Foo when true yield null false sealed';
    const types = tokenTypes(line);
    expect(types).toContain('contextual-keyword:record');
    expect(types).toContain('contextual-keyword:var');
    expect(types).toContain('contextual-keyword:permits');
    expect(types).toContain('contextual-keyword:when');
    expect(types).toContain('contextual-keyword:yield');
    expect(types).toContain('contextual-keyword:sealed');
    expect(types).toContain('literal:true');
    expect(types).toContain('literal:false');
    expect(types).toContain('literal:null');
  });

  it('highlights type names and method calls where safe', () => {
    const types = tokenTypes('ResponseEntity.status(500).body("oops")');
    expect(types).toContain('type-name:ResponseEntity');
    expect(types).toContain('method-name:status');
    expect(types).toContain('number:500');
  });
});

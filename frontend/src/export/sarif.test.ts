import { describe, expect, it } from 'vitest';
import type { AnalyzeRepositoryResponse, Finding } from '../types';
import { sarifToJson, toSarif } from './sarif';

function baseFinding(overrides: Partial<Finding> = {}): Finding {
  return {
    severity: 'WARNING',
    ruleId: 'JAVA_EMPTY_CATCH_BLOCK',
    title: 'Empty catch block',
    message: 'Exception is caught but the catch block is empty.',
    category: 'EXCEPTION_HANDLING',
    confidence: 'HIGH',
    runtimeDetection: 'NOT_NORMALLY_DETECTED',
    whyBadPractice: 'Silently swallowed exceptions hide failures.',
    possibleImpact: 'Hidden bugs and hard-to-diagnose failures.',
    recommendation: 'Log the exception or rethrow it.',
    evidence: 'Catch block at line 3 contains no statements.',
    sourceFile: 'src/main/java/com/example/Demo.java',
    line: 3,
    target: 'Demo#run',
    primaryLocation: {
      filePath: 'src/main/java/com/example/Demo.java',
      startLine: 3,
      endLine: 5,
      language: 'java'
    },
    highlightRanges: [],
    occurrences: [],
    relatedSignals: [],
    ...overrides
  };
}

function baseResponse(findings: Finding[] = []): AnalyzeRepositoryResponse {
  return {
    repositoryUrl: 'https://github.com/example/demo.git',
    branch: 'main',
    commitSha: 'abc1234567890',
    workspaceId: 'ws-001',
    analysisId: 'ws-001',
    findings
  };
}

describe('toSarif', () => {
  it('produces a valid SARIF 2.1.0 envelope', () => {
    const doc = toSarif(baseResponse());
    expect(doc.version).toBe('2.1.0');
    expect(doc.$schema).toContain('sarif-schema-2.1.0.json');
    expect(doc.runs).toHaveLength(1);
  });

  it('sets the tool driver name and informationUri', () => {
    const { runs } = toSarif(baseResponse());
    expect(runs[0].tool.driver.name).toBe('Spring Boot Analyzer');
    expect(runs[0].tool.driver.informationUri).toContain('github.com');
  });

  it('emits version control provenance from repositoryUrl, commitSha, and branch', () => {
    const { runs } = toSarif(baseResponse());
    const vcp = runs[0].versionControlProvenance ?? [];
    expect(vcp).toHaveLength(1);
    expect(vcp[0].repositoryUri).toBe('https://github.com/example/demo.git');
    expect(vcp[0].revisionId).toBe('abc1234567890');
    expect(vcp[0].branch).toBe('main');
  });

  it('omits versionControlProvenance when repositoryUrl is absent', () => {
    const response = baseResponse();
    delete response.repositoryUrl;
    const { runs } = toSarif(response);
    expect(runs[0].versionControlProvenance).toBeUndefined();
  });

  it('maps WARNING severity to SARIF level "warning"', () => {
    const { runs } = toSarif(baseResponse([baseFinding({ severity: 'WARNING' })]));
    expect(runs[0].results[0].level).toBe('warning');
  });

  it('maps ERROR severity to SARIF level "error"', () => {
    const { runs } = toSarif(baseResponse([baseFinding({ severity: 'ERROR' })]));
    expect(runs[0].results[0].level).toBe('error');
  });

  it('maps INFO severity to SARIF level "note"', () => {
    const { runs } = toSarif(baseResponse([baseFinding({ severity: 'INFO' })]));
    expect(runs[0].results[0].level).toBe('note');
  });

  it('de-duplicates rules — one rule entry per unique ruleId', () => {
    const findings = [
      baseFinding({ ruleId: 'JAVA_EMPTY_CATCH_BLOCK', target: 'A#run' }),
      baseFinding({ ruleId: 'JAVA_EMPTY_CATCH_BLOCK', target: 'B#run' }),
      baseFinding({ ruleId: 'SPRING_FIELD_INJECTION', title: 'Field injection', target: 'C#field' })
    ];
    const { runs } = toSarif(baseResponse(findings));
    expect(runs[0].tool.driver.rules).toHaveLength(2);
    expect(runs[0].results).toHaveLength(3);
  });

  it('emits a result for every finding including structural ones without ruleId', () => {
    const structural: Finding = {
      severity: 'WARNING',
      message: 'Component outside main package.',
      location: 'src/main/java/com/example/bad/Bad.java',
      sourceFile: 'src/main/java/com/example/bad/Bad.java',
      highlightRanges: [],
      occurrences: [],
      relatedSignals: []
    };
    const { runs } = toSarif(baseResponse([structural]));
    expect(runs[0].results).toHaveLength(1);
    expect(runs[0].results[0].ruleId).toBe('UNKNOWN');
  });

  it('builds a physical location from primaryLocation when present', () => {
    const { runs } = toSarif(baseResponse([baseFinding()]));
    const loc = runs[0].results[0].locations?.[0]?.physicalLocation;
    expect(loc?.artifactLocation.uri).toBe('src/main/java/com/example/Demo.java');
    expect(loc?.artifactLocation.uriBaseId).toBe('%SRCROOT%');
    expect(loc?.region?.startLine).toBe(3);
    expect(loc?.region?.endLine).toBe(5);
  });

  it('falls back to sourceFile + line when primaryLocation is absent', () => {
    const finding = baseFinding({ primaryLocation: undefined, sourceFile: 'src/Foo.java', line: 10 });
    const { runs } = toSarif(baseResponse([finding]));
    const loc = runs[0].results[0].locations?.[0]?.physicalLocation;
    expect(loc?.artifactLocation.uri).toBe('src/Foo.java');
    expect(loc?.region?.startLine).toBe(10);
  });

  it('normalises backslash paths to forward slashes', () => {
    const finding = baseFinding({
      primaryLocation: {
        filePath: 'src\\main\\java\\com\\example\\Demo.java',
        startLine: 1,
        endLine: 1,
        language: 'java'
      }
    });
    const { runs } = toSarif(baseResponse([finding]));
    const uri = runs[0].results[0].locations?.[0]?.physicalLocation?.artifactLocation.uri;
    expect(uri).not.toContain('\\');
    expect(uri).toBe('src/main/java/com/example/Demo.java');
  });

  it('includes target as a logical location', () => {
    const { runs } = toSarif(baseResponse([baseFinding()]));
    const logicalLocs = runs[0].results[0].locations?.[0]?.logicalLocations;
    expect(logicalLocs?.[0]?.name).toBe('Demo#run');
    expect(logicalLocs?.[0]?.kind).toBe('member');
  });

  it('emits occurrences as relatedLocations', () => {
    const finding = baseFinding({
      occurrences: [
        {
          message: 'Occurrence at B',
          location: {
            filePath: 'src/main/java/com/example/B.java',
            startLine: 20,
            endLine: 22,
            language: 'java'
          }
        }
      ]
    });
    const { runs } = toSarif(baseResponse([finding]));
    const related = runs[0].results[0].relatedLocations ?? [];
    expect(related).toHaveLength(1);
    expect(related[0].id).toBe(1);
    expect(related[0].physicalLocation?.artifactLocation.uri).toBe('src/main/java/com/example/B.java');
  });

  it('includes confidence, runtimeDetection, category, and possibleImpact in result properties', () => {
    const { runs } = toSarif(baseResponse([baseFinding()]));
    const props = runs[0].results[0].properties ?? {};
    expect(props['confidence']).toBe('HIGH');
    expect(props['runtimeDetection']).toBe('NOT_NORMALLY_DETECTED');
    expect(props['category']).toBe('EXCEPTION_HANDLING');
    expect(props['possibleImpact']).toBeDefined();
  });

  it('includes rule tags for category and confidence', () => {
    const { runs } = toSarif(baseResponse([baseFinding()]));
    const rule = runs[0].tool.driver.rules[0];
    expect(rule.properties?.tags).toContain('EXCEPTION_HANDLING');
    expect(rule.properties?.tags).toContain('confidence:HIGH');
  });

  it('omits relatedLocations when no occurrences are present', () => {
    const { runs } = toSarif(baseResponse([baseFinding({ occurrences: [] })]));
    expect(runs[0].results[0].relatedLocations).toBeUndefined();
  });

  it('handles a response with no findings gracefully', () => {
    const doc = toSarif(baseResponse([]));
    expect(doc.runs[0].results).toHaveLength(0);
    expect(doc.runs[0].tool.driver.rules).toHaveLength(0);
  });
});

describe('sarifToJson', () => {
  it('returns valid JSON that round-trips to the same structure', () => {
    const response = baseResponse([baseFinding()]);
    const json = sarifToJson(response);
    const parsed = JSON.parse(json) as ReturnType<typeof toSarif>;
    expect(parsed.version).toBe('2.1.0');
    expect(parsed.runs[0].results).toHaveLength(1);
  });

  it('produces pretty-printed JSON (indented with 2 spaces)', () => {
    const json = sarifToJson(baseResponse([baseFinding()]));
    expect(json).toContain('\n  ');
  });
});

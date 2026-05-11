/**
 * SARIF 2.1.0 export for Spring Boot Analyzer findings.
 *
 * Converts an AnalyzeRepositoryResponse into a minimal but spec-compliant
 * SARIF 2.1.0 document. Only fields that downstream tools (GitHub Code
 * Scanning, VS Code SARIF viewer, Azure DevOps) reliably consume are
 * populated; optional extensions are omitted to keep the output lean.
 *
 * Reference: https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html
 */

import type { AnalyzeRepositoryResponse, Finding } from '../types';

// ---------------------------------------------------------------------------
// Minimal SARIF 2.1.0 type surface (only what we produce)
// ---------------------------------------------------------------------------

export interface SarifDocument {
  $schema: string;
  version: '2.1.0';
  runs: SarifRun[];
}

interface SarifRun {
  tool: SarifTool;
  results: SarifResult[];
  artifacts?: SarifArtifact[];
  versionControlProvenance?: SarifVersionControlDetails[];
}

interface SarifTool {
  driver: SarifToolComponent;
}

interface SarifToolComponent {
  name: string;
  version?: string;
  informationUri?: string;
  rules: SarifReportingDescriptor[];
}

interface SarifReportingDescriptor {
  id: string;
  name?: string;
  shortDescription?: SarifMessage;
  fullDescription?: SarifMessage;
  helpUri?: string;
  defaultConfiguration?: { level: SarifLevel };
  properties?: { tags?: string[]; [key: string]: unknown };
}

interface SarifResult {
  ruleId: string;
  level: SarifLevel;
  message: SarifMessage;
  locations?: SarifLocation[];
  relatedLocations?: SarifRelatedLocation[];
  properties?: { [key: string]: unknown };
}

interface SarifLocation {
  physicalLocation?: SarifPhysicalLocation;
  logicalLocations?: SarifLogicalLocation[];
}

interface SarifRelatedLocation {
  id: number;
  message?: SarifMessage;
  physicalLocation?: SarifPhysicalLocation;
}

interface SarifPhysicalLocation {
  artifactLocation: SarifArtifactLocation;
  region?: SarifRegion;
}

interface SarifArtifactLocation {
  uri: string;
  uriBaseId?: string;
}

interface SarifRegion {
  startLine: number;
  endLine?: number;
  startColumn?: number;
  endColumn?: number;
}

interface SarifLogicalLocation {
  name?: string;
  kind?: string;
}

interface SarifArtifact {
  location: SarifArtifactLocation;
  length?: number;
}

interface SarifVersionControlDetails {
  repositoryUri: string;
  revisionId?: string;
  branch?: string;
}

interface SarifMessage {
  text: string;
}

type SarifLevel = 'error' | 'warning' | 'note' | 'none';

// ---------------------------------------------------------------------------
// Conversion helpers
// ---------------------------------------------------------------------------

function toSarifLevel(severity: string | null | undefined): SarifLevel {
  switch ((severity ?? '').toUpperCase()) {
    case 'ERROR':
      return 'error';
    case 'WARNING':
      return 'warning';
    case 'INFO':
      return 'note';
    default:
      return 'none';
  }
}

function buildRule(finding: Finding): SarifReportingDescriptor {
  const ruleId = finding.ruleId ?? finding.rule ?? 'UNKNOWN';
  const tags: string[] = [];
  if (finding.category) tags.push(finding.category);
  if (finding.confidence) tags.push(`confidence:${finding.confidence}`);
  if (finding.runtimeDetection) tags.push(`runtimeDetection:${finding.runtimeDetection}`);

  const descriptor: SarifReportingDescriptor = {
    id: ruleId,
    name: ruleId
      .split('_')
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
      .join(''),
    shortDescription: { text: finding.title ?? finding.shortMessage ?? finding.message ?? ruleId },
    defaultConfiguration: { level: toSarifLevel(finding.severity) }
  };
  if (finding.whyBadPractice) {
    descriptor.fullDescription = { text: finding.whyBadPractice };
  }
  if (tags.length > 0) {
    descriptor.properties = { tags };
  }
  return descriptor;
}

function buildPhysicalLocation(
  filePath: string | null | undefined,
  startLine: number | null | undefined,
  endLine: number | null | undefined
): SarifPhysicalLocation | undefined {
  if (!filePath) return undefined;
  const loc: SarifPhysicalLocation = {
    artifactLocation: { uri: filePath.replace(/\\/g, '/'), uriBaseId: '%SRCROOT%' }
  };
  if (startLine != null && startLine > 0) {
    loc.region = { startLine };
    if (endLine != null && endLine >= startLine) {
      loc.region.endLine = endLine;
    }
  }
  return loc;
}

function buildResult(finding: Finding): SarifResult {
  const ruleId = finding.ruleId ?? finding.rule ?? 'UNKNOWN';
  const messageText = [
    finding.message ?? finding.title ?? ruleId,
    finding.recommendation ? `Recommendation: ${finding.recommendation}` : null,
    finding.evidence ? `Evidence: ${finding.evidence}` : null
  ]
    .filter(Boolean)
    .join('\n\n');

  const result: SarifResult = {
    ruleId,
    level: toSarifLevel(finding.severity),
    message: { text: messageText },
    properties: {}
  };

  // Primary location
  const primaryFile = finding.primaryLocation?.filePath ?? finding.sourceFile;
  const primaryStart = finding.primaryLocation?.startLine ?? finding.line;
  const primaryEnd = finding.primaryLocation?.endLine ?? finding.line;
  const physLoc = buildPhysicalLocation(primaryFile, primaryStart, primaryEnd);
  if (physLoc) {
    const loc: SarifLocation = { physicalLocation: physLoc };
    if (finding.target) {
      loc.logicalLocations = [{ name: finding.target, kind: 'member' }];
    }
    result.locations = [loc];
  }

  // Additional occurrences as related locations
  if (finding.occurrences && finding.occurrences.length > 0) {
    result.relatedLocations = finding.occurrences
      .map((occ, idx) => {
        const occPhys = buildPhysicalLocation(
          occ.location?.filePath,
          occ.location?.startLine,
          occ.location?.endLine
        );
        if (!occPhys) return null;
        return {
          id: idx + 1,
          message: occ.message ? { text: occ.message } : undefined,
          physicalLocation: occPhys
        } as SarifRelatedLocation;
      })
      .filter((r): r is SarifRelatedLocation => r !== null);
  }

  // Extra metadata in properties
  if (result.properties != null) {
    if (finding.confidence) result.properties['confidence'] = finding.confidence;
    if (finding.runtimeDetection) result.properties['runtimeDetection'] = finding.runtimeDetection;
    if (finding.category) result.properties['category'] = finding.category;
    if (finding.possibleImpact) result.properties['possibleImpact'] = finding.possibleImpact;
  }

  return result;
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Converts an AnalyzeRepositoryResponse to a SARIF 2.1.0 document.
 *
 * Rules are de-duplicated: one entry per unique ruleId, using the first
 * occurrence's metadata. Results are emitted in the order findings arrive.
 * Findings without a ruleId (structural warnings) use the ruleId "UNKNOWN"
 * and are still included so the output is complete.
 */
export function toSarif(response: AnalyzeRepositoryResponse): SarifDocument {
  const findings = response.findings ?? [];

  // Build de-duplicated rule set
  const ruleMap = new Map<string, SarifReportingDescriptor>();
  for (const finding of findings) {
    const ruleId = finding.ruleId ?? finding.rule ?? 'UNKNOWN';
    if (!ruleMap.has(ruleId)) {
      ruleMap.set(ruleId, buildRule(finding));
    }
  }

  const run: SarifRun = {
    tool: {
      driver: {
        name: 'Spring Boot Analyzer',
        informationUri: 'https://github.com/RobbanHoglund/spring-boot-analyzer',
        rules: Array.from(ruleMap.values())
      }
    },
    results: findings.map(buildResult)
  };

  // Version control provenance
  if (response.repositoryUrl) {
    run.versionControlProvenance = [
      {
        repositoryUri: response.repositoryUrl,
        ...(response.commitSha ? { revisionId: response.commitSha } : {}),
        ...(response.branch ? { branch: response.branch } : {})
      }
    ];
  }

  return {
    $schema: 'https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Documents/CommitteeSpecifications/2.1.0/sarif-schema-2.1.0.json',
    version: '2.1.0',
    runs: [run]
  };
}

/**
 * Serialises the SARIF document to a pretty-printed JSON string.
 */
export function sarifToJson(response: AnalyzeRepositoryResponse): string {
  return JSON.stringify(toSarif(response), null, 2);
}

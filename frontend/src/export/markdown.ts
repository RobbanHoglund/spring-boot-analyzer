/**
 * Markdown report export for Spring Boot Analyzer findings.
 *
 * Produces a structured, fully self-contained Markdown document that
 * renders well on GitHub, GitLab, Jira/Confluence, Notion, and
 * standard Markdown editors. The report includes:
 *
 *  - Repository / runtime metadata header
 *  - Summary table (severity counts)
 *  - Findings grouped by severity (Errors → Warnings → Info)
 *  - Per-finding: rule ID, title, file link, category, confidence,
 *    full body (why/impact/recommendation/evidence), and occurrence list
 */

import type { AnalyzeRepositoryResponse, Finding } from '../types';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function esc(text: string | null | undefined): string {
  // Escape pipe characters that would break Markdown tables
  return (text ?? '').replace(/\|/g, '\\|');
}

function severityIcon(severity: string | null | undefined): string {
  switch ((severity ?? '').toUpperCase()) {
    case 'ERROR':   return '🔴';
    case 'WARNING': return '🟡';
    case 'INFO':    return '🔵';
    default:        return '⚪';
  }
}

function severityLabel(severity: string | null | undefined): string {
  const s = (severity ?? '').toUpperCase();
  if (s === 'ERROR')   return 'Error';
  if (s === 'WARNING') return 'Warning';
  if (s === 'INFO')    return 'Info';
  return severity ?? 'Unknown';
}

function fileRef(finding: Finding): string {
  const loc = finding.primaryLocation;
  const path = loc?.filePath ?? finding.sourceFile;
  const line = loc?.startLine ?? finding.line ?? null;
  const url  = loc?.githubUrl;

  if (!path) return '—';
  const label = line ? `${path}:${line}` : path;
  return url ? `[\`${label}\`](${url})` : `\`${label}\``;
}

function occurrenceRef(occ: NonNullable<Finding['occurrences']>[number]): string {
  const path = occ.location?.filePath;
  const line = occ.location?.startLine;
  const url  = occ.location?.githubUrl;
  if (!path) return '—';
  const label = line ? `${path}:${line}` : path;
  return url ? `[\`${label}\`](${url})` : `\`${label}\``;
}

function shortSha(sha: string | null | undefined): string {
  return sha ? sha.slice(0, 7) : '';
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

// ---------------------------------------------------------------------------
// Finding block
// ---------------------------------------------------------------------------

function renderFinding(finding: Finding, index: number): string {
  const ruleId  = finding.ruleId ?? finding.rule ?? 'UNKNOWN';
  const title   = finding.title ?? finding.message ?? ruleId;
  const lines: string[] = [];

  // Heading — anchored, linkable
  lines.push(`### ${index + 1}. \`${ruleId}\` — ${title}`);
  lines.push('');

  // Metadata table
  const metaRows: [string, string][] = [
    ['File', fileRef(finding)],
    ['Category', esc(finding.category) || '—'],
    ['Confidence', esc(finding.confidence) || '—'],
  ];
  if (finding.target) metaRows.push(['Target', `\`${esc(finding.target)}\``]);

  lines.push('| | |');
  lines.push('|---|---|');
  for (const [label, value] of metaRows) {
    lines.push(`| **${label}** | ${value} |`);
  }
  lines.push('');

  // Short message / description
  const desc = finding.shortMessage ?? finding.message;
  if (desc) {
    lines.push(`> ${desc}`);
    lines.push('');
  }

  // Body sections
  if (finding.whyBadPractice) {
    lines.push(`**Why it matters:** ${finding.whyBadPractice}`);
    lines.push('');
  }
  if (finding.possibleImpact) {
    lines.push(`**Possible impact:** ${finding.possibleImpact}`);
    lines.push('');
  }
  if (finding.recommendation) {
    lines.push(`**Recommendation:** ${finding.recommendation}`);
    lines.push('');
  }
  if (finding.evidence) {
    lines.push(`**Evidence:** ${finding.evidence}`);
    lines.push('');
  }
  if (finding.limitations) {
    lines.push(`**Limitations:** *${finding.limitations}*`);
    lines.push('');
  }

  // Multiple occurrences
  const occurrences = finding.occurrences ?? [];
  if (occurrences.length > 1) {
    lines.push(`**Occurrences (${occurrences.length}):**`);
    lines.push('');
    for (const occ of occurrences) {
      const msg = occ.message ? ` — ${occ.message}` : '';
      lines.push(`- ${occurrenceRef(occ)}${msg}`);
    }
    lines.push('');
  }

  lines.push('---');
  lines.push('');

  return lines.join('\n');
}

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

export function generateMarkdown(result: AnalyzeRepositoryResponse): string {
  const findings = result.findings ?? [];
  const rsa      = result.runtimeStackAnalysis;
  const sha      = shortSha(result.commitSha);

  const errors   = findings.filter((f) => (f.severity ?? '').toUpperCase() === 'ERROR');
  const warnings = findings.filter((f) => (f.severity ?? '').toUpperCase() === 'WARNING');
  const infos    = findings.filter((f) => (f.severity ?? '').toUpperCase() === 'INFO');

  const repoName = (() => {
    const url = result.repositoryUrl ?? '';
    const m = /github\.com\/([^/]+\/[^/.]+)/.exec(url);
    return m ? m[1] : url || 'Repository';
  })();

  const lines: string[] = [];

  // ── Title ────────────────────────────────────────────────────────────────
  lines.push(`# Spring Boot Analyzer — ${repoName}`);
  lines.push('');

  // ── Metadata ─────────────────────────────────────────────────────────────
  const repoUrl = result.repositoryUrl;
  lines.push(repoUrl
    ? `**Repository:** [${repoUrl}](${repoUrl})  `
    : `**Repository:** ${repoName}  `);
  lines.push(`**Branch:** \`${result.branch ?? 'default'}\`` +
    (sha ? ` · **Commit:** [\`${sha}\`](${repoUrl?.replace(/\.git$/, '')}/commit/${result.commitSha})` : '') +
    '  ');
  if (rsa?.springBootVersion) {
    const src = rsa.springBootVersionSource ? ` *(${rsa.springBootVersionSource})*` : '';
    lines.push(`**Spring Boot:** ${rsa.springBootVersion}${src}  `);
  }
  if (rsa?.javaVersion) {
    lines.push(`**Java:** ${rsa.javaVersion}  `);
  }
  lines.push(`**Analyzed:** ${today()}  `);
  lines.push('');
  lines.push('---');
  lines.push('');

  // ── Summary ───────────────────────────────────────────────────────────────
  lines.push('## Summary');
  lines.push('');
  lines.push('| Severity | Count |');
  lines.push('|----------|------:|');
  lines.push(`| ${severityIcon('ERROR')} Error | ${errors.length} |`);
  lines.push(`| ${severityIcon('WARNING')} Warning | ${warnings.length} |`);
  lines.push(`| ${severityIcon('INFO')} Info | ${infos.length} |`);
  lines.push(`| **Total** | **${findings.length}** |`);
  lines.push('');

  const suppressedRuleIds = result.suppressedRuleIds ?? [];
  const unknownSuppressedRuleIds = result.unknownSuppressedRuleIds ?? [];
  const suppressedFindingCount = result.suppressedFindingCount ?? 0;
  if (suppressedRuleIds.length > 0
      || unknownSuppressedRuleIds.length > 0
      || suppressedFindingCount > 0) {
    lines.push('> **Repository suppressions:** '
      + `${suppressedFindingCount} finding(s) removed by \`.analyzer-suppress.yml\`. `
      + `Valid rule IDs: ${suppressedRuleIds.map((id) => `\`${id}\``).join(', ') || 'none'}.`);
    if (unknownSuppressedRuleIds.length > 0) {
      lines.push(`> Unknown rule IDs ignored: ${unknownSuppressedRuleIds.map((id) => `\`${id}\``).join(', ')}.`);
    }
    lines.push('');
  }

  if (findings.length === 0) {
    lines.push('*No findings detected.*');
    lines.push('');
    lines.push('---');
    lines.push('');
    lines.push(`*Generated by [Spring Boot Analyzer](https://github.com/RobbanHoglund/spring-boot-analyzer)*`);
    return lines.join('\n');
  }

  lines.push('---');
  lines.push('');

  // ── Findings by severity ──────────────────────────────────────────────────
  const groups: { label: string; icon: string; items: Finding[] }[] = [
    { label: 'Errors',   icon: severityIcon('ERROR'),   items: errors   },
    { label: 'Warnings', icon: severityIcon('WARNING'), items: warnings },
    { label: 'Info',     icon: severityIcon('INFO'),    items: infos    },
  ];

  // Running index across all severity groups for numbered headings
  let globalIndex = 0;

  for (const group of groups) {
    if (group.items.length === 0) continue;

    lines.push(`## ${group.icon} ${group.label} (${group.items.length})`);
    lines.push('');

    for (const finding of group.items) {
      lines.push(renderFinding(finding, globalIndex));
      globalIndex++;
    }
  }

  // ── Footer ────────────────────────────────────────────────────────────────
  lines.push(`*Generated by [Spring Boot Analyzer](https://github.com/RobbanHoglund/spring-boot-analyzer)*`);

  return lines.join('\n');
}

import { element } from '../dom';
import { tokenizeJavaLines } from '../code/javaHighlighter';
import { expandSnippetHighlightRanges } from '../code/highlightRanges';
import type {
  ActuatorEndpointExposure,
  AnalyzeRepositoryResponse,
  ApplicationProperty,
  ConfiguredUrl,
  DetectedClass,
  Finding,
  FindingOccurrence,
  GradleConfigurationModel,
  GradleDependencyConflict,
  GradleDependencyModel,
  GradleJavaToolchainModel,
  GradleModelAnalysis,
  GradlePluginBridgeFailure,
  GradlePluginDeclaration,
  GradlePluginResolutionFailure,
  GradlePluginResolutionBridgeResult,
  GradlePluginModel,
  GradleRepositoryModel,
  GradleResolvedDependencyModel,
  GradleResolutionResult,
  ResolvedGradlePlugin,
  GradleSettingsPluginModel,
  GradleSettingsPluginWorkaround,
  GradleSourceSetModel,
  GradleTaskModel,
  HttpSurfaceAnalysis,
  HighlightRange,
  InboundEndpoint,
  OutboundEndpoint,
  PropertyReference,
  RuntimeStackAnalysis,
  SourceLocation,
  SourceSnippetResponse
} from '../types';

const FINDING_SEVERITIES = ['ALL', 'ERROR', 'WARNING', 'INFO'] as const;
const FINDING_CATEGORIES = [
  'ALL',
  'SECURITY',
  'CONFIGURATION',
  'PROFILE_DRIFT',
  'HTTP',
  'STARTUP',
  'SCHEDULING',
  'PERSISTENCE',
  'TRANSACTION',
  'EXCEPTION_HANDLING',
  'VALIDATION',
  'CONDITIONAL_BEAN',
  'DEPENDENCY',
  'ACTUATOR',
  'API_SURFACE',
  'OBSERVABILITY',
  'MAINTAINABILITY'
] as const;
const FINDING_RUNTIME_DETECTIONS = [
  'ALL',
  'NOT_NORMALLY_DETECTED',
  'ACTIVE_PROFILE_RUNTIME_MAY_DETECT',
  'RUNTIME_REQUIRED'
] as const;
const FINDING_CONFIDENCE_LEVELS = ['ALL', 'HIGH', 'MEDIUM', 'LOW'] as const;
const CONFIGURATION_KINDS = [
  'ALL',
  'SPRING_BOOT',
  'SPRING_BOOT_MAP_PROPERTY',
  'CUSTOM_CONFIGURATION_PROPERTIES',
  'CODE_REFERENCED',
  'CONDITIONAL_PROPERTY',
  'THIRD_PARTY',
  'UNKNOWN'
] as const;
const CONFIGURATION_FOCUS_OPTIONS = ['REVIEW', 'SPRING_BOOT', 'CUSTOM', 'PROFILE_DIFFS', 'ALL'] as const;
const COMPONENT_TYPES = [
  'ALL',
  'MAIN_APPLICATION',
  'REST_CONTROLLER',
  'CONTROLLER',
  'SERVICE',
  'REPOSITORY',
  'COMPONENT',
  'CONFIGURATION',
  'ENTITY',
  'CONFIGURATION_PROPERTIES',
  'UNKNOWN'
] as const;

type SortDirection = 'asc' | 'desc';
type SectionChipTone = 'default' | 'info' | 'warning' | 'success';
type SectionChip = {
  text: string;
  tone?: SectionChipTone;
};
type PresentedFinding = {
  severity: string;
  title: string;
  ruleType: string;
  target: string;
  location: string;
  locationShort: string;
  locationMeta: string;
  locationKnown: boolean;
  message: string;
  summary: string;
  category: string;
  confidence: string;
  runtimeDetection: string;
  heuristic: boolean;
  finding: Finding;
};

type GroupedPresentedFinding = PresentedFinding & {
  occurrences: number;
  items: PresentedFinding[];
};

export interface FindingCodeOccurrence {
  key: string;
  label: string;
  summary: string;
  location: SourceLocation;
  highlightRanges: HighlightRange[];
}

export interface CodeSnippetModalState {
  open: boolean;
  title: string;
  summary: string;
  ruleType: string;
  target: string;
  severity: string;
  category: string;
  confidence: string;
  runtimeDetection: string;
  analysisId: string | null;
  occurrences: FindingCodeOccurrence[];
  selectedOccurrenceIndex: number;
  snippet: SourceSnippetResponse | null;
  loading: boolean;
  errorMessage: string;
  returnFocusId: string | null;
}

export interface TableSortState {
  key: string;
  direction: SortDirection;
}

export interface ResultsViewState {
  findingsSeverity: string;
  findingsCategory: string;
  findingsRuntimeDetection: string;
  findingsConfidence: string;
  findingsText: string;
  findingsExpanded: boolean;
  findingsGrouped: boolean;
  configurationSearch: string;
  configurationFocus: string;
  configurationProfile: string;
  configurationSource: string;
  configurationKind: string;
  configurationSensitiveOnly: boolean;
  configurationView: 'flat' | 'compare';
  configurationChangedOnly: boolean;
  configurationExpanded: boolean;
  configurationExpandedRowKey: string | null;
  findingsSort: TableSortState;
  inboundSort: TableSortState;
  outboundSort: TableSortState;
  configuredUrlsSort: TableSortState;
  configurationSort: TableSortState;
  componentsSort: TableSortState;
  dependenciesSort: TableSortState;
  componentType: string;
  componentText: string;
  componentsExpanded: boolean;
  dependencyText: string;
  resolvedDependencyConfiguration: string;
  resolvedDependencyDirectOnly: boolean;
  rawJsonExpanded: boolean;
  httpInboundExpanded: boolean;
  httpOutboundExpanded: boolean;
  httpConfiguredExpanded: boolean;
  httpActuatorExpanded: boolean;
  codeModal: CodeSnippetModalState;
}

export interface ResultsViewActions {
  onFindingsSeverityChange: (value: string) => void;
  onFindingsCategoryChange: (value: string) => void;
  onFindingsRuntimeDetectionChange: (value: string) => void;
  onFindingsConfidenceChange: (value: string) => void;
  onFindingsTextChange: (value: string) => void;
  onToggleFindingsExpanded: () => void;
  onFindingsGroupedChange: (value: boolean) => void;
  onConfigurationSearchChange: (value: string) => void;
  onConfigurationFocusChange: (value: string) => void;
  onConfigurationProfileChange: (value: string) => void;
  onConfigurationSourceChange: (value: string) => void;
  onConfigurationKindChange: (value: string) => void;
  onConfigurationSensitiveOnlyChange: (value: boolean) => void;
  onConfigurationViewChange: (value: 'flat' | 'compare') => void;
  onConfigurationChangedOnlyChange: (value: boolean) => void;
  onToggleConfigurationExpanded: () => void;
  onToggleConfigurationRow: (key: string) => void;
  onSetFindingsSort: (key: string) => void;
  onSetInboundSort: (key: string) => void;
  onSetOutboundSort: (key: string) => void;
  onSetConfiguredUrlsSort: (key: string) => void;
  onSetConfigurationSort: (key: string) => void;
  onSetComponentsSort: (key: string) => void;
  onSetDependenciesSort: (key: string) => void;
  onComponentTypeChange: (value: string) => void;
  onComponentTextChange: (value: string) => void;
  onToggleComponentsExpanded: () => void;
  onDependencyTextChange: (value: string) => void;
  onResolvedDependencyConfigurationChange: (value: string) => void;
  onResolvedDependencyDirectOnlyChange: (value: boolean) => void;
  onRawJsonExpandedChange: (value: boolean) => void;
  onToggleHttpInboundExpanded: () => void;
  onToggleHttpOutboundExpanded: () => void;
  onToggleHttpConfiguredExpanded: () => void;
  onToggleHttpActuatorExpanded: () => void;
  onOpenFindingCode: (finding: Finding, groupedFindings: Finding[], triggerId: string, selectedOccurrenceIndex?: number) => void;
  onCloseFindingCode: () => void;
  onSelectFindingCodeOccurrence: (index: number) => void;
}

export function renderResultsView(
  result: AnalyzeRepositoryResponse | null,
  state: ResultsViewState,
  actions: ResultsViewActions,
  status?: { statusMessage?: string; errorMessage?: string; warningMessage?: string; isAnalyzing?: boolean }
): HTMLElement {
  const panel = element('section', { className: 'panel panel-compact panel-results' });
  panel.appendChild(renderResultsHeader(status));

  if (!result) {
    panel.appendChild(
      element(
        'div',
        { className: 'empty-state' },
        element('p', {
          text: 'Enter a repository URL, optionally choose a branch and HTTPS token, then click "Clone and analyze".'
        }),
        element('p', {
          text: 'The backend clones the repository into a temporary workspace and analyzes source, configuration, and framework usage without starting the application.'
        })
      )
    );
    return panel;
  }

  panel.appendChild(renderSectionJumpNav());
  panel.appendChild(renderProjectSection(result));
  panel.appendChild(renderResultsOverviewBlock(result));
  panel.appendChild(renderRuntimeStackSection(result));
  panel.appendChild(renderFindingsSection(result.findings ?? [], state, actions));
  panel.appendChild(renderHttpSurfaceSection(result.httpSurfaceAnalysis, state, actions));
  panel.appendChild(renderConfigurationSection(result, state, actions));
  panel.appendChild(renderSpringApiUsageSection(result));
  panel.appendChild(renderComponentsSection(result.detectedComponents ?? [], state, actions));
  panel.appendChild(renderDependenciesSection(result, state, actions));
  panel.appendChild(renderBuildModelSection(result.gradleModelAnalysis));
  panel.appendChild(renderRawJsonSection(result, state, actions));
  if (state.codeModal.open) {
    panel.appendChild(renderCodeSnippetModal(state.codeModal, actions));
  }
  return panel;
}

function renderProjectSection(result: AnalyzeRepositoryResponse): HTMLElement {
  const section = resultsSection(
    'Project',
    'results-project',
    'Repository source, branch selection, and analyzer workspace identity for this run.'
  );
  const block = element('div', { className: 'project-summary-card project-summary-card-compact' });
  const rows = element('div', { className: 'project-summary-grid project-summary-grid-compact' });
  rows.appendChild(summaryValueRow('Repository URL', result.repositoryUrl ?? 'Unknown', true));
  rows.appendChild(summaryValueRow('Branch', result.branch?.trim() ? result.branch : 'default branch'));
  rows.appendChild(summaryValueRow('Workspace ID', result.workspaceId ?? 'Not returned', true));
  block.appendChild(rows);
  section.appendChild(block);
  return section;
}

function renderResultsOverviewBlock(result: AnalyzeRepositoryResponse): HTMLElement {
  const wrapper = element('section', { className: 'results-overview-block' });
  const grid = element('div', { className: 'results-overview-grid' });
  grid.append(
    renderTopConcernsCard(result),
    renderSecurityPostureCard(result),
    renderPersistenceSummaryCard(result)
  );
  wrapper.appendChild(grid);
  const toolingNote = toolingProjectNote(result);
  if (toolingNote) {
    wrapper.appendChild(
      element('div', {
        className: 'empty-note overview-empty-note',
        text: toolingNote
      })
    );
  }
  return wrapper;
}

function toolingProjectNote(result: AnalyzeRepositoryResponse): string | null {
  const repositoryText = `${result.repositoryUrl ?? ''} ${result.workspaceId ?? ''}`.toLowerCase();
  const componentSignals = (result.detectedComponents ?? [])
    .map((component) => componentDisplayName(component).toLowerCase());
  const sourceSignals = (result.findings ?? [])
    .map((finding) => `${finding.sourceFile ?? ''} ${finding.target ?? ''}`.toLowerCase());
  const matches = [
    repositoryText,
    ...componentSignals,
    ...sourceSignals
  ].filter((value) =>
    value.includes('analyzer')
    || value.includes('scanner')
    || value.includes('parser')
    || value.includes('gradlemodel')
    || value.includes('staticpracticefindinganalyzer')
  ).length;
  if (matches < 2) {
    return null;
  }
  return 'This repository appears to be an analyzer or tooling application. Some parser and fallback findings may be intentional robustness decisions; review them with that context in mind.';
}

function topConcernGroups(findings: Finding[]): GroupedPresentedFinding[] {
  const merged = new Map<string, PresentedFinding[]>();
  for (const group of groupFindings(findings)) {
    const key = topConcernKey(group);
    const bucket = merged.get(key) ?? [];
    bucket.push(...group.items);
    merged.set(key, bucket);
  }

  return [...merged.entries()]
    .map(([, bucket]) => {
      const representative = chooseFindingGroupRepresentative(bucket);
      const uniqueTargets = uniqueValues(bucket.map((item) => item.target).filter((value) => value && value !== '—'));
      const target = uniqueTargets.length <= 1
        ? (uniqueTargets[0] ?? representative.target)
        : groupedTargetSummary(representative, uniqueTargets.length);
      return {
        severity: representative.severity,
        title: representative.title,
        category: representative.category,
        confidence: representative.confidence,
        runtimeDetection: representative.runtimeDetection,
        heuristic: representative.heuristic,
        ruleType: representative.ruleType,
        target,
        message: representative.message,
        summary: representative.summary,
        location: representative.location,
        locationShort: middleEllipsis(representative.location, 88),
        locationMeta: representative.locationMeta,
        locationKnown: representative.locationKnown,
        occurrences: bucket.length,
        items: bucket,
        finding: representative.finding
      };
    })
    .filter((finding) => finding.severity === 'ERROR' || finding.severity === 'WARNING')
    .sort((left, right) => {
      const priority = topConcernPriority(left) - topConcernPriority(right);
      if (priority !== 0) {
        return priority;
      }
      const severity = findingPriority(left.severity, ['ERROR', 'WARNING', 'INFO'])
        - findingPriority(right.severity, ['ERROR', 'WARNING', 'INFO']);
      if (severity !== 0) {
        return severity;
      }
      if (left.occurrences !== right.occurrences) {
        return right.occurrences - left.occurrences;
      }
      const confidence = findingPriority(left.confidence, ['High', 'Medium', 'Low'])
        - findingPriority(right.confidence, ['High', 'Medium', 'Low']);
      if (confidence !== 0) {
        return confidence;
      }
      return compareValues(left.title, right.title, 'asc');
    });
}

function topConcernKey(finding: GroupedPresentedFinding): string {
  return topConcernAnchorKeyFromFinding(finding.finding, finding.title, finding.ruleType);
}

function topConcernAnchorKeyFromPresented(finding: GroupedPresentedFinding | PresentedFinding): string {
  return topConcernAnchorKeyFromFinding(finding.finding, finding.title, finding.ruleType);
}

function topConcernAnchorKeyFromFinding(finding: Finding, fallbackTitle?: string, fallbackRuleType?: string): string {
  const ruleId = (finding.ruleId ?? '').trim();
  if (ruleId) {
    return ruleId;
  }
  return normalizeGroupingText(fallbackTitle || fallbackRuleType || finding.title || finding.ruleId || finding.rule || finding.category || 'static-finding');
}

function topConcernAnchorId(key: string): string {
  return `finding-concern-${hashString(key)}`;
}

function topConcernPriority(finding: GroupedPresentedFinding): number {
  const ruleId = (finding.finding.ruleId ?? '').trim();
  if (ruleId === 'SPRING_RAW_EXCEPTION_MESSAGE_HTTP') {
    return 1;
  }
  if (ruleId === 'SPRING_SECRET_LITERAL' || ruleId === 'SPRING_SECRET_WEAK_PLACEHOLDER_DEFAULT') {
    return 2;
  }
  if (ruleId === 'SPRING_RISKY_PROD_CONFIG' || finding.category === 'Actuator') {
    return 3;
  }
  if (ruleId === 'JAVA_EMPTY_CATCH_BLOCK') {
    return 4;
  }
  if (ruleId === 'SPRING_SWALLOWED_EXCEPTION_FALLBACK') {
    return 5;
  }
  if (ruleId === 'SPRING_BROAD_EXCEPTION_HANDLER') {
    return 6;
  }
  if (ruleId === 'CONFIG_CODE_REFERENCE_MISSING') {
    return 7;
  }
  return 20;
}

function renderRuntimeStackSection(result: AnalyzeRepositoryResponse): HTMLElement {
  const runtime = result.runtimeStackAnalysis ?? {};
  const gradleModel = result.gradleModelAnalysis;
  const httpSummary = result.httpSurfaceAnalysis?.summary ?? {};
  const configSummary = result.configurationAnalysis?.summary ?? {};
  const findings = result.findings ?? [];
  const detectedComponents = result.detectedComponents ?? [];
  const section = resultsSection(
    'Runtime and stack',
    'results-runtime',
    'Detected Java, Spring Boot, web stack, runtime capabilities, and dependency model.',
    [
      sectionChip(runtime.springBootVersion ? `Spring Boot ${runtime.springBootVersion}` : 'Spring Boot unknown'),
      sectionChip(webStackLabel(runtime.webStack)),
      sectionChip(dependencyModelLabel(gradleModel), 'info')
    ]
  );
  section.appendChild(
    renderRuntimePillars([
      {
        label: 'Spring Boot',
        value: runtime.springBootVersion ?? 'Unknown',
        meta: runtime.springBootVersionSource ?? undefined
      },
      {
        label: 'Java',
        value: runtime.javaVersion ?? result.javaVersionHint ?? 'Unknown'
      },
      {
        label: 'Web stack',
        value: webStackLabel(runtime.webStack),
        meta: runtime.webStackReason ?? undefined
      },
      {
        label: 'Dependency model',
        value: dependencyModelLabel(gradleModel),
        meta: dependencyModelMeta(gradleModel)
      }
    ])
  );
  const overview = element('div', { className: 'runtime-overview' });
  const stackPanel = element(
    'div',
    { className: 'runtime-stack-panel' },
    element('div', { className: 'subsection-title', text: 'Stack overview' })
  );
  const mainClass = runtime.mainClass ?? normalizeMainApplicationClasses(result.mainApplicationClasses)[0]?.name ?? 'Unknown';
  const foundationList = element('div', { className: 'runtime-kv-list' });
  const capabilitiesList = element('div', { className: 'runtime-kv-list' });

  const foundationRows: Array<{ label: string; value: string; meta?: string | null; wide?: boolean }> = [
    { label: 'Spring framework', value: springFrameworkVersionLabel(result), meta: springFrameworkVersionMeta(result) },
    { label: 'Build tool', value: result.buildTool ?? 'Unknown' },
    { label: 'Dependency model', value: dependencyModelLabel(gradleModel), meta: dependencyModelMeta(gradleModel) },
    { label: 'Main class', value: mainClass, wide: true }
  ];
  const capabilityRows: Array<{ label: string; value: string; meta?: string | null; wide?: boolean }> = [
    { label: 'Virtual threads', value: virtualThreadLabel(runtime), meta: runtimeVirtualThreadsCompactMeta(runtime) },
    { label: 'Scheduling', value: schedulingLabel(runtime), meta: schedulingMeta(runtime) },
    { label: 'Actuator', value: actuatorLabel(result), meta: actuatorMeta(result) }
  ];

  for (const row of foundationRows) {
    foundationList.appendChild(renderRuntimeKeyValueRow(row.label, row.value, row.meta, row.wide));
  }
  for (const row of capabilityRows) {
    capabilitiesList.appendChild(renderRuntimeKeyValueRow(row.label, row.value, row.meta, row.wide));
  }
  stackPanel.append(
    element(
      'div',
      { className: 'runtime-panel-section' },
      element('div', { className: 'runtime-panel-caption', text: 'Foundation' }),
      foundationList
    ),
    element(
      'div',
      { className: 'runtime-panel-section' },
      element('div', { className: 'runtime-panel-caption', text: 'Capabilities' }),
      capabilitiesList
    )
  );

  const metricsPanel = element(
    'div',
    { className: 'runtime-metrics-panel' },
    element('div', { className: 'subsection-title', text: 'Metrics' })
  );
  const metricsGrid = element('div', { className: 'runtime-metric-grid' });
  const findingsMeta = summarizeFindings(findings);
  const metrics: Array<{ label: string; value: string; meta?: string | null; warning?: boolean }> = [
    { label: 'Inbound endpoints', value: String(httpSummary.inboundEndpointCount ?? 0) },
    { label: 'Outbound calls', value: String(httpSummary.outboundEndpointCount ?? 0) },
    { label: 'Components', value: String(detectedComponents.length) },
    {
      label: 'Configured properties',
      value: String(configSummary.configuredPropertyCount ?? (result.configurationAnalysis?.properties ?? []).length)
    },
    { label: 'External hosts', value: String((httpSummary.externalHosts ?? []).length) },
    {
      label: 'Findings',
      value: String(findings.length),
      meta: findingsMeta,
      warning: findings.length > 0
    }
  ];

  for (const metric of metrics) {
    metricsGrid.appendChild(renderRuntimeMetric(metric.label, metric.value, metric.meta, metric.warning));
  }
  metricsPanel.appendChild(metricsGrid);

  overview.append(stackPanel, metricsPanel);
  section.appendChild(overview);
  section.appendChild(renderJavaModelCard(result));
  return section;
}

function renderRuntimePillars(
  pillars: Array<{ label: string; value: string; meta?: string }>
): HTMLElement {
  const grid = element('div', { className: 'runtime-pillars' });
  for (const pillar of pillars) {
    grid.appendChild(
      element(
        'div',
        { className: 'runtime-pillar' },
        element('div', { className: 'runtime-pillar-label', text: pillar.label }),
        element('div', {
          className: 'runtime-pillar-value',
          text: pillar.value,
          attributes: { title: pillar.value }
        }),
        pillar.meta
          ? element('div', {
              className: 'runtime-pillar-meta',
              text: pillar.meta,
              attributes: { title: pillar.meta }
            })
          : null
      )
    );
  }
  return grid;
}

function renderTopConcernsCard(result: AnalyzeRepositoryResponse): HTMLElement {
  const findings = result.findings ?? [];
  const concerns = topConcernGroups(findings).slice(0, 5);
  const card = summaryInsightCard(
    'Top concerns',
    'Highest-value static findings to review first before diving into the full details.'
  );
  if (concerns.length === 0) {
    card.appendChild(
      element('div', {
        className: 'empty-note success-note overview-empty-note',
        text: 'No major concerns detected. The analyzer did not find any warning-level or error-level issues in this run.'
      })
    );
    return card;
  }
  const list = element('ol', { className: 'top-concern-list' });
  concerns.forEach((concern) => {
    const item = element('li', { className: 'top-concern-item' });
    const concernAnchor = topConcernAnchorId(topConcernKey(concern));
    const link = element('a', {
      className: 'top-concern-link',
      text: concern.title,
      attributes: {
        href: `#${concernAnchor}`,
        title: concern.summary || concern.message
      }
    });
    item.append(
      link,
      element('div', {
        className: 'top-concern-summary',
        text: concern.summary || concern.message
      }),
      element(
        'div',
        { className: 'top-concern-meta' },
        element('span', {
          className: `badge badge-${concern.severity.toLowerCase()}`,
          text: concern.severity,
          attributes: { title: severityExplanation(concern.severity), 'aria-label': severityExplanation(concern.severity) }
        }),
        element('span', { className: 'badge badge-category', text: concern.category }),
        element('span', { className: 'badge badge-runtime', text: concern.runtimeDetection }),
        concern.occurrences > 1
          ? element('span', { className: 'finding-summary-count', text: `${concern.occurrences} occurrences` })
          : null
      )
    );
    list.appendChild(item);
  });
  card.appendChild(list);
  return card;
}

function renderSecurityPostureCard(result: AnalyzeRepositoryResponse): HTMLElement {
  const findings = result.findings ?? [];
  const dependencies = collectDependencyNotations(result);
  const inboundCount = result.httpSurfaceAnalysis?.summary?.inboundEndpointCount
    ?? (result.httpSurfaceAnalysis?.inboundEndpoints ?? []).length;
  const actuatorExposureCount = result.httpSurfaceAnalysis?.summary?.actuatorExposureCount
    ?? (result.httpSurfaceAnalysis?.actuatorExposures ?? []).length;
  const securityPresent = dependencies.some((dependency) =>
    dependency.includes('spring-boot-starter-security') || dependency.includes('org.springframework.security')
  );
  const securityConfigDetected = (result.detectedComponents ?? []).some((component) => {
    const name = componentDisplayName(component).toLowerCase();
    const annotations = (component.annotationNames ?? component.annotations ?? []).map((value) => value.toLowerCase());
    return name.includes('security')
      || annotations.some((value) => value.includes('enablewebsecurity') || value.includes('securityfilterchain'));
  });
  const sensitiveValues = result.configurationAnalysis?.summary?.sensitiveValueCount
    ?? findings.filter((finding) => finding.ruleId === 'SPRING_SECRET_LITERAL').length;
  const weakSecretDefaults = findings.filter((finding) => finding.ruleId === 'SPRING_SECRET_WEAK_PLACEHOLDER_DEFAULT').length;
  const rawExceptionExposures = findings.filter((finding) => finding.ruleId === 'SPRING_RAW_EXCEPTION_MESSAGE_HTTP').length;
  const healthDetailsExposed = findings.some((finding) =>
    finding.ruleId === 'SPRING_RISKY_PROD_CONFIG'
      && (finding.target === 'management.endpoint.health.show-details'
        || defaultText(finding.evidence).includes('management.endpoint.health.show-details'))
  );

  const card = summaryInsightCard(
    'Security posture',
    'Static signals about security dependencies, exposed surfaces, and sensitive configuration handling.'
  );
  const list = element('div', { className: 'detail-card detail-card-compact overview-detail-list' });
  [
    ['Spring Security', securityPresent ? 'Present' : 'Not detected'],
    ['Security configuration', securityConfigDetected ? 'Detected' : 'Not confirmed statically'],
    ['Inbound endpoints', String(inboundCount)],
    ['Actuator exposures', actuatorExposureCount > 0 ? String(actuatorExposureCount) : '0 detected'],
    ['Sensitive values redacted', `${sensitiveValues}`],
    ['Weak secret defaults', String(weakSecretDefaults)],
    ['Raw exception responses', String(rawExceptionExposures)],
    ['Health details exposure', healthDetailsExposed ? 'Review needed' : 'Not detected'],
    ['Endpoint authorization model', 'Not confirmed statically']
  ].forEach(([label, value]) => list.appendChild(renderDetailRow(label, value)));
  card.appendChild(list);
  return card;
}

function renderPersistenceSummaryCard(result: AnalyzeRepositoryResponse): HTMLElement {
  const findings = result.findings ?? [];
  const dependencies = collectDependencyNotations(result);
  const repositories = (result.detectedComponents ?? []).filter((component) => normalizeComponentType(component) === 'REPOSITORY');
  const runtimeDatabase = inferDatabaseSignal(result, ['default', 'prod', 'production', 'staging']);
  const testDatabase = inferDatabaseSignal(result, ['test']);
  const persistenceStyle = inferPersistenceStyle(dependencies);
  const flywayPresent = dependencies.some((dependency) => dependency.includes('org.flywaydb:flyway-core'))
    || (result.gradleModelAnalysis?.pluginDeclarations ?? []).some((plugin) => plugin.pluginId === 'org.flywaydb.flyway')
    || (result.gradleModelAnalysis?.plugins ?? []).some((plugin) => defaultText(plugin.pluginId).includes('flyway'));
  const persistencePresent = persistenceStyle !== 'Not confirmed statically'
    || repositories.length > 0
    || runtimeDatabase !== null
    || testDatabase !== null
    || flywayPresent;
  const transactionConcerns = findings.filter((finding) => normalizeFindingCategory(finding.category) === 'TRANSACTION').length;

  const card = summaryInsightCard(
    'Persistence',
    'Detected persistence stack, database signals, schema tooling, and transaction-related review points.'
  );
  const list = element('div', { className: 'detail-card detail-card-compact overview-detail-list' });
  [
    ['Persistence style', persistencePresent ? persistenceStyle : 'Not confirmed statically'],
    ['Runtime database', runtimeDatabase ?? 'Not confirmed statically'],
    ['Test database', testDatabase ?? 'Not confirmed statically'],
    ['Flyway', flywayPresent ? 'Present' : 'Not detected'],
    ['Repositories', String(repositories.length)],
    [persistencePresent ? 'Transaction concerns' : 'Potential consistency concerns', String(transactionConcerns)]
  ].forEach(([label, value]) => list.appendChild(renderDetailRow(label, value)));
  card.appendChild(list);
  return card;
}

function renderFindingsSection(
  findings: Finding[],
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const warningCount = findings.filter((finding) => normalizeSeverity(finding.severity) === 'WARNING').length;
  const staticOnlyCount = findings.filter((finding) => normalizeRuntimeDetection(finding.runtimeDetection) === 'NOT_NORMALLY_DETECTED').length;
  const section = resultsSection(
    `Findings (${findings.length})`,
    'results-findings',
    'Prioritized static findings from configuration, code patterns, HTTP usage, and profile drift.',
    [
      sectionChip(`${warningCount} warnings to review`, warningCount > 0 ? 'warning' : 'default'),
      sectionChip(`${staticOnlyCount} detected statically`, 'info')
    ]
  );
  if (findings.length === 0) {
    section.appendChild(
      element('div', { className: 'empty-note success-note', text: 'No issues detected by the current checks.' })
    );
    return section;
  }

  const summary = element('div', { className: 'stat-grid compact' });
  const summaryStats: Array<{ label: string; value: number; className?: string }> = [
    { label: 'Total findings', value: findings.length },
    { label: 'Errors / blocking', value: findings.filter((finding) => normalizeSeverity(finding.severity) === 'ERROR').length, className: 'severity-error' },
    { label: 'Warnings', value: findings.filter((finding) => normalizeSeverity(finding.severity) === 'WARNING').length, className: 'severity-warning' },
    { label: 'Info', value: findings.filter((finding) => normalizeSeverity(finding.severity) === 'INFO').length, className: 'severity-info' },
    {
      label: 'Detected statically',
      value: findings.filter((finding) => normalizeRuntimeDetection(finding.runtimeDetection) === 'NOT_NORMALLY_DETECTED').length
    },
    {
      label: 'Depends on active profile',
      value: findings.filter((finding) => normalizeRuntimeDetection(finding.runtimeDetection) === 'ACTIVE_PROFILE_RUNTIME_MAY_DETECT').length
    },
    {
      label: 'Requires runtime verification',
      value: findings.filter((finding) => normalizeRuntimeDetection(finding.runtimeDetection) === 'RUNTIME_REQUIRED').length
    }
  ];
  for (const stat of summaryStats) {
    summary.appendChild(
      element(
        'div',
        { className: `mini-stat ${stat.className ?? ''}`.trim() },
        element('div', { className: 'mini-stat-label', text: stat.label }),
        element('div', {
          className: 'mini-stat-value',
          text: String(stat.value)
        })
      )
    );
  }
  section.appendChild(summary);
  section.appendChild(
    element('p', {
      className: 'muted-text findings-severity-note',
      text: 'Warnings are prioritized static review items. Errors are reserved for analyzer failures or other blocking conditions.'
    })
  );

  const primaryControls = element('div', { className: 'filter-row compact-filter-row' });
  primaryControls.append(
    labeledInlineField(
      'Severity',
      selectInput(
        FINDING_SEVERITIES.map((value) => ({ value, label: value === 'ALL' ? 'All severities' : value })),
        state.findingsSeverity,
        actions.onFindingsSeverityChange,
        'results-findings-severity'
      )
    ),
    labeledInlineField(
      'Category',
      selectInput(
        FINDING_CATEGORIES.map((value) => ({ value, label: value === 'ALL' ? 'All categories' : findingCategoryLabel(value) })),
        state.findingsCategory,
        actions.onFindingsCategoryChange,
        'results-findings-category'
      )
    ),
    labeledInlineField(
      'Runtime detection',
      selectInput(
        FINDING_RUNTIME_DETECTIONS.map((value) => ({
          value,
          label: value === 'ALL' ? 'All runtime signals' : runtimeDetectionLabel(value)
        })),
        state.findingsRuntimeDetection,
        actions.onFindingsRuntimeDetectionChange,
        'results-findings-runtime-detection'
      )
    ),
    labeledInlineField(
      'Confidence',
      selectInput(
        FINDING_CONFIDENCE_LEVELS.map((value) => ({
          value,
          label: value === 'ALL' ? 'All confidence levels' : confidenceLabel(value)
        })),
        state.findingsConfidence,
        actions.onFindingsConfidenceChange,
        'results-findings-confidence'
      )
    )
  );
  section.appendChild(primaryControls);

  const secondaryControls = element('div', { className: 'filter-row compact-filter-row' });
  secondaryControls.append(
    labeledInlineField(
      'Text',
      textInput(state.findingsText, 'Filter findings', actions.onFindingsTextChange, 'results-findings-text')
    ),
    labeledInlineField(
      'Grouping',
      checkboxField('Group similar findings', state.findingsGrouped, actions.onFindingsGroupedChange, 'results-findings-grouped')
    )
  );
  section.appendChild(secondaryControls);

  const filtered = findings.filter((finding) => {
    const severityMatches =
      state.findingsSeverity === 'ALL' || normalizeSeverity(finding.severity) === state.findingsSeverity;
    const categoryMatches =
      state.findingsCategory === 'ALL' || normalizeFindingCategory(finding.category) === state.findingsCategory;
    const runtimeMatches =
      state.findingsRuntimeDetection === 'ALL'
      || normalizeRuntimeDetection(finding.runtimeDetection) === state.findingsRuntimeDetection;
    const confidenceMatches =
      state.findingsConfidence === 'ALL' || normalizeConfidence(finding.confidence) === state.findingsConfidence;
    const textNeedle = state.findingsText.trim().toLowerCase();
    const derived = deriveFindingPresentation(finding);
    const haystack = [
      finding.message,
      finding.location,
      finding.rule,
      finding.ruleId,
      finding.title,
      finding.category,
      finding.runtimeDetection,
      finding.confidence,
      finding.target,
      finding.evidence,
      derived.ruleType,
      derived.target
    ]
      .filter((value): value is string => Boolean(value))
      .join(' ')
      .toLowerCase();
    return severityMatches && categoryMatches && runtimeMatches && confidenceMatches
      && (!textNeedle || haystack.includes(textNeedle));
  });

  if (filtered.length === 0) {
    section.appendChild(element('p', { className: 'muted-text', text: 'No findings match the current filters.' }));
    return section;
  }

  const grouped = state.findingsGrouped ? groupFindings(filtered) : [];
  const sortedGroups = state.findingsGrouped ? sortFindingGroups(grouped, state.findingsSort) : [];
  const sortedFindings: PresentedFinding[] = state.findingsGrouped
      ? sortedGroups
      : sortFindings(filtered, state.findingsSort).map(deriveFindingPresentation);
  const visible = state.findingsExpanded ? sortedFindings : sortedFindings.slice(0, 25);
  const assignedConcernAnchors = new Set<string>();
  const table = createTable([
    sortableHeader('Severity', 'severity', state.findingsSort, actions.onSetFindingsSort),
    'Category',
    sortableHeader('Finding', 'rule', state.findingsSort, actions.onSetFindingsSort),
    sortableHeader('Location', 'location', state.findingsSort, actions.onSetFindingsSort),
    'Actions'
  ], 'findings-table');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const derived of visible) {
    const groupedFinding = state.findingsGrouped ? (derived as GroupedPresentedFinding) : null;
    const row = tbody.insertRow();
    row.className = 'data-row finding-summary-row';
    const concernAnchorKey = topConcernAnchorKeyFromPresented(groupedFinding ?? derived);
    if (!assignedConcernAnchors.has(concernAnchorKey)) {
      row.id = topConcernAnchorId(concernAnchorKey);
      assignedConcernAnchors.add(concernAnchorKey);
    }
    const detailsId = `finding-details-${Math.random().toString(36).slice(2, 10)}`;
    const codeButtonId = createFindingCodeButtonId(groupedFinding ?? derived);
    appendCells(row, [
      badgeCell(derived.severity, `badge badge-${derived.severity.toLowerCase()}`),
      badgeCell(derived.category, 'badge badge-category'),
      findingSummaryCell(groupedFinding ?? derived, detailsId),
      findingLocationCell(groupedFinding ?? derived),
      findingActionsCell(groupedFinding ?? derived, detailsId, codeButtonId)
    ]);
    const detailsRow = tbody.insertRow();
    detailsRow.className = 'details-row finding-details-row';
    detailsRow.hidden = true;
    const detailsCell = detailsRow.insertCell();
    detailsCell.colSpan = 5;
    detailsCell.appendChild(renderFindingDetailsCard(groupedFinding ?? derived, detailsId, actions));

    const expandButton = row.querySelector('.finding-expand-button') as HTMLButtonElement | null;
    if (expandButton) {
      expandButton.addEventListener('click', () => {
        const expanded = detailsRow.hidden;
        detailsRow.hidden = !expanded;
        expandButton.textContent = expanded ? 'Hide details' : 'Show details';
        expandButton.setAttribute('aria-expanded', expanded ? 'true' : 'false');
      });
    }
    const codeButton = row.querySelector('.finding-code-button') as HTMLButtonElement | null;
    if (codeButton) {
      codeButton.addEventListener('click', () => {
        actions.onOpenFindingCode(
          groupedFinding ? groupedFinding.finding : derived.finding,
          groupedFinding ? groupedFinding.items.map((item) => item.finding) : [derived.finding],
          codeButtonId,
          0
        );
      });
    }
  }
  section.appendChild(wrapTable(table));

  if (sortedFindings.length > 25) {
    section.appendChild(
      toggleButton(
        state.findingsExpanded ? 'Show fewer findings' : `Show all findings (${sortedFindings.length})`,
        actions.onToggleFindingsExpanded
      )
    );
  }
  return section;
}

function renderHttpSurfaceSection(
  httpSurfaceAnalysis: HttpSurfaceAnalysis | undefined,
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const analysis = httpSurfaceAnalysis ?? {};
  const inbound = analysis.inboundEndpoints ?? [];
  const outbound = analysis.outboundEndpoints ?? [];
  const configured = analysis.configuredUrls ?? [];
  const actuator = analysis.actuatorExposures ?? [];
  const summary = analysis.summary ?? {};

  const section = resultsSection(
    'HTTP surface',
    'results-http',
    'Inbound routes, outbound calls, configured service URLs, and actuator exposure signals detected statically.',
    [
      sectionChip(`${summary.inboundEndpointCount ?? inbound.length} inbound`),
      sectionChip(`${summary.outboundEndpointCount ?? outbound.length} outbound`, 'info'),
      sectionChip(`${summary.actuatorExposureCount ?? actuator.length} actuator`, actuator.length > 0 ? 'warning' : 'default')
    ]
  );
  const cards = element('div', { className: 'summary-grid runtime-grid' });
  for (const [label, value] of [
    ['Inbound endpoints', String(summary.inboundEndpointCount ?? inbound.length)],
    ['Outbound calls', String(summary.outboundEndpointCount ?? outbound.length)],
    ['Configured URLs', String(summary.configuredUrlCount ?? configured.length)],
    ['Actuator exposures', String(summary.actuatorExposureCount ?? actuator.length)],
    ['External hosts', String((summary.externalHosts ?? []).length)]
  ] as Array<[string, string]>) {
    cards.appendChild(
      element(
        'div',
        { className: 'summary-card compact-summary-card' },
        element('div', { className: 'summary-label', text: label }),
        element('div', { className: 'summary-value', text: value })
      )
    );
  }
  section.appendChild(cards);

  if (inbound.length === 0 && outbound.length === 0 && configured.length === 0 && actuator.length === 0) {
    section.appendChild(element('div', { className: 'empty-note', text: 'No HTTP surface signals were detected.' }));
    return section;
  }

  section.appendChild(renderInboundEndpointsTable(inbound, state, actions));
  section.appendChild(renderOutboundEndpointsTable(outbound, state, actions));
  section.appendChild(renderConfiguredUrlsTable(configured, state, actions));
  section.appendChild(renderActuatorTable(actuator, state, actions));
  return section;
}

function renderInboundEndpointsTable(
  endpoints: InboundEndpoint[],
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const block = element('section', { className: 'subsection-block' }, element('h4', { text: 'Inbound endpoints' }));
  if (endpoints.length === 0) {
    block.appendChild(element('p', { className: 'muted-text', text: 'No inbound endpoints were detected.' }));
    return block;
  }

  const visible = state.httpInboundExpanded
    ? sortInboundEndpoints(endpoints, state.inboundSort)
    : sortInboundEndpoints(endpoints, state.inboundSort).slice(0, 25);
  const table = createTable([
    sortableHeader('Method', 'method', state.inboundSort, actions.onSetInboundSort),
    sortableHeader('Path', 'path', state.inboundSort, actions.onSetInboundSort),
    sortableHeader('Controller', 'controller', state.inboundSort, actions.onSetInboundSort),
    'Handler',
    'Source',
    'Params'
  ], 'http-table http-table-inbound');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const endpoint of visible) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, [
      badgeCell(endpoint.httpMethod ?? 'ANY', `badge badge-http`),
      truncateCell(endpoint.path ?? '/', 'http-cell-path'),
      technicalClampCell(endpoint.controllerClass ?? 'Unknown', undefined, 'http-cell-controller'),
      truncateCell(endpoint.handlerMethod ?? 'Unknown', 'http-cell-handler'),
      technicalClampCell(sourceLabel(endpoint.sourceFile, endpoint.line), undefined, 'http-cell-source'),
      technicalClampCell((endpoint.parameters ?? []).join(', ') || '—', undefined, 'http-cell-params')
    ]);
  }
  block.appendChild(wrapTable(table));
  if (endpoints.length > 25) {
    block.appendChild(
      toggleButton(
        state.httpInboundExpanded ? 'Show fewer inbound endpoints' : `Show all inbound endpoints (${endpoints.length})`,
        actions.onToggleHttpInboundExpanded
      )
    );
  }
  return block;
}

function renderOutboundEndpointsTable(
  endpoints: OutboundEndpoint[],
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const block = element('section', { className: 'subsection-block' }, element('h4', { text: 'Outbound calls' }));
  if (endpoints.length === 0) {
    block.appendChild(element('p', { className: 'muted-text', text: 'No outbound HTTP calls were detected.' }));
    return block;
  }

  const visible = state.httpOutboundExpanded
    ? sortOutboundEndpoints(endpoints, state.outboundSort)
    : sortOutboundEndpoints(endpoints, state.outboundSort).slice(0, 25);
  const table = createTable([
    sortableHeader('Client', 'client', state.outboundSort, actions.onSetOutboundSort),
    sortableHeader('Method', 'method', state.outboundSort, actions.onSetOutboundSort),
    sortableHeader('URL / template', 'url', state.outboundSort, actions.onSetOutboundSort),
    sortableHeader('Host', 'host', state.outboundSort, actions.onSetOutboundSort),
    'Source',
    'Config property'
  ], 'http-table http-table-outbound');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const endpoint of visible) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, [
      truncateCell(endpoint.clientType ?? 'HTTP client', 'http-cell-client'),
      truncateCell(endpoint.method ?? 'REQUEST', 'http-cell-method'),
      outboundUrlCell(endpoint),
      truncateCell(endpoint.host ?? '—', 'http-cell-host'),
      technicalClampCell(sourceLabel(endpoint.sourceFile, endpoint.line), undefined, 'http-cell-source'),
      technicalClampCell(endpoint.configurationPropertyName ?? '—', undefined, 'http-cell-config-property')
    ]);
  }
  block.appendChild(wrapTable(table));
  if (endpoints.length > 25) {
    block.appendChild(
      toggleButton(
        state.httpOutboundExpanded ? 'Show fewer outbound calls' : `Show all outbound calls (${endpoints.length})`,
        actions.onToggleHttpOutboundExpanded
      )
    );
  }
  return block;
}

function renderConfiguredUrlsTable(
  urls: ConfiguredUrl[],
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const block = element('section', { className: 'subsection-block' }, element('h4', { text: 'Configured URLs' }));
  if (urls.length === 0) {
    block.appendChild(element('p', { className: 'muted-text', text: 'No externalized URLs or hosts were detected.' }));
    return block;
  }

  const visible = state.httpConfiguredExpanded
    ? sortConfiguredUrls(urls, state.configuredUrlsSort)
    : sortConfiguredUrls(urls, state.configuredUrlsSort).slice(0, 25);
  const table = createTable([
    sortableHeader('Property', 'property', state.configuredUrlsSort, actions.onSetConfiguredUrlsSort),
    'Value',
    sortableHeader('Kind', 'kind', state.configuredUrlsSort, actions.onSetConfiguredUrlsSort),
    sortableHeader('Host', 'host', state.configuredUrlsSort, actions.onSetConfiguredUrlsSort),
    sortableHeader('Profile', 'profile', state.configuredUrlsSort, actions.onSetConfiguredUrlsSort),
    sortableHeader('Source', 'source', state.configuredUrlsSort, actions.onSetConfiguredUrlsSort)
  ], 'http-table http-table-configured');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const item of visible) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, [
      technicalClampCell(item.propertyName ?? 'Unknown', undefined, 'http-cell-property'),
      technicalClampCell(
        item.referencedPropertyName ? `${item.value ?? '—'} -> ${item.referencedPropertyName}` : item.value ?? '—',
        undefined,
        'http-cell-value'
      ),
      truncateCell(urlKindLabel(item.kind), 'http-cell-kind'),
      truncateCell(item.host ?? '—', 'http-cell-host'),
      truncateCell(item.profile ?? 'default', 'http-cell-profile'),
      technicalClampCell(sourceLabel(item.sourceFile, item.line), undefined, 'http-cell-source')
    ]);
  }
  block.appendChild(wrapTable(table));
  if (urls.length > 25) {
    block.appendChild(
      toggleButton(
        state.httpConfiguredExpanded ? 'Show fewer configured URLs' : `Show all configured URLs (${urls.length})`,
        actions.onToggleHttpConfiguredExpanded
      )
    );
  }
  return block;
}

function renderActuatorTable(
  exposures: ActuatorEndpointExposure[],
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const block = element('section', { className: 'subsection-block' }, element('h4', { text: 'Actuator exposure' }));
  if (exposures.length === 0) {
    block.appendChild(element('p', { className: 'muted-text', text: 'No actuator exposure properties were detected.' }));
    return block;
  }

  const visible = state.httpActuatorExpanded ? exposures : exposures.slice(0, 25);
  const table = createTable(['Property', 'Value', 'Profile', 'Source', 'Exposed endpoints'], 'http-table http-table-actuator');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const exposure of visible) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, [
      technicalClampCell(exposure.propertyName ?? 'Unknown', undefined, 'http-cell-property'),
      truncateCell(exposure.value ?? '—', 'http-cell-value-short'),
      truncateCell(exposure.profile ?? 'default', 'http-cell-profile'),
      technicalClampCell(sourceLabel(exposure.sourceFile, exposure.line), undefined, 'http-cell-source'),
      technicalClampCell((exposure.exposedEndpoints ?? []).join(', ') || '—', undefined, 'http-cell-endpoints')
    ]);
  }
  block.appendChild(wrapTable(table));
  if (exposures.length > 25) {
    block.appendChild(
      toggleButton(
        state.httpActuatorExpanded ? 'Show fewer actuator rows' : `Show all actuator rows (${exposures.length})`,
        actions.onToggleHttpActuatorExpanded
      )
    );
  }
  return block;
}

function renderConfigurationSection(
  result: AnalyzeRepositoryResponse,
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const analysis = result.configurationAnalysis ?? {};
  const properties = analysis.properties ?? [];
  const files = analysis.files ?? [];
  const summary = analysis.summary ?? {};
  const profiles = uniqueValues(properties.map((property) => property.profile ?? 'default'));
  const changedPropertyNames = collectChangedPropertyNames(properties);
  const reviewSignals = configurationReviewSignals(properties, analysis.codeReferences ?? [], changedPropertyNames);
  const dominantNamespace = dominantCustomPropertyNamespace(properties);
  const section = resultsSection(
    'Configuration',
    'results-configuration',
    'Configured properties, profile comparison, Spring Boot metadata matches, and code-referenced configuration signals.',
    [
      sectionChip(`${properties.length} properties`),
      sectionChip(`${summary.sensitiveValueCount ?? 0} sensitive redacted`, (summary.sensitiveValueCount ?? 0) > 0 ? 'warning' : 'default'),
      sectionChip(`${(summary.profiles ?? profiles).length} profiles`, 'info')
    ]
  );

  const cards = element('div', { className: 'summary-grid runtime-grid' });
  for (const [label, value] of [
    ['Configured properties', String(summary.configuredPropertyCount ?? properties.length)],
    ['Known Spring Boot', String(summary.knownSpringBootPropertyCount ?? 0)],
    ['Custom properties', String(summary.customPropertyCount ?? 0)],
    ['Unknown properties', String(summary.unknownPropertyCount ?? 0)],
    ['Code references', String(summary.codeReferenceCount ?? (analysis.codeReferences ?? []).length)],
    ['Sensitive redacted', String(summary.sensitiveValueCount ?? 0)],
    ['Profiles detected', String((summary.profiles ?? []).length)]
  ] as Array<[string, string]>) {
    cards.appendChild(
      element(
        'div',
        { className: 'summary-card compact-summary-card' },
        element('div', { className: 'summary-label', text: label }),
        element('div', { className: 'summary-value', text: value })
      )
    );
  }
  section.appendChild(cards);

  const reviewOverview = element('div', { className: 'summary-grid runtime-grid configuration-review-grid' });
  reviewOverview.appendChild(
    element(
      'div',
      { className: 'summary-card compact-summary-card configuration-review-card' },
      element('div', { className: 'summary-label', text: 'Configuration review' }),
      element('div', {
        className: 'summary-value',
        text: reviewSignals.total > 0
          ? `${reviewSignals.total} review signal${reviewSignals.total === 1 ? '' : 's'} surfaced statically`
          : 'No review-priority signals in the current property set'
      }),
      element(
        'div',
        { className: 'compact-card-meta' },
        [
          `${reviewSignals.sensitive} sensitive`,
          `${reviewSignals.unknown} unknown`,
          `${reviewSignals.changed} changed across profiles`,
          `${reviewSignals.codeReferenced} code-referenced`
        ].join(' · ')
      )
    )
  );
  reviewOverview.appendChild(
    element(
      'div',
      { className: 'summary-card compact-summary-card configuration-review-card' },
      element('div', { className: 'summary-label', text: 'Current shape' }),
      element('div', {
        className: 'summary-value',
        text: dominantNamespace
          ? `${dominantNamespace.namespace} dominates the visible custom namespace`
          : 'Mixed configuration namespaces'
      }),
      element('div', {
        className: 'compact-card-meta',
        text: dominantNamespace
          ? `${dominantNamespace.count} of ${summary.customPropertyCount ?? 0} custom properties share this namespace. Use Focus to jump from raw custom config to Spring Boot or review-oriented subsets.`
          : 'Use Focus to switch between review signals, Spring Boot properties, custom app config, and profile differences.'
      })
    )
  );
  section.appendChild(reviewOverview);

  if (properties.length === 0) {
    section.appendChild(element('div', { className: 'empty-note', text: 'No application configuration files or property references were detected.' }));
    return section;
  }
  const sourceFiles = uniqueValues(properties.map((property) => property.sourceFile));

  const primaryControls = element('div', { className: 'filter-row compact-filter-row configuration-primary-filters' });
  primaryControls.append(
    labeledInlineField(
      'Search',
      textInput(state.configurationSearch, 'Search property', actions.onConfigurationSearchChange, 'results-configuration-search')
    ),
    labeledInlineField(
      'Focus',
      selectInput(
        CONFIGURATION_FOCUS_OPTIONS.map((value) => ({
          value,
          label: configurationFocusLabel(value)
        })),
        state.configurationFocus,
        actions.onConfigurationFocusChange,
        'results-configuration-focus'
      )
    ),
    labeledInlineField(
      'Profile',
      selectInput(
        [{ value: 'ALL', label: 'All profiles' }, ...profiles.map((value) => ({ value, label: value }))],
        state.configurationProfile,
        actions.onConfigurationProfileChange,
        'results-configuration-profile'
      )
    ),
    labeledInlineField('Sensitive', checkboxField('Sensitive only', state.configurationSensitiveOnly, actions.onConfigurationSensitiveOnlyChange, 'results-configuration-sensitive'))
  );
  section.appendChild(primaryControls);

  const secondaryControls = element('div', { className: 'filter-row compact-filter-row configuration-secondary-filters' });
  secondaryControls.append(
    labeledInlineField(
      'Kind',
      selectInput(
        CONFIGURATION_KINDS.map((value) => ({
          value,
          label: value === 'ALL' ? 'All kinds' : configurationKindLabel(value)
        })),
        state.configurationKind,
        actions.onConfigurationKindChange,
        'results-configuration-kind'
      )
    ),
    labeledInlineField(
      'Source',
      selectInput(
        [{ value: 'ALL', label: 'All sources' }, ...sourceFiles.map((value) => ({ value, label: value }))],
        state.configurationSource,
        actions.onConfigurationSourceChange,
        'results-configuration-source'
      )
    )
  );
  section.appendChild(secondaryControls);

  const chipPanel = element('div', { className: 'configuration-chip-panel' });
  const viewRow = element('div', { className: 'mode-switcher compact-chip-row configuration-chip-row' });
  viewRow.append(
    toggleChip('Flat list', state.configurationView === 'flat', () => actions.onConfigurationViewChange('flat')),
    toggleChip('Compare profiles', state.configurationView === 'compare', () => actions.onConfigurationViewChange('compare'))
  );
  chipPanel.appendChild(
    element(
      'div',
      { className: 'configuration-chip-group' },
      element('div', { className: 'configuration-chip-group-label', text: 'View' }),
      viewRow
    )
  );
  const filterRow = element('div', { className: 'mode-switcher compact-chip-row configuration-chip-row' });
  filterRow.append(
    toggleChip('Review', state.configurationFocus === 'REVIEW', () => actions.onConfigurationFocusChange('REVIEW')),
    toggleChip('Spring Boot', state.configurationFocus === 'SPRING_BOOT', () => actions.onConfigurationFocusChange('SPRING_BOOT')),
    toggleChip('Custom app config', state.configurationFocus === 'CUSTOM', () => actions.onConfigurationFocusChange('CUSTOM')),
    toggleChip('Profile differences', state.configurationFocus === 'PROFILE_DIFFS', () => actions.onConfigurationFocusChange('PROFILE_DIFFS')),
    toggleChip('All properties', state.configurationFocus === 'ALL', () => actions.onConfigurationFocusChange('ALL')),
    toggleChip('Sensitive only', state.configurationSensitiveOnly, () => actions.onConfigurationSensitiveOnlyChange(!state.configurationSensitiveOnly)),
    toggleChip('Changed across profiles', state.configurationChangedOnly, () => actions.onConfigurationChangedOnlyChange(!state.configurationChangedOnly))
  );
  chipPanel.appendChild(
    element(
      'div',
      { className: 'configuration-chip-group' },
      element('div', { className: 'configuration-chip-group-label', text: 'Focus' }),
      filterRow
    )
  );
  const kindRow = element('div', { className: 'mode-switcher compact-chip-row configuration-chip-row' });
  kindRow.append(
    toggleChip('All kinds', state.configurationKind === 'ALL', () => actions.onConfigurationKindChange('ALL')),
    toggleChip('Spring Boot', state.configurationKind === 'SPRING_BOOT', () => actions.onConfigurationKindChange('SPRING_BOOT')),
    toggleChip('Custom', state.configurationKind === 'CUSTOM_CONFIGURATION_PROPERTIES', () => actions.onConfigurationKindChange('CUSTOM_CONFIGURATION_PROPERTIES')),
    toggleChip('Code referenced', state.configurationKind === 'CODE_REFERENCED', () => actions.onConfigurationKindChange('CODE_REFERENCED')),
    toggleChip('Third-party', state.configurationKind === 'THIRD_PARTY', () => actions.onConfigurationKindChange('THIRD_PARTY')),
    toggleChip('Unknown', state.configurationKind === 'UNKNOWN', () => actions.onConfigurationKindChange('UNKNOWN'))
  );
  chipPanel.appendChild(
    element(
      'div',
      { className: 'configuration-chip-group' },
      element('div', { className: 'configuration-chip-group-label', text: 'Kind quick filter' }),
      kindRow
    )
  );
  section.appendChild(chipPanel);

  if (files.length > 0) {
    const fileRow = element('div', { className: 'configuration-files-row compact-source-summary' });
    for (const file of files) {
      fileRow.appendChild(
        element(
          'div',
          { className: 'mini-stat' },
          element('div', { className: 'mini-stat-label', text: file.path ?? 'Unknown file' }),
          element('div', {
            className: 'mini-stat-value',
            text: `${file.propertyCount ?? 0} props | ${file.profile ?? 'default'}`
          })
        )
      );
    }
    section.appendChild(fileRow);
  }

  const filtered = properties.filter((property) => configurationMatches(property, state, changedPropertyNames));
  if (filtered.length === 0) {
    section.appendChild(element('p', { className: 'muted-text', text: 'No configuration properties match the current filters.' }));
    return section;
  }

  if (state.configurationView === 'compare') {
    const groups = buildProfileComparisonGroups(filtered).filter((group) => !state.configurationChangedOnly || group.changed);
    if (groups.length === 0) {
      section.appendChild(element('p', { className: 'muted-text', text: 'No profile differences match the current filters.' }));
      return section;
    }
    const visibleGroups = state.configurationExpanded ? groups : groups.slice(0, 25);
    section.appendChild(renderProfileComparisonTable(visibleGroups, profiles));
    if (groups.length > 25) {
      section.appendChild(
        toggleButton(
          state.configurationExpanded ? 'Show fewer configuration rows' : `Show all configuration rows (${groups.length})`,
          actions.onToggleConfigurationExpanded
        )
      );
    }
    return section;
  }

  const visibleProperties = state.configurationExpanded
    ? sortConfigurationProperties(filtered, state.configurationSort)
    : sortConfigurationProperties(filtered, state.configurationSort).slice(0, 25);
  section.appendChild(renderConfigurationTable(visibleProperties, state, actions));
  if (filtered.length > 25) {
    section.appendChild(
      toggleButton(
        state.configurationExpanded ? 'Show fewer configuration rows' : `Show all configuration rows (${filtered.length})`,
        actions.onToggleConfigurationExpanded
      )
    );
  }
  return section;
}

function renderSpringApiUsageSection(result: AnalyzeRepositoryResponse): HTMLElement {
  const section = resultsSection(
    'Spring API usage',
    'results-spring-api',
    'Detected Spring Boot starters, likely auto-configuration signals, and framework APIs used by the application.'
  );
  const starters = detectSpringBootStarters(result);
  const autoConfigurations = inferLikelyAutoConfigurations(result, starters);
  const apiSignals = detectSpringApiSignals(result);

  if (starters.length === 0 && autoConfigurations.length === 0 && apiSignals.length === 0) {
    section.appendChild(element('p', { className: 'muted-text', text: 'No Spring API usage patterns were detected.' }));
    return section;
  }

  if (starters.length > 0) {
    section.appendChild(element('h4', { text: `Detected Spring Boot starters (${starters.length})` }));
    const starterGrid = element('div', { className: 'summary-grid runtime-grid starter-grid' });
    for (const starter of starters) {
      starterGrid.appendChild(
        element(
          'div',
          { className: 'summary-card compact-summary-card starter-card' },
          element('div', { className: 'summary-label', text: starter.artifact }),
          element('div', { className: 'summary-value', text: starter.label }),
          element('div', { className: 'compact-card-meta', text: starter.description })
        )
      );
    }
    section.appendChild(starterGrid);
  }

  if (autoConfigurations.length > 0) {
    section.appendChild(element('h4', { text: 'Likely auto-configurations' }));
    const configGrid = element('div', { className: 'summary-grid runtime-grid' });
    for (const configuration of autoConfigurations) {
      configGrid.appendChild(
        element(
          'div',
          { className: 'summary-card compact-summary-card' },
          element('div', { className: 'summary-value', text: configuration })
        )
      );
    }
    section.appendChild(configGrid);
    section.appendChild(
      element(
        'div',
        { className: 'empty-note' },
        element('p', {
          className: 'muted-text',
          text: 'Static analysis can infer likely auto-configurations, but active runtime conditions may differ by profile, classpath, and environment.'
        })
      )
    );
  }

  if (apiSignals.length > 0) {
    section.appendChild(element('h4', { text: 'Detected Spring API signals' }));
    const grid = element('div', { className: 'summary-grid runtime-grid' });
    for (const signal of apiSignals) {
      grid.appendChild(
        element(
          'div',
          { className: 'summary-card compact-summary-card' },
          element('div', { className: 'summary-label', text: signal.category }),
          element('div', { className: 'summary-value', text: signal.values.join(', ') })
        )
      );
    }
    section.appendChild(grid);
  }
  return section;
}

function renderComponentsSection(
  components: DetectedClass[],
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const section = resultsSection(
    `Components (${components.length})`,
    'results-components',
    'Detected Spring components, configuration classes, repositories, entities, and application entry points.',
    [sectionChip(`${components.length} detected`, 'info')]
  );
  const grouped = groupComponents(components);
  if (grouped.size > 0) {
    const summary = element('div', { className: 'stat-grid compact' });
    for (const [componentType, entries] of grouped.entries()) {
      summary.appendChild(
        element(
          'div',
          { className: 'mini-stat' },
          element('div', { className: 'mini-stat-label', text: componentTypeLabel(componentType) }),
          element('div', { className: 'mini-stat-value', text: String(entries.length) })
        )
      );
    }
    section.appendChild(summary);
  }

  section.appendChild(
    element(
      'div',
      { className: 'filter-row compact-filter-row' },
      labeledInlineField(
        'Type',
        selectInput(
          COMPONENT_TYPES.map((value) => ({
            value,
            label: value === 'ALL' ? 'All component types' : componentTypeLabel(value)
          })),
          state.componentType,
          actions.onComponentTypeChange,
          'results-components-type'
        )
      ),
      labeledInlineField(
        'Text',
        textInput(state.componentText, 'Filter components', actions.onComponentTextChange, 'results-components-text')
      )
    )
  );

  const filtered = components.filter((component) => {
    const componentType = normalizeComponentType(component);
    const typeMatches = state.componentType === 'ALL' || componentType === state.componentType;
    const textNeedle = state.componentText.trim().toLowerCase();
    const haystack = [
      componentDisplayName(component),
      component.packageName,
      component.filePath,
      ...(component.annotationNames ?? component.annotations ?? [])
    ]
      .filter((value): value is string => Boolean(value))
      .join(' ')
      .toLowerCase();
    return typeMatches && (!textNeedle || haystack.includes(textNeedle));
  });

  if (filtered.length === 0) {
    section.appendChild(element('p', { className: 'muted-text', text: 'No components match the current filters.' }));
    return section;
  }

  const visible = state.componentsExpanded
    ? sortComponents(filtered, state.componentsSort)
    : sortComponents(filtered, state.componentsSort).slice(0, 25);
  const table = createTable([
    sortableHeader('Class', 'class', state.componentsSort, actions.onSetComponentsSort),
    sortableHeader('Type', 'type', state.componentsSort, actions.onSetComponentsSort),
    sortableHeader('Package', 'package', state.componentsSort, actions.onSetComponentsSort),
    sortableHeader('Source', 'source', state.componentsSort, actions.onSetComponentsSort),
    'Annotations'
  ], 'components-table');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const component of visible) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, [
      componentClassCell(component),
      badgeCell(componentTypeLabel(normalizeComponentType(component)), `badge badge-kind`),
      technicalClampCell(component.packageName ?? '—', undefined, 'component-package-cell'),
      technicalClampCell(component.filePath ?? '—', undefined, 'component-source-cell'),
      componentAnnotationsCell(component)
    ]);
  }
  section.appendChild(wrapTable(table));
  if (filtered.length > 25) {
    section.appendChild(
      toggleButton(
        state.componentsExpanded ? 'Show fewer components' : `Show all components (${filtered.length})`,
        actions.onToggleComponentsExpanded
      )
    );
  }
  return section;
}

function renderDependenciesSection(
  result: AnalyzeRepositoryResponse,
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const dependencies = result.dependencies ?? [];
  const gradleModel = result.gradleModelAnalysis;
  const successfulResolvedDependencies = successfulResolvedGradleDependencies(gradleModel);
  const resolutionResults = gradleModel?.resolutionResults ?? [];
  const dependencyBearingResults = dependencyBearingGradleResolutionResults(gradleModel);
  const configurationOptions = uniqueValues(successfulResolvedDependencies.map((dependency) => dependency.configuration));
  const section = resultsSection(
    'Dependencies',
    'results-dependencies',
    'Declared and resolved dependency information, managed stack versions, and dependency-resolution signals.',
    [sectionChip(dependencyModelLabel(gradleModel), 'info')]
  );
  const successfulResolutionResults = dependencyBearingResults.filter(
    (item) => item.attempted && item.successful && (item.resolvedDependencyCount ?? 0) > 0
  );
  const failedResolutionResults = dependencyBearingResults.filter((item) => item.attempted && !item.successful);
  const staticOnlyMode = isStaticOnlyDependencyMode(gradleModel);
  const summaryCards = staticOnlyMode
    ? [
        ['Static dependencies detected', String(dependencies.length)],
        ['Gradle model', gradleModelSummaryLabel(gradleModel)],
        ['Gradle-resolved entries', String(successfulResolvedDependencies.length)],
        ['Unique Gradle-resolved modules', String(uniqueResolvedModuleCount(gradleModel))],
        ['Resolved configurations', `${successfulResolutionResults.length}/${dependencyBearingResults.length || 0}`]
      ]
    : [
        ['Declared dependencies', String((gradleModel?.declaredDependencies ?? []).length)],
        ['Resolved entries', String(successfulResolvedDependencies.length)],
        ['Unique resolved modules', String(uniqueResolvedModuleCount(gradleModel))],
        ['Resolved configurations', `${successfulResolutionResults.length}/${dependencyBearingResults.length || 0}`],
        ['Dependency conflicts', String((gradleModel?.dependencyConflicts ?? []).length)]
      ];
  const summary = element('div', { className: 'summary-grid runtime-grid' });
  for (const [label, value] of summaryCards as Array<[string, string]>) {
    summary.appendChild(
      element(
        'div',
        {
          className:
            label === 'Resolved configurations' && failedResolutionResults.length > 0
              ? 'summary-card compact-summary-card warning-note'
              : 'summary-card compact-summary-card'
        },
        element('div', { className: 'summary-label', text: label }),
        element('div', { className: 'summary-value', text: value })
      )
    );
  }
  section.appendChild(summary);

  if (staticOnlyMode && result.buildTool === 'GRADLE') {
    section.appendChild(
      element(
        'div',
        { className: 'empty-note' },
        element('div', { className: 'subsection-title', text: 'Analysis precision note' }),
        element('p', {
          className: 'muted-text',
          text: 'Gradle model analysis was not requested. Dependency versions are inferred from static build files, so resolved transitive dependencies and exact managed versions may be incomplete.'
        })
      )
    );
  }

  const managedStackEntries = resolvedStackEntries(gradleModel);
  if (managedStackEntries.length > 0) {
    const managedStack = element(
      'div',
      { className: 'empty-note' },
      element('div', { className: 'subsection-title', text: 'Managed stack' })
    );
    const stackGrid = element('div', { className: 'property-detail-grid' });
    for (const entry of managedStackEntries) {
      stackGrid.appendChild(propertyDetailItem(entry.label, entry.version));
    }
    managedStack.appendChild(stackGrid);
    section.appendChild(managedStack);
  }

  const needle = state.dependencyText.trim().toLowerCase();
  const declared = (gradleModel?.declaredDependencies ?? []).filter((dependency) =>
    !needle || [dependency.projectPath, dependency.configuration, dependency.notation]
      .filter((value): value is string => Boolean(value))
      .join(' ')
      .toLowerCase()
      .includes(needle)
  );
  const resolved = successfulResolvedDependencies.filter((dependency) =>
    (state.resolvedDependencyConfiguration === 'ALL' || (dependency.configuration ?? '') === state.resolvedDependencyConfiguration)
      && (!state.resolvedDependencyDirectOnly || Boolean(dependency.direct))
      && (!needle || [dependency.projectPath, dependency.configuration, dependency.group, dependency.artifact, dependency.version, dependency.selectedReason, selectedReasonSummary(dependency)]
      .filter((value): value is string => Boolean(value))
      .join(' ')
      .toLowerCase()
      .includes(needle))
  );
  const filtered = dependencies.filter((dependency) => dependency.toLowerCase().includes(needle));
  section.appendChild(renderDependencyInsights(result, declared, resolved, failedResolutionResults));

  const controls = element('div', { className: 'filter-row compact-filter-row' });
  controls.append(
    labeledInlineField(
      'Search',
      textInput(state.dependencyText, 'Filter dependencies', actions.onDependencyTextChange, 'results-dependencies-search')
    ),
    labeledInlineField(
      'Configuration',
      selectInput(
        [{ value: 'ALL', label: 'All configurations' }, ...configurationOptions.map((value) => ({ value, label: value }))],
        state.resolvedDependencyConfiguration,
        actions.onResolvedDependencyConfigurationChange,
        'results-dependencies-configuration'
      )
    ),
    labeledInlineField(
      'Direct only',
      checkboxField(
        'Only direct dependencies',
        state.resolvedDependencyDirectOnly,
        actions.onResolvedDependencyDirectOnlyChange,
        'results-dependencies-direct-only'
      )
    )
  );
  section.appendChild(controls);
  if (filtered.length === 0 && resolved.length === 0 && declared.length === 0) {
    section.appendChild(element('p', { className: 'muted-text', text: 'No dependencies match the current filter.' }));
    return section;
  }

  if (successfulResolvedDependencies.length === 0 && failedResolutionResults.length > 0) {
    const failedConfigurations = failedResolutionResults
      .map((item) => `${item.projectPath ?? ':'}:${item.configuration ?? 'unknown'}`)
      .join(', ');
    section.appendChild(
      element(
        'div',
        { className: 'empty-note warning-note' },
        element('div', { className: 'subsection-title', text: 'Dependency graph resolution did not succeed' }),
        element('p', {
          className: 'muted-text',
          text:
            'Gradle ran successfully, but dependency graph resolution failed for dependency-bearing configurations.'
        }),
        element('p', {
          className: 'muted-text',
          text: failedConfigurations ? `Failed configurations: ${failedConfigurations}` : 'Failed configurations were captured in the build model.'
        })
      )
    );
  }

  if (declared.length > 0) {
    section.appendChild(element('h4', { text: 'Declared dependencies' }));
    const table = createTable(['Project', 'Configuration', 'Notation', 'Source'], 'dependencies-table');
    const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
    for (const dependency of declared.slice(0, 50)) {
      const row = tbody.insertRow();
      row.className = 'data-row';
      appendCells(row, [
        truncateCell(dependency.projectPath ?? '—'),
        truncateCell(dependency.configuration ?? '—'),
        truncateCell(dependency.notation ?? '—'),
        truncateCell('Gradle model')
      ]);
    }
    section.appendChild(wrapTable(table));
  }

  if (resolved.length > 0) {
    const details = element('details', { className: 'subsection-block advanced-details-block' });
    details.appendChild(element('summary', { text: `Advanced resolved dependency graph (${resolved.length})` }));
    const inner = element('div', { className: 'subsection-block-inner' });
    inner.appendChild(
      element('p', {
        className: 'muted-text',
        text: 'Resolved dependencies are kept available for deep inspection, but collapsed by default because transitive graphs are usually much noisier than declared dependencies.'
      })
    );
    const table = createTable([
      'Project',
      'Configuration',
      sortableHeader('Group', 'group', state.dependenciesSort, actions.onSetDependenciesSort),
      sortableHeader('Artifact', 'artifact', state.dependenciesSort, actions.onSetDependenciesSort),
      sortableHeader('Version', 'version', state.dependenciesSort, actions.onSetDependenciesSort),
      'Direct / transitive',
      'Selected reason'
    ], 'dependencies-table');
    const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
    for (const dependency of sortResolvedDependencies(resolved, state.dependenciesSort).slice(0, 50)) {
      const row = tbody.insertRow();
      row.className = 'data-row';
      appendCells(row, [
        truncateCell(dependency.projectPath ?? '—'),
        truncateCell(dependency.configuration ?? '—'),
        truncateCell(dependency.group ?? ''),
        truncateCell(dependency.artifact ?? ''),
        truncateCell(dependency.version ?? ''),
        truncateCell(dependency.direct ? 'Direct' : 'Transitive'),
        truncateCellWithTitle(
          selectedReasonSummary(dependency),
          dependency.selectedReason ?? selectedReasonSummary(dependency),
          'cell-wrap-two'
        )
      ]);
    }
    inner.appendChild(wrapTable(table));
    details.appendChild(inner);
    section.appendChild(details);
  }

  if (resolutionResults.length > 0) {
    const details = element('details', { className: 'subsection-block advanced-details-block' });
    details.appendChild(element('summary', { text: `Resolution results (${resolutionResults.length})` }));
    const inner = element('div', { className: 'subsection-block-inner' });
    const table = createTable(
      ['Project', 'Configuration', 'Status', 'Fallback', 'Resolved count', 'Error'],
      'dependencies-table'
    );
    const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
    for (const resultItem of resolutionResults.slice(0, 50)) {
      const row = tbody.insertRow();
      row.className = 'data-row';
      appendCells(row, [
        truncateCell(resultItem.projectPath ?? '—'),
        truncateCell(resultItem.configuration ?? '—'),
        badgeCell(resultItem.successful ? 'Resolved' : 'Failed', resultItem.successful ? 'badge badge-success' : 'badge badge-warning'),
        truncateCell(resultItem.fallbackUsed ? 'Lenient fallback' : 'Primary'),
        truncateCell(String(resultItem.resolvedDependencyCount ?? 0)),
        truncateCell(resultItem.errorMessage ?? '—', 'cell-wrap-two')
      ]);
    }
    inner.appendChild(wrapTable(table));
    details.appendChild(inner);
    section.appendChild(details);
  }

  if ((gradleModel?.dependencyConflicts ?? []).length > 0) {
    const details = element('details', { className: 'subsection-block advanced-details-block' });
    details.appendChild(element('summary', { text: `Dependency conflicts (${(gradleModel?.dependencyConflicts ?? []).length})` }));
    const inner = element('div', { className: 'subsection-block-inner' });
    const table = createTable(['Project', 'Configuration', 'Module', 'Requested', 'Selected'], 'dependencies-table');
    const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
    for (const conflict of (gradleModel?.dependencyConflicts ?? []).slice(0, 50)) {
      const row = tbody.insertRow();
      row.className = 'data-row';
      appendCells(row, [
        truncateCell(conflict.projectPath ?? '—'),
        truncateCell(conflict.configuration ?? '—'),
        truncateCell(`${conflict.group ?? ''}:${conflict.artifact ?? ''}`),
        truncateCell(conflict.requestedVersions ?? '—'),
        truncateCell(conflict.selectedVersion ?? '—')
      ]);
    }
    inner.appendChild(wrapTable(table));
    details.appendChild(inner);
    section.appendChild(details);
  }

  if (resolved.length === 0 && declared.length === 0 && filtered.length > 0) {
    const sorted = sortDependencies(filtered, state.dependenciesSort);
    const table = createTable([
      'Dependency',
      sortableHeader('Group', 'group', state.dependenciesSort, actions.onSetDependenciesSort),
      sortableHeader('Artifact', 'artifact', state.dependenciesSort, actions.onSetDependenciesSort),
      sortableHeader('Version', 'version', state.dependenciesSort, actions.onSetDependenciesSort)
    ], 'dependencies-table');
    const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
    for (const dependency of sorted) {
      const parsed = parseDependency(dependency);
      const row = tbody.insertRow();
      row.className = 'data-row';
      appendCells(row, [
        truncateCell(dependency),
        truncateCell(parsed.group),
        truncateCell(parsed.artifact),
        truncateCell(parsed.version)
      ]);
    }
    section.appendChild(wrapTable(table));
  }
  return section;
}

function renderRawJsonSection(
  result: AnalyzeRepositoryResponse,
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const details = element('details', { className: 'raw-json' }) as HTMLDetailsElement;
  if (state.rawJsonExpanded) {
    details.open = true;
  }
  details.addEventListener('toggle', () => actions.onRawJsonExpandedChange(details.open));
  details.appendChild(element('summary', { text: 'Raw response JSON' }));
  if (state.rawJsonExpanded) {
    details.appendChild(element('pre', { text: JSON.stringify(redactSensitiveValues(result), null, 2) }));
  }
  return details;
}

function renderBuildModelSection(gradleModel: GradleModelAnalysis | undefined): HTMLElement {
  const section = resultsSection(
    'Build model',
    'results-build-model',
    'Gradle model collection status, plugins, configurations, source sets, and analyzer execution details.',
    gradleModel ? [sectionChip(gradleModelSummaryLabel(gradleModel), gradleModel.status === 'SUCCESS' || gradleModel.status === 'SUCCESS_WITH_WORKAROUND' ? 'success' : 'warning')] : undefined
  );
  if (!gradleModel || gradleModel.status === 'NOT_REQUESTED') {
    section.appendChild(
      element(
        'div',
        { className: 'empty-note' },
        element('div', { className: 'subsection-title', text: 'Gradle model analysis was not requested.' }),
        element('p', {
          className: 'muted-text',
          text: 'Gradle model analysis was not requested. Dependency versions are inferred from static build files, so resolved transitive dependencies and exact managed versions may be incomplete.'
        })
      )
    );
    return section;
  }
  if (gradleModel.status === 'DISABLED') {
    section.appendChild(
      element(
        'div',
        { className: 'empty-note' },
        element('div', { className: 'subsection-title', text: 'Gradle model analysis is disabled.' }),
        element('p', {
          className: 'muted-text',
          text: 'The analyzer is configured not to run Gradle model analysis, so dependency versions are inferred from static build files only.'
        })
      )
    );
    return section;
  }

  const bridge = gradleModel.pluginResolutionBridge;
  const resolvedPlugins = bridge?.resolvedPlugins ?? [];
  const bridgeFailures = bridge?.failures ?? [];
  const successfulResolvedDependencies = successfulResolvedGradleDependencies(gradleModel);
  const resolutionResults = gradleModel.resolutionResults ?? [];
  const dependencyBearingResults = dependencyBearingGradleResolutionResults(gradleModel);
  const successfulResolutionResults = dependencyBearingResults.filter(
    (item) => item.attempted && item.successful && (item.resolvedDependencyCount ?? 0) > 0
  );
  const failedResolutionResults = dependencyBearingResults.filter((item) => item.attempted && !item.successful);
  const repositories = dedupeGradleRepositories(gradleModel.repositories ?? []);

  const summary = element('div', { className: 'summary-grid runtime-grid' });
  for (const [label, value] of [
    ['Status', gradleModel.status ?? 'Unknown'],
    ['Gradle version', gradleModel.gradleVersion ?? 'Unknown'],
    ['Java version', gradleModel.javaVersion ?? 'Unknown'],
    ['Projects', String((gradleModel.projects ?? []).length)],
    ['Declared dependencies', String((gradleModel.declaredDependencies ?? []).length)],
    ['Resolved entries', String(successfulResolvedDependencies.length)],
    ['Unique resolved modules', String(uniqueResolvedModuleCount(gradleModel))],
    ['Resolved configurations', `${successfulResolutionResults.length}/${dependencyBearingResults.length || 0}`],
    ['Failed configurations', String(failedResolutionResults.length)],
    ['Repositories', String(repositories.length)],
    ['Declared plugins', String((gradleModel.pluginDeclarations ?? []).length)],
    ['Plugins prefetched', String(resolvedPlugins.length)],
    ['Bridge status', bridgeStatusLabel(gradleModel.pluginBridgeStatus)],
    ['Failed plugins', String(bridgeFailures.length)],
    ['Source sets', String((gradleModel.sourceSets ?? []).length)],
    ['Tasks', String((gradleModel.tasks ?? []).length)]
  ] as Array<[string, string]>) {
    summary.appendChild(
      element(
        'div',
        {
          className:
            ((label === 'Status') && (gradleModel.status === 'PARTIAL' || gradleModel.status === 'FAILED' || gradleModel.status === 'TIMED_OUT'))
              || (label === 'Failed configurations' && failedResolutionResults.length > 0)
              ? 'summary-card compact-summary-card warning-note'
              : 'summary-card compact-summary-card'
        },
        element('div', { className: 'summary-label', text: label }),
        element('div', { className: 'summary-value', text: value })
      )
    );
  }
  section.appendChild(summary);

  if (gradleModel.status && (gradleModel.status === 'PARTIAL' || gradleModel.status === 'FAILED' || gradleModel.status === 'TIMED_OUT')) {
    section.appendChild(renderGradleFailurePanel(gradleModel));
  } else if ((gradleModel.status === 'SUCCESS' || gradleModel.status === 'SUCCESS_WITH_WORKAROUND') && !gradleModel.failureType && !gradleModel.errorMessage) {
    section.appendChild(
      element(
        'div',
        {
          className:
            successfulResolvedDependencies.length === 0 && failedResolutionResults.length > 0
              ? 'empty-note warning-note'
              : 'empty-note'
        },
        element('div', { className: 'subsection-title', text: 'Gradle model collected successfully.' }),
        element('p', {
          className: 'muted-text',
          text:
            successfulResolvedDependencies.length === 0 && failedResolutionResults.length > 0
              ? 'Gradle executed successfully, but no resolved dependencies were collected because dependency graph resolution failed for dependency-bearing configurations.'
              : 'Declared dependencies, repositories, configurations, and Gradle metadata were collected successfully.'
        })
      )
    );
  } else if (gradleModel.errorMessage || gradleModel.failureType) {
    section.appendChild(
      element(
        'div',
        { className: 'empty-note warning-note' },
        element('div', {
          className: 'subsection-title',
          text: `Reason${gradleModel.failureType ? ` (${gradleModel.failureType.replace(/_/g, ' ')})` : ''}`
        }),
        element('p', {
          className: 'muted-text',
          text: gradleModel.errorMessage ?? 'Gradle model analysis did not complete.'
        })
      )
    );
  }

  section.appendChild(
    element(
      'div',
      { className: 'compact-card-list' },
      element('p', {
        className: 'muted-text',
        text: `Execution mode: ${gradleModel.executionMode ?? 'Unknown'}`
      }),
      element('p', {
        className: 'muted-text',
        text: `Gradle version: ${gradleModel.gradleVersion ?? 'Unknown'} | Java: ${gradleModel.javaVersion ?? 'Unknown'}`
      })
    )
  );

  if (failedResolutionResults.length > 0) {
    section.appendChild(
      element(
        'div',
        { className: 'empty-note warning-note' },
        element('div', { className: 'subsection-title', text: 'Dependency resolution health' }),
        element('p', {
          className: 'muted-text',
          text: `Resolved dependency-bearing configurations: ${successfulResolutionResults.length}/${dependencyBearingResults.length || 0}. Failed configurations: ${failedResolutionResults.map((item) => `${item.projectPath ?? ':'}:${item.configuration ?? 'unknown'}`).join(', ')}`
        })
      )
    );
  }

  if (gradleModel.pluginBridgeUsed) {
    section.appendChild(
      element(
        'div',
        { className: 'empty-note' },
        element('div', { className: 'subsection-title', text: 'Plugin resolution used local analyzer cache' }),
        element('p', {
          className: 'muted-text',
          text:
            'Declared Gradle plugins were prefetched into a local analyzer cache before plugin resolution. Static source analysis still used the original cloned repository.'
        })
      )
    );
  }

  const visibleAppliedPlugins = filterVisibleAppliedPlugins(gradleModel.plugins ?? []);
  const highlightCards = element('div', { className: 'summary-grid runtime-grid build-highlight-grid' });
  highlightCards.append(
    buildCompactSummaryCard('User-relevant plugins', visibleAppliedPlugins.map((plugin) => plugin.pluginId ?? plugin.implementationClass ?? 'Unknown').slice(0, 4).join(', ') || 'None'),
    buildCompactSummaryCard('Source sets', (gradleModel.sourceSets ?? []).map((item) => item.name ?? 'unknown').slice(0, 4).join(', ') || 'None'),
    buildCompactSummaryCard('Java toolchain', summarizeJavaToolchain(gradleModel.javaToolchains ?? [])),
    buildCompactSummaryCard('Repositories', repositories.slice(0, 3).map((item) => normalizedRepositoryName(item)).join(', ') || 'None')
  );
  section.appendChild(highlightCards);

  if ((gradleModel.findings ?? []).length > 0) {
    const list = element('ul', { className: 'simple-list' });
    for (const finding of gradleModel.findings ?? []) {
      list.appendChild(element('li', { className: 'muted-text', text: finding.message ?? 'Gradle model finding' }));
    }
    section.appendChild(list);
  }

  const advanced = element('details', { className: 'subsection-block build-model-details advanced-details-block' });
  advanced.appendChild(element('summary', { text: 'Advanced Gradle internals' }));
  const advancedInner = element('div', { className: 'subsection-block-inner' });

  if (repositories.length > 0) {
    appendBuildModelDetailsTable(
      advancedInner,
      'Repositories',
      ['Project', 'Name', 'Type', 'URL'],
      repositories.slice(0, 50),
      (item: GradleRepositoryModel) => [
        truncateCell(item.projectPath ?? '—'),
        truncateCellWithTitle(normalizedRepositoryName(item), item.url ?? item.name ?? '—'),
        truncateCell(item.type ?? '—'),
        truncateCellWithTitle(normalizedRepositoryUrlLabel(item), item.url ?? normalizedRepositoryUrlLabel(item))
      ]
    );
  }

  if ((gradleModel.pluginDeclarations ?? []).length > 0) {
    appendBuildModelDetailsTable(
      advancedInner,
      'Declared plugins',
      ['Plugin ID', 'Version', 'Source', 'Prefetched', 'Implementation'],
      (gradleModel.pluginDeclarations ?? []).slice(0, 50),
      (item: GradlePluginDeclaration) => {
        const resolved = resolvedPlugins.find(
          (plugin) => plugin.pluginId === item.pluginId && plugin.version === item.version
        );
        return [
          truncateCell(item.pluginId ?? '—'),
          truncateCell(item.version ?? '—'),
          truncateCell(sourceLabel(item.sourceFile, item.line)),
          truncateCell(resolved ? 'yes' : 'no'),
          truncateCell(resolved?.implementationCoordinates ?? '—')
        ];
      }
    );
  }

  if (resolutionResults.length > 0) {
    appendBuildModelDetailsTable(
      advancedInner,
      'Configuration resolution results',
      ['Project', 'Configuration', 'Status', 'Fallback', 'Resolved count', 'Error'],
      resolutionResults.slice(0, 50),
      (item: GradleResolutionResult) => [
        truncateCell(item.projectPath ?? '—'),
        truncateCell(item.configuration ?? '—'),
        badgeCell(item.successful ? 'Resolved' : 'Failed', item.successful ? 'badge badge-success' : 'badge badge-warning'),
        truncateCell(item.fallbackUsed ? 'Lenient fallback' : 'Primary'),
        truncateCell(String(item.resolvedDependencyCount ?? 0)),
        truncateCell(item.errorMessage ?? '—', 'cell-wrap-two')
      ]
    );
  }

  if ((bridgeFailures ?? []).length > 0) {
    appendBuildModelDetailsTable(
      advancedInner,
      'Plugin bridge failures',
      ['Plugin ID', 'Version', 'Source', 'Marker', 'Implementation', 'Message'],
      bridgeFailures.slice(0, 25),
      (item: GradlePluginBridgeFailure) => [
        truncateCell(item.pluginId ?? '—'),
        truncateCell(item.version ?? '—'),
        truncateCell(sourceLabel(item.sourceFile, item.line)),
        truncateCell(item.markerPresentLocally ? 'cached' : 'missing'),
        truncateCell(item.implementationPresentLocally ? item.implementationCoordinates ?? 'cached' : 'missing'),
        truncateCell(item.message ?? 'Resolution failed', 'cell-wrap-two')
      ]
    );
  }

  if (visibleAppliedPlugins.length > 0) {
    appendBuildModelDetailsTable(advancedInner, 'Applied plugins', ['Project', 'Plugin', 'Implementation'], visibleAppliedPlugins.slice(0, 25), (item: GradlePluginModel) => [
      truncateCell(item.projectPath ?? '—'),
      truncateCell(item.pluginId ?? '—'),
      truncateCell(item.implementationClass ?? '—')
    ]);
  }
  const hiddenAppliedPlugins = (gradleModel.plugins ?? []).filter((item) => !visibleAppliedPlugins.includes(item));
  if (hiddenAppliedPlugins.length > 0) {
    appendBuildModelDetailsTable(advancedInner, 'All applied plugins', ['Project', 'Plugin', 'Implementation'], (gradleModel.plugins ?? []).slice(0, 50), (item: GradlePluginModel) => [
      truncateCell(item.projectPath ?? '—'),
      truncateCell(item.pluginId ?? '—'),
      truncateCell(item.implementationClass ?? '—')
    ]);
  }

  if ((gradleModel.settingsPlugins ?? []).length > 0) {
    appendBuildModelDetailsTable(
      advancedInner,
      'Settings plugins',
      ['Plugin ID', 'Version', 'Source'],
      (gradleModel.settingsPlugins ?? []).slice(0, 25),
      (item: GradleSettingsPluginModel) => [
        truncateCell(item.pluginId ?? '—'),
        truncateCell(item.version ?? '—'),
        truncateCell(sourceLabel(item.sourceFile, item.line))
      ]
    );
  }

  appendBuildModelDetailsTable(advancedInner, 'Configurations', ['Project', 'Configuration', 'Resolvable', 'Consumable', 'Declared', 'All', 'Extends from'], (gradleModel.configurations ?? []).slice(0, 25), (item: GradleConfigurationModel) => [
    truncateCell(item.projectPath ?? '—'),
    truncateCell(item.name ?? '—'),
    truncateCell(String(item.resolvable ?? false)),
    truncateCell(String(item.consumable ?? false)),
    truncateCell(String(item.declaredDependencyCount ?? item.dependencyCount ?? 0)),
    truncateCell(String(item.allDependencyCount ?? item.dependencyCount ?? 0)),
    truncateCell((item.extendsFrom ?? []).join(', ') || '—')
  ]);
  appendBuildModelDetailsTable(advancedInner, 'Source sets', ['Project', 'Source set', 'Java dirs', 'Resource dirs'], (gradleModel.sourceSets ?? []).slice(0, 25), (item: GradleSourceSetModel) => [
    truncateCell(item.projectPath ?? '—'),
    truncateCell(item.name ?? '—'),
    truncateCell((item.javaDirs ?? []).join(', ') || '—'),
    truncateCell((item.resourceDirs ?? []).join(', ') || '—')
  ]);
  appendBuildModelDetailsTable(advancedInner, 'Java toolchains', ['Project', 'Language', 'Vendor', 'Implementation'], (gradleModel.javaToolchains ?? []).slice(0, 25), (item: GradleJavaToolchainModel) => [
    truncateCell(item.projectPath ?? '—'),
    truncateCell(item.languageVersion ?? '—'),
    truncateCell(item.vendor ?? '—'),
    truncateCell(item.implementation ?? '—')
  ]);
  appendBuildModelDetailsTable(advancedInner, 'Dependency conflicts', ['Project', 'Configuration', 'Module', 'Requested', 'Selected'], (gradleModel.dependencyConflicts ?? []).slice(0, 25), (item: GradleDependencyConflict) => [
    truncateCell(item.projectPath ?? '—'),
    truncateCell(item.configuration ?? '—'),
    truncateCell(`${item.group ?? ''}:${item.artifact ?? ''}`),
    truncateCell(item.requestedVersions ?? '—'),
    truncateCell(item.selectedVersion ?? '—')
  ]);
  appendBuildModelDetailsTable(advancedInner, 'Tasks', ['Project', 'Task', 'Group', 'Description'], (gradleModel.tasks ?? []).slice(0, 25), (item: GradleTaskModel) => [
    truncateCell(item.projectPath ?? '—'),
    truncateCell(item.name ?? '—'),
    truncateCell(item.group ?? '—'),
    truncateCell(item.description ?? '—')
  ]);

  advanced.appendChild(advancedInner);
  section.appendChild(advanced);
  return section;
}

function renderGradleFailurePanel(gradleModel: GradleModelAnalysis): HTMLElement {
  const failure = (gradleModel.pluginResolutionFailures ?? [])[0];
  const bridge = gradleModel.pluginResolutionBridge;
  const bridgeFailure = (bridge?.failures ?? []).find(
    (item) => item.pluginId === failure?.pluginId && item.version === failure?.version
  );
  if ((gradleModel.failureType === 'SETTINGS_PLUGIN_RESOLUTION_FAILED' || gradleModel.failureType === 'PLUGIN_RESOLUTION_FAILED') && failure) {
    const panel = element('div', { className: 'empty-note warning-note' });
    panel.append(
      element('div', { className: 'subsection-title', text: 'Gradle model analysis partial' }),
      detailParagraph(
        'Reason',
        'Gradle plugin resolution failed before the analyzer diagnostic task could complete. The analyzer tried to bridge declared plugins through a local analyzer plugin cache.'
      ),
      detailParagraph('Plugin', `${failure.pluginId ?? 'Unknown'}${failure.version ? `:${failure.version}` : ''}`),
      detailParagraph('Location', sourceLabel(failure.settingsFile, failure.line)),
      detailParagraph('Marker artifact', failure.artifact ?? 'Not captured'),
      detailParagraph(
        'Searched repositories',
        (failure.searchedRepositories ?? []).join(', ') || 'No repository details were captured.'
      ),
      detailParagraph(
        'Explanation',
        bridge?.successful
          ? 'The static analyzer still completed. The local plugin cache was populated, but Gradle still could not configure the target build.'
          : 'The static analyzer still completed. Build-aware dependency resolution is unavailable because Gradle could not configure the target build.'
      ),
      detailParagraph(
        'Suggested fixes',
        'Check analyzer proxy or certificate settings, confirm plugin repositories in analyzer.gradle.plugin-resolution-bridge.repositories, or run Static only mode if Gradle-resolved dependencies are not needed.'
      )
    );
    if (bridgeFailure) {
      panel.appendChild(
        detailParagraph(
          'Local cache state',
          `Marker ${bridgeFailure.markerPresentLocally ? 'present' : 'missing'}, implementation ${bridgeFailure.implementationPresentLocally ? 'present' : 'missing'}`
        )
      );
    }
    return panel;
  }

  return element(
    'div',
    { className: 'empty-note warning-note' },
    element('div', {
      className: 'subsection-title',
      text: `Reason${gradleModel.failureType ? ` (${gradleModel.failureType.replace(/_/g, ' ')})` : ''}`
    }),
    element('p', {
      className: 'muted-text',
      text: gradleModel.errorMessage ?? 'Gradle model analysis did not complete.'
    })
  );
}

function detailParagraph(label: string, text: string): HTMLElement {
  return element(
    'p',
    { className: 'muted-text' },
    element('strong', { text: `${label}: ` }),
    document.createTextNode(text)
  );
}

function bridgeStatusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'LOCAL_PLUGIN_CACHE_USED':
      return 'Local cache used';
    case 'LOCAL_PLUGIN_CACHE_PARTIAL':
      return 'Local cache partial';
    case 'LOCAL_PLUGIN_CACHE_FAILED':
      return 'Local cache failed';
    default:
      return 'Not used';
  }
}

function renderConfigurationTable(
  properties: ApplicationProperty[],
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const table = createTable(['', 'Property', 'Value', 'Profile', 'Source', 'Kind', 'Type', 'Meaning'], 'configuration-table');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;

  for (const property of properties) {
    const key = configurationRowKey(property);
    const expanded = state.configurationExpandedRowKey === key;
    const row = tbody.insertRow();
    row.className = 'data-row';

    const expandCell = row.insertCell();
    const expandButton = element('button', {
      className: 'table-expand-button',
      text: expanded ? '\u25BE' : '\u25B8',
      attributes: {
        type: 'button',
        'aria-label': expanded
          ? `Collapse details for ${property.name ?? 'configuration property'}`
          : `Expand details for ${property.name ?? 'configuration property'}`
      }
    });
    expandButton.addEventListener('click', () => {
      actions.onToggleConfigurationRow(key);
    });
    expandCell.appendChild(expandButton);

    appendCells(row, [
      truncateCell(property.name ?? 'Unknown property'),
      truncateCell(displayPropertyValue(property)),
      truncateCell(property.profile ?? 'default'),
      truncateCell(sourceLabel(property.sourceFile, property.line)),
      badgeCell(configurationKindLabel(property.kind ?? 'UNKNOWN'), `badge ${configurationBadgeClass(property.kind)}`),
      truncateCell(property.documentation?.type ?? 'Unknown type'),
      truncateCell(compactPropertyMeaning(property))
    ]);

    if (expanded) {
      const detailsRow = tbody.insertRow();
      detailsRow.className = 'details-row';
      const detailsCell = detailsRow.insertCell();
      detailsCell.colSpan = 8;
      detailsCell.appendChild(renderConfigurationDetails(property));
    }
  }

  return wrapTable(table);
}

function renderConfigurationDetails(property: ApplicationProperty): HTMLElement {
  const card = element('div', { className: 'property-detail-card' });
  const header = element(
    'div',
    { className: 'property-detail-header' },
    element(
      'div',
      {},
      element('div', { className: 'property-detail-title', text: property.name ?? 'Unknown property' }),
      element('div', {
        className: 'property-detail-subtitle',
        text: `${configurationKindLabel(property.kind ?? 'UNKNOWN')} from ${sourceLabel(property.sourceFile, property.line)}`
      })
    ),
    element('span', {
      className: `badge ${configurationBadgeClass(property.kind)}`,
      text: configurationKindLabel(property.kind ?? 'UNKNOWN')
    })
  );

  const grid = element('div', { className: 'property-detail-grid' });
  grid.append(
    propertyDetailItem('Value', displayPropertyValue(property)),
    propertyDetailItem('Profile', property.profile ?? 'default'),
    propertyDetailItem('Source', sourceLabel(property.sourceFile, property.line)),
    propertyDetailItem('Type', property.documentation?.type ?? 'Unknown'),
    propertyDetailItem('Default value', property.documentation?.defaultValue ?? 'Not documented'),
    propertyDetailItem('Source type', property.documentation?.sourceType ?? 'Not documented')
  );

  card.append(header, grid);
  card.appendChild(propertyDetailSection('Meaning', propertyMeaning(property)));

  if (property.documentation?.deprecated) {
    card.appendChild(
      propertyDetailSection(
        'Deprecation',
        property.documentation.deprecationReason ?? 'This property is marked as deprecated.'
      )
    );
  }
  if ((property.documentation?.hints ?? []).length > 0) {
    const hints = property.documentation?.hints ?? [];
    card.appendChild(
      propertyDetailSection(
        'Value hints',
        hints.map((hint) => [hint.value, hint.description].filter(Boolean).join(' - ')).join('; ')
      )
    );
  }
  if ((property.references ?? []).length > 0) {
    card.appendChild(propertyDetailSection('Code references', formatReferences(property.references ?? [])));
  }

  return card;
}

function renderProfileComparisonTable(
  groups: Array<{ name: string; valuesByProfile: Map<string, string>; kind: string; meaning: string; changed: boolean }>,
  profiles: string[]
): HTMLElement {
  const headers = ['Property', ...profiles, 'Kind', 'Meaning'];
  const table = createTable(headers, 'configuration-compare-table');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const group of groups) {
    const row = tbody.insertRow();
    row.className = group.changed ? 'data-row changed-row' : 'data-row';
    row.appendChild(textCell(group.name));
    for (const profile of profiles) {
      row.appendChild(truncateCell(group.valuesByProfile.get(profile) ?? 'Not configured', group.changed ? 'changed-value' : undefined));
    }
    row.appendChild(truncateCell(group.kind));
    row.appendChild(truncateCell(group.meaning));
  }
  return wrapTable(table);
}

function renderResultsHeader(status?: {
  statusMessage?: string;
  errorMessage?: string;
  warningMessage?: string;
  isAnalyzing?: boolean;
}): HTMLElement {
  const header = element('div', { className: 'results-header' });
  header.appendChild(element('h2', { text: 'Results' }));
  const right = element('div', { className: 'results-header-status' });
  if (status?.errorMessage) {
    right.appendChild(element('span', { className: 'status-chip status-chip-error', text: status.errorMessage }));
  } else if (status?.warningMessage) {
    right.appendChild(element('span', { className: 'status-chip status-chip-warning', text: status.warningMessage }));
  } else if (status?.statusMessage) {
    right.appendChild(
      element('span', {
        className: status.isAnalyzing ? 'status-chip status-chip-info' : 'status-chip status-chip-success',
        text: status.statusMessage
      })
    );
  }
  header.appendChild(right);
  return header;
}

function resultsSection(title: string, id?: string, description?: string, chips: SectionChip[] = []): HTMLElement {
  const section = element('section', { className: 'results-section', attributes: id ? { id } : undefined });
  const header = element('div', { className: 'results-section-header' });
  const copy = element('div', { className: 'results-section-copy' });
  copy.appendChild(element('h3', { className: 'results-section-title', text: title }));
  if (description?.trim()) {
    copy.appendChild(element('p', { className: 'results-section-description', text: description.trim() }));
  }
  header.appendChild(copy);
  if (chips.length > 0) {
    const chipRow = element('div', { className: 'results-section-chips' });
    chips.forEach((chip) => chipRow.appendChild(sectionChipElement(chip)));
    header.appendChild(chipRow);
  }
  section.appendChild(header);
  return section;
}

function summaryInsightCard(title: string, description: string): HTMLElement {
  return element(
    'div',
    { className: 'summary-card insight-summary-card' },
    element('div', { className: 'summary-value insight-summary-title', text: title }),
    element('div', { className: 'compact-card-meta insight-summary-description', text: description })
  );
}

function renderDetailRow(label: string, value: string): HTMLElement {
  return element(
    'div',
    { className: 'detail-row overview-detail-row' },
    element('div', { className: 'detail-label', text: label }),
    element('div', { className: 'detail-value property-detail-value', text: value })
  );
}

function sectionChip(text: string, tone: SectionChipTone = 'default'): SectionChip {
  return { text, tone };
}

function sectionChipElement(chip: SectionChip): HTMLElement {
  const toneClass = chip.tone && chip.tone !== 'default' ? ` status-chip-${chip.tone}` : '';
  return element('span', { className: `status-chip${toneClass}`.trim(), text: chip.text });
}

function buildCompactSummaryCard(label: string, value: string, meta?: string | null): HTMLElement {
  return element(
    'div',
    { className: 'summary-card compact-summary-card' },
    element('div', { className: 'summary-label', text: label }),
    element('div', { className: 'summary-value', text: value }),
    meta ? element('div', { className: 'compact-card-meta', text: meta }) : null
  );
}

function wrapTable(table: HTMLTableElement): HTMLElement {
  return element('div', { className: 'data-table-wrapper' }, table);
}

function renderJavaModelCard(result: AnalyzeRepositoryResponse): HTMLElement {
  const runtime = result.runtimeStackAnalysis ?? {};
  const gradleModel = result.gradleModelAnalysis;
  const toolchainVersions = uniqueValues((gradleModel?.javaToolchains ?? []).map((toolchain) => toolchain.languageVersion));
  const detectedJava = runtime.javaVersion ?? result.javaVersionHint ?? gradleModel?.javaVersion ?? 'Unknown';
  const previewDetected = false;
  const card = element(
    'div',
    { className: 'detail-card java-model-card' },
    element('div', { className: 'subsection-title', text: 'Java model' }),
    element('div', { className: 'compact-card-meta', text: 'Static Java/runtime/build compatibility signals from source and Gradle metadata.' })
  );
  const grid = element('div', { className: 'property-detail-grid' });
  [
    ['Detected Java', detectedJava],
    ['Toolchain language version', toolchainVersions.join(', ') || 'Not confirmed statically'],
    ['Source compatibility', 'Not explicitly configured'],
    ['Target compatibility', 'Not explicitly configured'],
    ['Preview features', previewDetected ? 'Detected' : 'Not detected'],
    ['Virtual threads', virtualThreadLabel(runtime)],
    ['Compatibility note', springBootCompatibilityNote(result)]
  ].forEach(([label, value]) => grid.appendChild(propertyDetailItem(label, value)));
  card.appendChild(grid);
  return card;
}

function renderDependencyInsights(
  result: AnalyzeRepositoryResponse,
  declared: GradleDependencyModel[],
  resolved: GradleResolvedDependencyModel[],
  failedResolutionResults: GradleResolutionResult[]
): HTMLElement {
  const explicitVersions = declared.filter((dependency) => Boolean(dependency.version?.trim()));
  const duplicateMajorVersions = countDuplicateMajorVersions(resolved);
  const conflicts = result.gradleModelAnalysis?.dependencyConflicts ?? [];
  const card = element(
    'div',
    { className: 'empty-note dependency-insights-card' },
    element('div', { className: 'subsection-title', text: 'Dependency insights' })
  );
  const grid = element('div', { className: 'property-detail-grid' });
  [
    ['Dependency conflicts', String(conflicts.length)],
    ['Explicit declared versions', String(explicitVersions.length)],
    ['Unresolved configurations', String(failedResolutionResults.length)],
    ['Duplicate major versions', String(duplicateMajorVersions)]
  ].forEach(([label, value]) => grid.appendChild(propertyDetailItem(label, value)));
  card.appendChild(grid);
  if (explicitVersions.length > 0) {
    card.appendChild(
      element('p', {
        className: 'muted-text',
        text: `Declared dependencies with explicit versions include ${explicitVersions.slice(0, 4).map((dependency) => dependency.notation ?? `${dependency.group ?? ''}:${dependency.artifact ?? ''}:${dependency.version ?? ''}`).join(', ')}${explicitVersions.length > 4 ? ', ...' : ''}.`
      })
    );
  }
  return card;
}

function detectSpringBootStarters(
  result: AnalyzeRepositoryResponse
): Array<{ artifact: string; label: string; description: string }> {
  const dependencies = collectDependencyNotations(result);
  const starters = [
    ['spring-boot-starter-web', 'Spring MVC + embedded servlet container', 'Servlet-based web stack and MVC auto-configuration are likely available.'],
    ['spring-boot-starter-webflux', 'Spring WebFlux + reactive stack', 'Reactive HTTP stack and WebFlux auto-configuration are likely available.'],
    ['spring-boot-starter-security', 'Spring Security auto-configuration likely active', 'Security filter chain and related security auto-configuration are likely on the classpath.'],
    ['spring-boot-starter-actuator', 'Operational endpoints available', 'Actuator endpoints and operational diagnostics are likely available.'],
    ['spring-boot-starter-jdbc', 'DataSource/JdbcTemplate stack available', 'JDBC access, DataSource configuration, and JdbcTemplate support are likely available.'],
    ['spring-boot-starter-data-jpa', 'Spring Data JPA stack available', 'JPA repositories, entity management, and Hibernate integration are likely available.'],
    ['spring-boot-starter-validation', 'Jakarta Bean Validation available', 'Bean validation APIs are likely available for configuration and request validation.'],
    ['spring-boot-starter-test', 'Spring Boot test support', 'Spring Boot test slices and testing support are available in the project.']
  ] as Array<[string, string, string]>;
  return starters
    .filter(([artifact]) => dependencies.some((dependency) => dependency.includes(artifact)))
    .map(([artifact, label, description]) => ({ artifact, label, description }));
}

function inferLikelyAutoConfigurations(
  result: AnalyzeRepositoryResponse,
  starters: Array<{ artifact: string; label: string; description: string }>
): string[] {
  const dependencies = collectDependencyNotations(result);
  const configurations = new Set<string>();
  const starterArtifacts = new Set(starters.map((starter) => starter.artifact));
  if (starterArtifacts.has('spring-boot-starter-web') || result.runtimeStackAnalysis?.webStack === 'SERVLET_MVC') {
    configurations.add('WebMvcAutoConfiguration');
  }
  if (starterArtifacts.has('spring-boot-starter-webflux') || result.runtimeStackAnalysis?.webStack === 'REACTIVE_WEBFLUX') {
    configurations.add('WebFluxAutoConfiguration');
  }
  if (starterArtifacts.has('spring-boot-starter-web') || starterArtifacts.has('spring-boot-starter-webflux') || dependencies.some((dependency) => dependency.includes('jackson-databind'))) {
    configurations.add('JacksonAutoConfiguration');
  }
  if (starterArtifacts.has('spring-boot-starter-security')) {
    configurations.add('SecurityAutoConfiguration');
  }
  if (starterArtifacts.has('spring-boot-starter-jdbc') || starterArtifacts.has('spring-boot-starter-data-jpa') || dependencies.some((dependency) => dependency.includes('com.zaxxer:HikariCP'))) {
    configurations.add('DataSourceAutoConfiguration');
    configurations.add('JdbcTemplateAutoConfiguration');
  }
  if (starterArtifacts.has('spring-boot-starter-data-jpa')) {
    configurations.add('HibernateJpaAutoConfiguration');
  }
  if (dependencies.some((dependency) => dependency.includes('org.flywaydb:flyway-core'))) {
    configurations.add('FlywayAutoConfiguration');
  }
  if (starterArtifacts.has('spring-boot-starter-actuator')) {
    configurations.add('EndpointAutoConfiguration');
    configurations.add('HealthEndpointAutoConfiguration');
  }
  return [...configurations];
}

function detectSpringApiSignals(
  result: AnalyzeRepositoryResponse
): Array<{ category: string; values: string[] }> {
  const groups = new Map<string, string[]>();
  const add = (category: string, value: string) => {
    const items = groups.get(category) ?? [];
    if (!items.includes(value)) {
      items.push(value);
    }
    groups.set(category, items);
  };
  if ((result.mainApplicationClasses ?? []).length > 0) {
    add('Bootstrap', '@SpringBootApplication');
    add('Bootstrap', 'SpringApplication.run (inferred)');
  }
  if ((result.configurationAnalysis?.configurationPropertiesClasses ?? []).length > 0) {
    add('Configuration', '@ConfigurationProperties');
  }
  if ((result.configurationAnalysis?.codeReferences ?? []).some((reference) => reference.referenceType === '@ConditionalOnProperty')) {
    add('Configuration', '@ConditionalOnProperty');
  }
  if ((result.configurationAnalysis?.codeReferences ?? []).some((reference) => reference.referenceType === '@Scheduled')) {
    add('Scheduling', '@Scheduled');
  }
  if ((result.detectedComponents ?? []).some((component) => component.componentType === 'REST_CONTROLLER')) {
    add('Web', '@RestController');
  }
  if ((result.detectedComponents ?? []).some((component) => (component.annotationNames ?? []).includes('RequestMapping'))) {
    add('Web', '@RequestMapping');
  }
  if ((result.detectedComponents ?? []).some((component) => component.componentType === 'REPOSITORY')) {
    add('Persistence', '@Repository');
  }
  if ((result.detectedComponents ?? []).some((component) => (component.annotationNames ?? []).includes('EnableScheduling'))) {
    add('Scheduling', '@EnableScheduling');
  }
  return ['Bootstrap', 'Web', 'Configuration', 'Scheduling', 'Persistence', 'Actuator']
    .map((category) => ({ category, values: groups.get(category) ?? [] }))
    .filter((entry) => entry.values.length > 0);
}

function collectDependencyNotations(result: AnalyzeRepositoryResponse): string[] {
  const collected = new Set<string>();
  (result.dependencies ?? []).forEach((dependency) => collected.add(dependency));
  (result.gradleModelAnalysis?.declaredDependencies ?? []).forEach((dependency) => {
    const notation = dependency.notation?.trim()
      || [dependency.group, dependency.artifact, dependency.version].filter((value) => Boolean(value?.trim())).join(':');
    if (notation) {
      collected.add(notation);
    }
  });
  successfulResolvedGradleDependencies(result.gradleModelAnalysis).forEach((dependency) => {
    const notation = [dependency.group, dependency.artifact, dependency.version]
      .filter((value) => Boolean(value?.trim()))
      .join(':');
    if (notation) {
      collected.add(notation);
    }
  });
  return [...collected];
}

function inferPersistenceStyle(dependencies: string[]): string {
  const hasJpa = dependencies.some((dependency) => dependency.includes('spring-boot-starter-data-jpa'));
  const hasJdbc = dependencies.some((dependency) => dependency.includes('spring-boot-starter-jdbc'));
  const hasR2dbc = dependencies.some((dependency) => dependency.includes('spring-boot-starter-data-r2dbc') || dependency.includes(':r2dbc-'));
  if (hasJpa && hasJdbc) {
    return 'JPA + JDBC';
  }
  if (hasJpa) {
    return 'JPA';
  }
  if (hasJdbc) {
    return 'JDBC';
  }
  if (hasR2dbc) {
    return 'R2DBC';
  }
  return 'Not confirmed statically';
}

function inferDatabaseSignal(result: AnalyzeRepositoryResponse, profiles: string[]): string | null {
  const properties = result.configurationAnalysis?.properties ?? [];
  const matchingProperties = properties.filter((property) => profiles.includes(normalizedProfile(property.profile ?? 'default')));
  const datasourceProperty = matchingProperties.find((property) => property.name === 'spring.datasource.url');
  const driverProperty = matchingProperties.find((property) => property.name === 'spring.datasource.driver-class-name');
  const values = [datasourceProperty?.value ?? null, driverProperty?.value ?? null];
  for (const value of values) {
    const inferred = inferDatabaseNameFromValue(value);
    if (inferred) {
      return inferred;
    }
  }
  const dependencies = collectDependencyNotations(result);
  const fromDependency = inferDatabaseNameFromDependencies(dependencies, profiles.includes('test'));
  return fromDependency;
}

function inferDatabaseNameFromValue(value: string | null): string | null {
  const normalized = defaultText(value).toLowerCase();
  if (!normalized) {
    return null;
  }
  if (normalized.includes('postgresql')) {
    return 'PostgreSQL (inferred)';
  }
  if (normalized.includes('h2')) {
    return 'H2 (inferred)';
  }
  if (normalized.includes('mysql')) {
    return 'MySQL (inferred)';
  }
  if (normalized.includes('mariadb')) {
    return 'MariaDB (inferred)';
  }
  if (normalized.includes('sqlserver')) {
    return 'SQL Server (inferred)';
  }
  if (normalized.includes('oracle')) {
    return 'Oracle (inferred)';
  }
  return null;
}

function inferDatabaseNameFromDependencies(dependencies: string[], testProfile: boolean): string | null {
  const checks: Array<[string, string]> = [
    ['org.postgresql:postgresql', 'PostgreSQL (inferred)'],
    ['com.h2database:h2', 'H2 (inferred)'],
    ['mysql:mysql-connector', 'MySQL (inferred)'],
    ['org.mariadb.jdbc:mariadb-java-client', 'MariaDB (inferred)'],
    ['com.microsoft.sqlserver:mssql-jdbc', 'SQL Server (inferred)'],
    ['com.oracle.database.jdbc', 'Oracle (inferred)']
  ];
  for (const [needle, label] of checks) {
    if (dependencies.some((dependency) => dependency.includes(needle))) {
      if (testProfile && !label.startsWith('H2')) {
        continue;
      }
      return label;
    }
  }
  return null;
}

function springBootCompatibilityNote(result: AnalyzeRepositoryResponse): string {
  const boot = defaultText(result.runtimeStackAnalysis?.springBootVersion);
  const javaVersion = defaultText(result.runtimeStackAnalysis?.javaVersion, result.javaVersionHint ?? undefined);
  if (!boot || !javaVersion) {
    return 'No obvious compatibility mismatch confirmed statically.';
  }
  const javaMajor = parseInt(javaVersion, 10);
  if (!Number.isFinite(javaMajor)) {
    return 'No obvious compatibility mismatch confirmed statically.';
  }
  if (boot.startsWith('3.') && javaMajor >= 17) {
    return `Spring Boot ${boot} and Java ${javaVersion} do not show an obvious static compatibility mismatch. Verify production JVM and dependency support.`;
  }
  if (boot.startsWith('3.') && javaMajor < 17) {
    return `Spring Boot ${boot} usually expects a newer Java baseline than ${javaVersion}. Review the build and runtime toolchain configuration.`;
  }
  return 'Verify the final production JVM and dependency compatibility for this Spring Boot / Java combination.';
}

function countDuplicateMajorVersions(resolved: GradleResolvedDependencyModel[]): number {
  const versionsByModule = new Map<string, Set<string>>();
  for (const dependency of resolved) {
    const module = `${dependency.group ?? ''}:${dependency.artifact ?? ''}`;
    const version = dependency.version ?? '';
    if (!module.trim() || !version.trim()) {
      continue;
    }
    const set = versionsByModule.get(module) ?? new Set<string>();
    const major = version.split('.')[0];
    if (major) {
      set.add(major);
      versionsByModule.set(module, set);
    }
  }
  return [...versionsByModule.values()].filter((majors) => majors.size > 1).length;
}

function summarizeJavaToolchain(toolchains: GradleJavaToolchainModel[]): string {
  const languages = uniqueValues(toolchains.map((toolchain) => toolchain.languageVersion));
  if (languages.length === 0) {
    return 'Not confirmed statically';
  }
  const vendors = uniqueValues(toolchains.map((toolchain) => toolchain.vendor));
  return vendors.length > 0 ? `${languages.join(', ')} (${vendors.join(', ')})` : languages.join(', ');
}

function appendBuildModelTable<T>(
  section: HTMLElement,
  title: string,
  headers: Array<string | HTMLElement>,
  items: T[],
  cells: (item: T) => HTMLTableCellElement[]
): void {
  if (items.length === 0) {
    return;
  }
  section.appendChild(element('h4', { text: title }));
  const table = createTable(headers, 'dependencies-table');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const item of items) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, cells(item));
  }
  section.appendChild(wrapTable(table));
}

function appendBuildModelDetailsTable<T>(
  section: HTMLElement,
  title: string,
  headers: Array<string | HTMLElement>,
  items: T[],
  cells: (item: T) => HTMLTableCellElement[]
): void {
  if (items.length === 0) {
    return;
  }
  const details = element('details', { className: 'subsection-block build-model-details' });
  details.appendChild(element('summary', { text: `${title} (${items.length})` }));
  const inner = element('div', { className: 'subsection-block-inner' });
  const table = createTable(headers, 'dependencies-table');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const item of items) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, cells(item));
  }
  inner.appendChild(wrapTable(table));
  details.appendChild(inner);
  section.appendChild(details);
}

function createTable(headers: Array<string | HTMLElement>, className: string): HTMLTableElement {
  const table = element('table', { className: `data-table ${className}` }) as HTMLTableElement;
  const thead = table.createTHead();
  const headerRow = thead.insertRow();
  for (const header of headers) {
    const th = document.createElement('th');
    th.scope = 'col';
    if (typeof header === 'string') {
      th.textContent = header;
    } else {
      th.appendChild(header);
    }
    headerRow.appendChild(th);
  }
  table.createTBody();
  return table;
}

function appendCells(row: HTMLTableRowElement, cells: HTMLTableCellElement[]): void {
  for (const cell of cells) {
    row.appendChild(cell);
  }
}

function textCell(text: string, className?: string): HTMLTableCellElement {
  const cell = document.createElement('td');
  if (className) {
    cell.className = className;
  }
  cell.textContent = text;
  return cell;
}

function truncateCell(text: string, extraClassName?: string): HTMLTableCellElement {
  const cell = document.createElement('td');
  const value = text || '—';
  cell.className = extraClassName ? `cell-truncate ${extraClassName}` : 'cell-truncate';
  cell.textContent = value;
  cell.title = value;
  return cell;
}

function truncateCellWithTitle(text: string, title: string, extraClassName?: string): HTMLTableCellElement {
  const cell = document.createElement('td');
  const value = text || '—';
  cell.className = extraClassName ? `cell-truncate ${extraClassName}` : 'cell-truncate';
  cell.textContent = value;
  cell.title = title || value;
  return cell;
}

function technicalClampCell(text: string, title?: string, extraClassName?: string): HTMLTableCellElement {
  const cell = document.createElement('td');
  const value = text || '—';
  if (extraClassName) {
    cell.className = extraClassName;
  }
  cell.title = title || value;
  cell.appendChild(element('div', { className: 'cell-technical-wrap', text: value }));
  return cell;
}

function outboundUrlCell(endpoint: OutboundEndpoint): HTMLTableCellElement {
  const value = endpoint.fullUrlPreview ?? endpoint.urlOrTemplate ?? 'Unknown';
  const cell = document.createElement('td');
  cell.className = 'cell-outbound-url';
  cell.title = value;
  cell.appendChild(element('div', { className: 'cell-truncate', text: value }));
  if (!endpoint.fullUrlPreview && (endpoint.urlOrTemplate ?? '').startsWith('/')) {
    cell.appendChild(
      element('div', {
        className: 'cell-inline-meta',
        text: 'Base URL not resolved statically.'
      })
    );
  }
  return cell;
}

function findingSummaryCell(
  finding: GroupedPresentedFinding | PresentedFinding,
  _detailsId: string
): HTMLTableCellElement {
  const cell = document.createElement('td');
  cell.className = 'finding-main-cell';
  const grouped = 'occurrences' in finding;
  const summaryText = finding.summary || finding.message || 'No summary available';
  const wrapper = element('div', { className: 'finding-summary-block' });
  const titleRow = element('div', { className: 'finding-summary-title-row' });
  titleRow.appendChild(
    element('div', {
      className: grouped && finding.occurrences > 1 ? 'finding-summary-kicker grouped' : 'finding-summary-kicker',
      text: grouped && finding.occurrences > 1 ? `Grouped pattern · ${finding.occurrences} occurrences` : finding.category
    })
  );
  wrapper.appendChild(titleRow);
  wrapper.appendChild(
    element('div', {
      className: 'finding-summary-title cell-wrap-two',
      text: finding.title || finding.ruleType,
      attributes: { title: finding.title || finding.ruleType }
    })
  );
  if (finding.target && finding.target !== '—') {
    wrapper.appendChild(
      element('div', {
        className: 'finding-summary-target property-detail-value',
        text: finding.target,
        attributes: { title: finding.target }
      })
    );
  }
  wrapper.appendChild(
    element('div', {
      className: 'finding-summary-text cell-wrap-two',
      text: summaryText,
      attributes: { title: summaryText }
    })
  );
  const meta = element('div', { className: 'finding-summary-meta-row' });
  meta.append(
    element('span', {
      className: 'badge badge-confidence',
      text: finding.confidence,
      attributes: { title: `Confidence: ${finding.confidence}` }
    }),
    element('span', {
      className: 'badge badge-runtime',
      text: finding.runtimeDetection,
      attributes: { title: `Runtime detection: ${finding.runtimeDetection}` }
    })
  );
  if (grouped && finding.occurrences > 1) {
    meta.appendChild(
      element('span', {
        className: 'finding-summary-count',
        text: `${finding.occurrences} occurrences`
      })
    );
  }
  if (finding.heuristic) {
    meta.appendChild(
      element('span', {
        className: 'badge badge-heuristic',
        text: 'Review manually',
        attributes: { title: 'This finding is heuristic and should be reviewed manually.' }
      })
    );
  }
  wrapper.appendChild(meta);
  cell.appendChild(wrapper);
  cell.title = grouped
    ? finding.items.map((item) => `${item.location} — ${item.summary}`).join('\n')
    : `${finding.location} — ${finding.summary}`;
  return cell;
}

function findingLocationCell(finding: GroupedPresentedFinding | PresentedFinding): HTMLTableCellElement {
  const cell = document.createElement('td');
  cell.className = 'finding-location-cell';
  const fullLocation = 'occurrences' in finding
    ? uniqueValues(finding.items.map((item) => item.location)).join('; ')
    : finding.location;
  const locationBlock = element('div', {
    className: finding.locationKnown ? 'finding-location-block is-exact' : 'finding-location-block'
  });
  locationBlock.appendChild(
    element('div', {
      className: 'finding-location-path',
      text: finding.locationShort || finding.location || '—',
      attributes: { title: fullLocation || '—' }
    })
  );
  if (finding.locationMeta) {
    locationBlock.appendChild(
      element('div', {
        className: 'finding-location-meta',
        text: finding.locationMeta,
        attributes: { title: finding.locationMeta }
      })
    );
  }
  cell.appendChild(locationBlock);
  return cell;
}

function findingActionsCell(
  finding: GroupedPresentedFinding | PresentedFinding,
  detailsId: string,
  codeButtonId: string
): HTMLTableCellElement {
  const cell = document.createElement('td');
  cell.className = 'finding-actions-cell';
  const actions = element('div', { className: 'finding-summary-actions' });
  const hasAnyCodeLocation = hasFindingCodeLocation(finding);
  actions.appendChild(
    element('button', {
      className: 'secondary-button finding-row-action finding-expand-button',
      text: 'Details',
      attributes: {
        type: 'button',
        'aria-expanded': 'false',
        'aria-controls': detailsId,
        'aria-label': `Show details for ${finding.title || finding.ruleType}${finding.target && finding.target !== '—' ? ` — ${finding.target}` : ''}`
      }
    })
  );
  if (hasAnyCodeLocation) {
    actions.appendChild(
      element('button', {
        className: 'secondary-button finding-row-action finding-code-button',
        text: 'View code',
        attributes: {
          id: codeButtonId,
          type: 'button',
          'aria-label': `View code for ${finding.title || finding.ruleType}${finding.target && finding.target !== '—' ? ` — ${finding.target}` : ''}`
        }
      })
    );
  }
  cell.appendChild(actions);
  return cell;
}

function renderFindingDetailsCard(
  finding: GroupedPresentedFinding | PresentedFinding,
  detailsId: string,
  actions: ResultsViewActions
): HTMLElement {
  const grouped = 'occurrences' in finding;
  const primary = finding;
  const mixedSeverity = grouped && uniqueValues(finding.items.map((item) => item.severity)).length > 1;
  const card = element('div', {
    className: 'finding-detail-card',
    attributes: { id: detailsId }
  });
  const header = element('div', { className: 'finding-detail-header' });
  const headerCopy = element('div', { className: 'finding-detail-header-copy' });
  headerCopy.append(
    element('div', {
      className: 'finding-detail-title',
      text: primary.title || primary.finding.title?.trim() || primary.ruleType
    }),
    element('div', {
      className: 'finding-detail-subtitle',
      text: primary.target && primary.target !== '—' ? `Target: ${primary.target}` : 'Target: —'
    }),
    element('div', {
      className: 'finding-detail-subtitle property-detail-value',
      text: `Source: ${primary.location || '—'}`
    })
  );
  const badgeRow = element('div', { className: 'finding-detail-badges' });
  for (const [text, className] of [
    [primary.severity, `badge badge-${primary.severity.toLowerCase()}`],
    [primary.category, 'badge badge-category'],
    [primary.confidence, 'badge badge-confidence'],
    [primary.runtimeDetection, 'badge badge-runtime']
  ] as Array<[string, string]>) {
    const title = className.includes(`badge-${primary.severity.toLowerCase()}`) ? severityExplanation(text) : text;
    badgeRow.appendChild(element('span', { className, text, attributes: { title } }));
  }
  if (mixedSeverity) {
    badgeRow.appendChild(
      element('span', {
        className: 'badge badge-heuristic',
        text: 'Mixed severity'
      })
    );
  }
  if (primary.heuristic) {
    badgeRow.appendChild(
      element('span', {
        className: 'badge badge-heuristic',
        text: 'Review manually'
      })
    );
  }
  header.append(headerCopy, badgeRow);
  card.append(header, renderFindingExplanationSections(primary.finding));

  if (grouped && finding.occurrences > 1) {
    const occurrenceSection = element('div', { className: 'property-detail-section' });
    occurrenceSection.appendChild(
      element('div', { className: 'property-detail-section-title', text: 'Occurrences' })
    );
    const list = element('ul', { className: 'simple-list compact-list finding-occurrence-list' });
    finding.items.forEach((item, index) => {
      const occurrence = element('li', { className: 'finding-occurrence-item' });
      const occurrenceCopy = element('div', { className: 'finding-occurrence-copy' });
      occurrenceCopy.appendChild(
        element('div', {
          className: 'property-detail-value',
          text: item.location || '—',
          attributes: { title: item.location || '—' }
        })
      );
      if (item.target && item.target !== '—') {
        occurrenceCopy.appendChild(
          element('div', {
            className: 'muted-text',
            text: item.target
          })
        );
      }
      occurrenceCopy.appendChild(
        element('div', {
          className: 'muted-text',
          text: `${item.severity} · ${item.confidence}`
        })
      );
      occurrenceCopy.appendChild(
        element('div', {
          className: 'muted-text',
          text: item.summary
        })
      );
      if (item.finding.evidence?.trim()) {
        occurrenceCopy.appendChild(
          element('div', {
            className: 'muted-text finding-occurrence-evidence',
            text: item.finding.evidence,
            attributes: { title: item.finding.evidence }
          })
        );
      }
      occurrence.appendChild(occurrenceCopy);
      if (hasFindingCodeLocation(item)) {
        const occurrenceButtonId = `${createFindingCodeButtonId(item)}-${index}`;
        const button = element('button', {
          className: 'secondary-button finding-row-action finding-occurrence-code-button',
          text: 'View code',
          attributes: {
            id: occurrenceButtonId,
            type: 'button',
            'aria-label': `View code for occurrence ${index + 1} of ${primary.title || primary.ruleType}`
          }
        });
        button.addEventListener('click', () => {
          actions.onOpenFindingCode(
            primary.finding,
            finding.items.map((entry) => entry.finding),
            occurrenceButtonId,
            index
          );
        });
        occurrence.appendChild(button);
      }
      list.appendChild(occurrence);
    });
    occurrenceSection.appendChild(list);
    card.appendChild(occurrenceSection);
  }

  return card;
}

function renderCodeSnippetModal(
  modalState: CodeSnippetModalState,
  actions: ResultsViewActions
): HTMLElement {
  const overlay = element('div', {
    className: 'code-snippet-modal-overlay',
    attributes: {
      role: 'dialog',
      'aria-modal': 'true',
      'aria-labelledby': 'code-snippet-modal-title'
    }
  });
  overlay.addEventListener('click', (event) => {
    if (event.target === overlay) {
      actions.onCloseFindingCode();
    }
  });
  overlay.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      actions.onCloseFindingCode();
    }
  });

  const modal = element('div', {
    className: 'code-snippet-modal',
    attributes: { tabindex: '-1' }
  });
  const currentOccurrence = modalState.occurrences[modalState.selectedOccurrenceIndex];

  const closeButton = element('button', {
    className: 'secondary-button modal-close-button',
    text: 'Close',
    attributes: {
      id: 'code-snippet-modal-close',
      type: 'button',
      'aria-label': 'Close code snippet dialog'
    }
  });
  closeButton.addEventListener('click', () => actions.onCloseFindingCode());

  const header = element('div', { className: 'code-snippet-modal-header' });
  const headerCopy = element('div', { className: 'code-snippet-modal-copy' });
  headerCopy.appendChild(
    element('div', {
      className: 'code-snippet-modal-title',
      text: modalState.title,
      attributes: { id: 'code-snippet-modal-title' }
    })
  );
  headerCopy.appendChild(
    element('div', {
      className: 'code-snippet-modal-subtitle property-detail-value',
      text: currentOccurrence?.location?.filePath ?? 'Source unavailable'
    })
  );
  headerCopy.appendChild(
    element('div', {
      className: 'code-snippet-modal-subtitle',
      text: currentOccurrence?.location ? lineRangeLabel(currentOccurrence.location) : 'Line range: —'
    })
  );
  if (currentOccurrence?.location?.symbol) {
    headerCopy.appendChild(
      element('div', {
        className: 'code-snippet-modal-subtitle',
        text: `Symbol: ${currentOccurrence.location.symbol}`
      })
    );
  }

  const badgeRow = element('div', { className: 'finding-detail-badges' });
  for (const [text, className] of [
    [modalState.severity, `badge badge-${modalState.severity.toLowerCase()}`],
    [findingCategoryLabel(normalizeFindingCategory(modalState.category)), 'badge badge-category'],
    [confidenceLabel(normalizeConfidence(modalState.confidence)), 'badge badge-confidence'],
    [runtimeDetectionLabel(normalizeRuntimeDetection(modalState.runtimeDetection)), 'badge badge-runtime']
  ] as Array<[string, string]>) {
    const title = className.includes(`badge-${modalState.severity.toLowerCase()}`) ? severityExplanation(text) : text;
    badgeRow.appendChild(element('span', { className, text, attributes: { title } }));
  }

  const actionRow = element('div', { className: 'code-snippet-modal-actions' });
  const copyPathButton = element('button', {
    className: 'secondary-button',
    text: 'Copy path',
    attributes: { type: 'button' }
  });
  copyPathButton.addEventListener('click', async () => {
    const path = currentOccurrence?.location?.filePath;
    if (!path) {
      return;
    }
    await navigator.clipboard.writeText(path);
    copyPathButton.textContent = 'Copied';
    window.setTimeout(() => {
      copyPathButton.textContent = 'Copy path';
    }, 1200);
  });
  actionRow.appendChild(copyPathButton);

  const copySummaryButton = element('button', {
    className: 'secondary-button',
    text: 'Copy finding summary',
    attributes: { type: 'button' }
  });
  copySummaryButton.addEventListener('click', async () => {
    await navigator.clipboard.writeText(`${modalState.title}: ${modalState.summary}`);
    copySummaryButton.textContent = 'Copied';
    window.setTimeout(() => {
      copySummaryButton.textContent = 'Copy finding summary';
    }, 1200);
  });
  actionRow.appendChild(copySummaryButton);

  const githubUrl = modalState.snippet?.githubUrl ?? currentOccurrence?.location?.githubUrl;
  if (githubUrl) {
    actionRow.appendChild(
      element('a', {
        className: 'secondary-button code-snippet-link-button',
        text: 'Open in GitHub',
        attributes: {
          href: githubUrl,
          target: '_blank',
          rel: 'noreferrer noopener'
        }
      })
    );
  }
  actionRow.appendChild(closeButton);

  header.append(headerCopy, element('div', { className: 'code-snippet-modal-meta' }, badgeRow, actionRow));
  modal.appendChild(header);

  if (modalState.occurrences.length > 1) {
    modal.appendChild(renderCodeOccurrenceSelector(modalState, actions));
  }

  if (modalState.loading) {
    modal.appendChild(element('div', { className: 'code-snippet-status', text: 'Loading source snippet...' }));
  } else if (modalState.errorMessage) {
    modal.appendChild(element('div', { className: 'code-snippet-status error-note', text: modalState.errorMessage }));
  } else if (modalState.snippet) {
    modal.appendChild(renderCodeSnippetViewer(modalState.snippet));
  }

  overlay.appendChild(modal);
  window.setTimeout(() => {
    (document.getElementById('code-snippet-modal-close') as HTMLButtonElement | null)?.focus();
  }, 0);
  return overlay;
}

function renderCodeOccurrenceSelector(
  modalState: CodeSnippetModalState,
  actions: ResultsViewActions
): HTMLElement {
  const section = element('div', { className: 'code-occurrence-selector' });
  section.appendChild(element('div', { className: 'property-detail-section-title', text: 'Occurrences' }));
  const list = element('div', { className: 'code-occurrence-list' });
  modalState.occurrences.forEach((occurrence, index) => {
    const button = element('button', {
      className: index === modalState.selectedOccurrenceIndex
        ? 'code-occurrence-button active'
        : 'code-occurrence-button',
      attributes: {
        type: 'button',
        'aria-pressed': index === modalState.selectedOccurrenceIndex ? 'true' : 'false'
      }
    });
    button.append(
      element('div', {
        className: 'property-detail-value',
        text: occurrence.location.filePath ?? 'Source unavailable'
      }),
      element('div', {
        className: 'muted-text',
        text: lineRangeLabel(occurrence.location)
      }),
      element('div', {
        className: 'muted-text',
        text: occurrence.summary
      })
    );
    button.addEventListener('click', () => actions.onSelectFindingCodeOccurrence(index));
    list.appendChild(button);
  });
  section.appendChild(list);
  return section;
}

function renderCodeSnippetViewer(snippet: SourceSnippetResponse): HTMLElement {
  const viewer = element('div', { className: 'code-snippet-viewer' });
  const scroller = element('div', { className: 'code-snippet-scroll' });
  const pre = element('pre', { className: 'code-snippet-pre' });
  const code = element('code', { className: 'code-snippet-code' });
  const lines = snippet.lines ?? [];
  const tokenized = (snippet.language ?? 'text') === 'java'
    ? tokenizeJavaLines(lines.map((line) => line.text))
    : null;
  const highlightRanges = expandSnippetHighlightRanges(snippet.language, lines, snippet.highlightRanges);

  lines.forEach((line, index) => {
    const row = element('div', {
      className: `code-snippet-line ${lineClassName(line.lineNumber, highlightRanges)}`.trim()
    });
    row.appendChild(
      element('span', {
        className: 'code-snippet-line-number',
        text: String(line.lineNumber)
      })
    );
    const content = element('span', { className: 'code-snippet-line-content' });
    const tokens = tokenized?.[index]?.tokens;
    if (tokens && tokens.length > 0) {
      for (const token of tokens) {
        content.appendChild(
          element('span', {
            className: `token-${token.type}`,
            text: token.text
          })
        );
      }
    } else {
      content.textContent = line.text;
    }
    row.appendChild(content);
    code.appendChild(row);
  });

  pre.appendChild(code);
  scroller.appendChild(pre);
  viewer.appendChild(scroller);
  return viewer;
}

function lineClassName(lineNumber: number, ranges: HighlightRange[]): string {
  const range = ranges.find((candidate) =>
    lineNumber >= (candidate.startLine ?? lineNumber) && lineNumber <= (candidate.endLine ?? lineNumber)
  );
  if (!range) {
    return '';
  }
  return range.kind === 'related' ? 'code-line-related' : 'code-line-highlight';
}

function hasFindingCodeLocation(finding: GroupedPresentedFinding | PresentedFinding): boolean {
  const findings = 'occurrences' in finding ? finding.items.map((item) => item.finding) : [finding.finding];
  return findings.some((item) => Boolean(primaryFindingLocation(item)?.filePath));
}

function createFindingCodeButtonId(finding: GroupedPresentedFinding | PresentedFinding): string {
  const seed = `${finding.ruleType}|${finding.target}|${finding.location}|${finding.message}`;
  return `finding-code-${hashString(seed)}`;
}

function lineRangeLabel(location: SourceLocation | undefined): string {
  if (!location?.startLine) {
    return 'Exact line not resolved statically';
  }
  if (!location.endLine || location.endLine <= location.startLine) {
    return `Line ${location.startLine}`;
  }
  return `Lines ${location.startLine}-${location.endLine}`;
}

function findingMetaLine(finding: PresentedFinding): HTMLElement {
  return element(
    'div',
    { className: 'finding-meta-line' },
    element('span', { className: 'muted-text', text: `Target: ${finding.target || '—'}` }),
    element('span', { className: 'muted-text', text: `Source: ${finding.location || '—'}` })
  );
}

function renderFindingExplanationSections(finding: Finding): HTMLElement {
  const wrapper = element('div', { className: 'finding-detail-grid' });
  const sections: Array<[string, string | null | undefined]> = [
    ['Why this is a bad pattern', finding.whyBadPractice],
    ['Possible impact', finding.possibleImpact],
    ['Recommendation', finding.recommendation],
    ['Evidence', finding.evidence ?? finding.message],
    ['Static analysis limitation', finding.limitations]
  ];
  for (const [title, text] of sections) {
    if (!text || !text.trim()) {
      continue;
    }
    wrapper.appendChild(propertyDetailSection(title, text));
  }
  return wrapper;
}

function badgeCell(text: string, badgeClass: string): HTMLTableCellElement {
  const cell = document.createElement('td');
  cell.className = 'cell-badge';
  const title = severityExplanation(text);
  const badge = element('span', {
    className: badgeClass,
    text,
    attributes: { title, 'aria-label': title }
  });
  cell.appendChild(badge);
  return cell;
}

function summaryValueRow(label: string, value: string, copyable = false): HTMLElement {
  const valueNode = element('div', {
    className: 'project-summary-value',
    text: value,
    attributes: { title: value }
  });
  const row = element(
    'div',
    { className: 'project-summary-row' },
    element('div', { className: 'project-summary-label', text: label }),
    element('div', { className: 'project-summary-main' }, valueNode)
  );
  if (copyable && value !== 'Unknown' && value !== 'Not returned') {
    const copyButton = element('button', {
      className: 'secondary-button copy-button',
      text: 'Copy',
      attributes: { type: 'button', title: `Copy ${label}` }
    });
    copyButton.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(value);
        copyButton.textContent = 'Copied';
        window.setTimeout(() => {
          copyButton.textContent = 'Copy';
        }, 1200);
      } catch {
        copyButton.textContent = 'Copy failed';
        window.setTimeout(() => {
          copyButton.textContent = 'Copy';
        }, 1200);
      }
    });
    (row.lastElementChild as HTMLElement).appendChild(copyButton);
  }
  return row;
}

function detailRow(label: string, value: string): HTMLElement {
  return element(
    'div',
    { className: 'detail-row' },
    element('span', { className: 'detail-label', text: label }),
    element('span', { className: 'detail-value', text: value })
  );
}

function propertyDetailItem(label: string, value: string): HTMLElement {
  return element(
    'div',
    { className: 'property-detail-item' },
    element('span', { className: 'property-detail-label', text: label }),
    element('span', { className: 'property-detail-value', text: value })
  );
}

function propertyDetailSection(title: string, text: string): HTMLElement {
  return element(
    'div',
    { className: 'property-detail-section' },
    element('div', { className: 'property-detail-section-title', text: title }),
    element('p', { className: 'property-detail-value', text })
  );
}

function renderRuntimeKeyValueRow(label: string, value: string, meta?: string | null, wide = false): HTMLElement {
  return element(
    'div',
    { className: wide ? 'runtime-kv-row runtime-kv-row-wide' : 'runtime-kv-row' },
    element('div', { className: 'runtime-kv-label', text: label }),
    element(
      'div',
      { className: 'runtime-kv-content' },
      element('div', {
        className: wide ? 'runtime-kv-value runtime-kv-value-wide' : 'runtime-kv-value',
        text: value,
        attributes: { title: value }
      }),
      meta
        ? element('div', {
            className: 'runtime-kv-meta',
            text: meta,
            attributes: { title: meta }
          })
        : null
    )
  );
}

function renderRuntimeMetric(
  label: string,
  value: string,
  meta?: string | null,
  warning = false
): HTMLElement {
  return element(
    'div',
    { className: warning ? 'runtime-metric runtime-metric-warning' : 'runtime-metric' },
    element('div', { className: 'runtime-metric-label', text: label }),
    element('div', { className: 'runtime-metric-value', text: value }),
    meta ? element('div', { className: 'runtime-metric-meta', text: meta }) : null
  );
}
function labeledInlineField(label: string, control: HTMLElement): HTMLElement {
  return element(
    'label',
    { className: 'inline-field' },
    element('span', { className: 'inline-field-label', text: label }),
    control
  );
}

function selectInput(
  options: Array<{ value: string; label: string }>,
  selectedValue: string,
  onChange: (value: string) => void,
  id?: string
): HTMLSelectElement {
  const select = element('select', { className: 'select-input', attributes: id ? { id } : undefined }) as HTMLSelectElement;
  for (const option of options) {
    select.appendChild(new Option(option.label, option.value, false, option.value === selectedValue));
  }
  select.value = selectedValue;
  select.addEventListener('change', () => onChange(select.value));
  return select;
}

function textInput(value: string, placeholder: string, onChange: (value: string) => void, id?: string): HTMLInputElement {
  const input = element('input', {
    className: 'text-input',
    attributes: { type: 'text', placeholder, ...(id ? { id } : {}) }
  }) as HTMLInputElement;
  input.value = value;
  input.addEventListener('input', () => onChange(input.value));
  return input;
}

function checkboxField(label: string, checked: boolean, onChange: (value: boolean) => void, id?: string): HTMLElement {
  const wrapper = element('label', { className: 'inline-checkbox' });
  const input = element('input', { attributes: { type: 'checkbox', ...(id ? { id } : {}) } }) as HTMLInputElement;
  input.checked = checked;
  input.addEventListener('change', () => onChange(input.checked));
  wrapper.append(input, element('span', { text: label }));
  return wrapper;
}

function toggleButton(text: string, onClick: () => void): HTMLElement {
  const button = element('button', {
    className: 'secondary-button',
    text,
    attributes: { type: 'button' }
  });
  button.addEventListener('click', onClick);
  return button;
}

function toggleChip(text: string, active: boolean, onClick: () => void): HTMLElement {
  const button = element('button', {
    className: active ? 'mode-button active' : 'mode-button',
    text,
    attributes: { type: 'button' }
  });
  button.addEventListener('click', onClick);
  return button;
}

function configurationMatches(
  property: ApplicationProperty,
  state: ResultsViewState,
  changedPropertyNames: Set<string>
): boolean {
  const searchNeedle = state.configurationSearch.trim().toLowerCase();
  const haystack = [
    property.name,
    property.value,
    property.profile,
    property.sourceFile,
    property.documentation?.type,
    property.documentation?.description
  ]
    .filter((value): value is string => Boolean(value))
    .join(' ')
    .toLowerCase();
  const normalizedKind = property.kind ?? 'UNKNOWN';
  const kindMatches =
    state.configurationKind === 'ALL' ||
    normalizedKind === state.configurationKind ||
    (state.configurationKind === 'CODE_REFERENCED' && normalizedKind === 'CONDITIONAL_PROPERTY');
  const profileMatches =
    state.configurationProfile === 'ALL' || (property.profile ?? 'default') === state.configurationProfile;
  const sourceMatches = state.configurationSource === 'ALL' || (property.sourceFile ?? '') === state.configurationSource;
  const sensitiveMatches = !state.configurationSensitiveOnly || Boolean(property.valueRedacted);
  const focusMatches = configurationFocusMatches(property, state.configurationFocus, changedPropertyNames);
  return (!searchNeedle || haystack.includes(searchNeedle))
    && kindMatches
    && profileMatches
    && sourceMatches
    && sensitiveMatches
    && focusMatches;
}

function normalizeMainApplicationClasses(
  value: AnalyzeRepositoryResponse['mainApplicationClasses']
): Array<{ name: string; filePath?: string }> {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((entry) =>
    typeof entry === 'string' ? { name: entry } : { name: componentDisplayName(entry), filePath: entry.filePath }
  );
}

function groupComponents(components: DetectedClass[]): Map<string, DetectedClass[]> {
  const grouped = new Map<string, DetectedClass[]>();
  for (const component of components) {
    const type = normalizeComponentType(component);
    const bucket = grouped.get(type) ?? [];
    bucket.push(component);
    grouped.set(type, bucket);
  }
  return new Map([...grouped.entries()].sort((left, right) => left[0].localeCompare(right[0])));
}

function normalizeSeverity(severity: string | undefined): string {
  const normalized = (severity ?? 'INFO').toUpperCase();
  return FINDING_SEVERITIES.includes(normalized as (typeof FINDING_SEVERITIES)[number]) ? normalized : 'INFO';
}

function normalizeComponentType(component: Partial<DetectedClass>): string {
  const value = (component.componentType ?? component.springComponentType ?? 'UNKNOWN').toUpperCase();
  return COMPONENT_TYPES.includes(value as (typeof COMPONENT_TYPES)[number]) ? value : 'UNKNOWN';
}

function componentTypeLabel(value: string): string {
  switch (value) {
    case 'MAIN_APPLICATION':
      return 'Main application';
    case 'REST_CONTROLLER':
      return 'REST controllers';
    case 'CONTROLLER':
      return 'Controllers';
    case 'SERVICE':
      return 'Services';
    case 'REPOSITORY':
      return 'Repositories';
    case 'COMPONENT':
      return 'Components';
    case 'CONFIGURATION':
      return 'Configurations';
    case 'CONFIGURATION_PROPERTIES':
      return 'Configuration properties';
    case 'ENTITY':
      return 'Entities';
    default:
      return 'Unknown';
  }
}

function severityLabel(value: string): string {
  switch (value) {
    case 'ERROR':
      return 'Errors';
    case 'WARNING':
      return 'Warnings';
    case 'INFO':
      return 'Info';
    default:
      return value;
  }
}

function severityExplanation(value: string): string {
  switch (normalizeSeverity(value)) {
    case 'ERROR':
      return 'Error — reserved for analyzer failures or other blocking conditions.';
    case 'WARNING':
      return 'Warning — prioritized static finding to review.';
    case 'INFO':
      return 'Info — lower-severity or heuristic finding.';
    default:
      return value;
  }
}

function renderSectionJumpNav(): HTMLElement {
  const nav = element('nav', { className: 'results-jump-nav', attributes: { 'aria-label': 'Results sections' } });
  for (const [label, href] of [
    ['Runtime', '#results-runtime'],
    ['Findings', '#results-findings'],
    ['HTTP', '#results-http'],
    ['Configuration', '#results-configuration'],
    ['Spring API', '#results-spring-api'],
    ['Components', '#results-components'],
    ['Dependencies', '#results-dependencies']
    ,['Build model', '#results-build-model']
  ] as Array<[string, string]>) {
    nav.appendChild(element('a', { className: 'results-jump-link', text: label, attributes: { href } }));
  }
  return nav;
}

function sortableHeader(
  label: string,
  key: string,
  sort: TableSortState,
  onSort: (key: string) => void
): HTMLElement {
  const sortIndicator = sort.key === key ? (sort.direction === 'asc' ? '↑' : '↓') : '';
  const button = element('button', {
    className: 'table-sort-button',
    text: sortIndicator ? `${label} ${sortIndicator}` : label,
    attributes: { type: 'button', 'aria-label': `Sort by ${label}` }
  });
  button.addEventListener('click', () => onSort(key));
  return button;
}

function configurationKindLabel(value: string): string {
  switch (value) {
    case 'SPRING_BOOT':
      return 'Spring Boot';
    case 'SPRING_BOOT_MAP_PROPERTY':
      return 'Spring Boot map property';
    case 'CUSTOM_CONFIGURATION_PROPERTIES':
      return 'Custom';
    case 'CODE_REFERENCED':
      return 'Code referenced';
    case 'CONDITIONAL_PROPERTY':
      return 'Conditional property';
    case 'THIRD_PARTY':
      return 'Third-party';
    case 'UNKNOWN':
      return 'Unknown';
    default:
      return value;
  }
}

function configurationFocusLabel(value: string): string {
  switch (value) {
    case 'REVIEW':
      return 'Review signals';
    case 'SPRING_BOOT':
      return 'Spring Boot properties';
    case 'CUSTOM':
      return 'Custom app config';
    case 'PROFILE_DIFFS':
      return 'Profile differences';
    case 'ALL':
    default:
      return 'All properties';
  }
}

function configurationFocusMatches(
  property: ApplicationProperty,
  focus: string,
  changedPropertyNames: Set<string>
): boolean {
  const kind = property.kind ?? 'UNKNOWN';
  const propertyName = property.name ?? '';
  switch (focus) {
    case 'REVIEW':
      return Boolean(property.valueRedacted)
        || kind === 'UNKNOWN'
        || kind === 'CODE_REFERENCED'
        || kind === 'CONDITIONAL_PROPERTY'
        || changedPropertyNames.has(propertyName);
    case 'SPRING_BOOT':
      return kind === 'SPRING_BOOT' || kind === 'SPRING_BOOT_MAP_PROPERTY';
    case 'CUSTOM':
      return kind === 'CUSTOM_CONFIGURATION_PROPERTIES';
    case 'PROFILE_DIFFS':
      return changedPropertyNames.has(propertyName);
    case 'ALL':
    default:
      return true;
  }
}

function configurationBadgeClass(kind: string | undefined): string {
  switch (kind) {
    case 'SPRING_BOOT':
    case 'SPRING_BOOT_MAP_PROPERTY':
      return 'badge-spring';
    case 'CUSTOM_CONFIGURATION_PROPERTIES':
      return 'badge-custom';
    case 'THIRD_PARTY':
      return 'badge-third-party';
    case 'UNKNOWN':
      return 'badge-warning';
    default:
      return 'badge-info';
  }
}

function webStackLabel(value: string | null | undefined): string {
  switch (value) {
    case 'SERVLET_MVC':
      return 'Spring MVC / Servlet';
    case 'REACTIVE_WEBFLUX':
      return 'WebFlux / Reactive';
    case 'MIXED_MVC_AND_WEBFLUX':
      return 'Mixed MVC + WebFlux';
    case 'NON_WEB':
      return 'Non-web';
    default:
      return 'Unknown';
  }
}

function virtualThreadLabel(runtime: RuntimeStackAnalysis): string {
  const virtualThreads = runtime.virtualThreads;
  if (!virtualThreads) {
    return 'Unknown';
  }
  if (virtualThreads.explicitVirtualThreadApiUsage) {
    return 'Direct API usage';
  }
  if (virtualThreads.enabledByProperty && virtualThreads.javaVersionCompatible) {
    return 'Enabled';
  }
  if (virtualThreads.enabledByProperty && !virtualThreads.javaVersionCompatible) {
    return 'Java not compatible';
  }
  return 'Not enabled';
}

function virtualThreadMeta(runtime: RuntimeStackAnalysis): string {
  const virtualThreads = runtime.virtualThreads;
  if (!virtualThreads) {
    return 'No virtual thread signal was detected.';
  }
  if (virtualThreads.explicitVirtualThreadApiUsage) {
    return 'Thread.ofVirtual / Executors.newVirtualThreadPerTaskExecutor detected';
  }
  if (virtualThreads.enabledByProperty) {
    return 'Configured by spring.threads.virtual.enabled=true';
  }
  if (virtualThreads.javaVersionCompatible && runtime.javaVersion) {
    return `Java ${runtime.javaVersion} supports virtual threads. No spring.threads.virtual.enabled=true was found.`;
  }
  return virtualThreads.summary ?? 'No virtual thread signal was detected.';
}

function runtimeVirtualThreadsCompactMeta(runtime: RuntimeStackAnalysis): string {
  const virtualThreads = runtime.virtualThreads;
  if (!virtualThreads) {
    return 'No virtual thread signal was detected.';
  }
  if (virtualThreads.explicitVirtualThreadApiUsage) {
    return 'Thread.ofVirtual / newVirtualThreadPerTaskExecutor detected';
  }
  if (virtualThreads.enabledByProperty) {
    return 'Configured by spring.threads.virtual.enabled=true';
  }
  if (virtualThreads.javaVersionCompatible && runtime.javaVersion) {
    return `Java ${runtime.javaVersion} compatible`;
  }
  return 'Compatibility unknown';
}

function dependencyModelLabel(gradleModel: GradleModelAnalysis | undefined): string {
  const status = gradleModel?.status ?? null;
  if (hasResolvedGradleDependencies(gradleModel) && status === 'SUCCESS_WITH_WORKAROUND') {
    return 'Gradle resolved';
  }
  if (hasResolvedGradleDependencies(gradleModel) && status === 'SUCCESS') {
    return 'Gradle resolved';
  }
  if ((status === 'SUCCESS' || status === 'SUCCESS_WITH_WORKAROUND') && (gradleModel?.declaredDependencies?.length ?? 0) > 0) {
    return 'Gradle declared only';
  }
  if (status === 'PARTIAL') {
    return 'Gradle partial';
  }
  if (status === 'FAILED' || status === 'TIMED_OUT') {
    return 'Static inference';
  }
  return 'Static inference';
}

function dependencyModelMeta(gradleModel: GradleModelAnalysis | undefined): string {
  const status = gradleModel?.status ?? null;
  const successfulResolutions = successfulGradleResolutionResults(gradleModel);
  const failedResolutions = failedGradleResolutionResults(gradleModel);
  const dependencyBearingResults = dependencyBearingGradleResolutionResults(gradleModel);
  if (hasResolvedGradleDependencies(gradleModel) && status === 'SUCCESS_WITH_WORKAROUND') {
    return `Collected from sanitized Gradle analysis copy (${successfulResolutions.length}/${dependencyBearingResults.length || 0} dependency-bearing configurations resolved)`;
  }
  if (hasResolvedGradleDependencies(gradleModel) && status === 'SUCCESS') {
    return `Collected from Gradle model analysis (${successfulResolutions.length}/${dependencyBearingResults.length || 0} dependency-bearing configurations resolved)`;
  }
  if ((status === 'SUCCESS' || status === 'SUCCESS_WITH_WORKAROUND') && failedResolutions.length > 0) {
    return 'Gradle executed, but selected configurations failed during dependency graph resolution';
  }
  if ((status === 'SUCCESS' || status === 'SUCCESS_WITH_WORKAROUND') && (gradleModel?.declaredDependencies?.length ?? 0) > 0) {
    return 'Gradle metadata was collected without a resolved dependency graph';
  }
  if (status === 'PARTIAL') {
    return 'Gradle metadata was collected, but dependency graph resolution was incomplete';
  }
  if (status === 'DISABLED' || status === 'NOT_REQUESTED') {
    return 'Gradle model analysis was not used';
  }
  return 'Dependency versions inferred statically';
}

function hasResolvedGradleDependencies(gradleModel: GradleModelAnalysis | undefined): boolean {
  return successfulResolvedGradleDependencies(gradleModel).length > 0 && successfulGradleResolutionResults(gradleModel).length > 0;
}

function successfulResolvedGradleDependencies(
  gradleModel: GradleModelAnalysis | undefined
): GradleResolvedDependencyModel[] {
  const successfulConfigurations = new Set(
    successfulGradleResolutionResults(gradleModel).map(
      (item) => `${item.projectPath ?? ''}|${item.configuration ?? ''}`
    )
  );
  return (gradleModel?.resolvedDependencies ?? []).filter((dependency) =>
    successfulConfigurations.has(`${dependency.projectPath ?? ''}|${dependency.configuration ?? ''}`)
  );
}

function successfulGradleResolutionResults(gradleModel: GradleModelAnalysis | undefined): GradleResolutionResult[] {
  return dependencyBearingGradleResolutionResults(gradleModel).filter(
    (item) => item.attempted && item.successful && (item.resolvedDependencyCount ?? 0) > 0
  );
}

function failedGradleResolutionResults(gradleModel: GradleModelAnalysis | undefined): GradleResolutionResult[] {
  return dependencyBearingGradleResolutionResults(gradleModel).filter((item) => item.attempted && !item.successful);
}

function dependencyBearingGradleResolutionResults(
  gradleModel: GradleModelAnalysis | undefined
): GradleResolutionResult[] {
  const dependencyBearingConfigurations = new Set(
    (gradleModel?.configurations ?? [])
      .filter((configuration) => (configuration.allDependencyCount ?? configuration.dependencyCount ?? 0) > 0)
      .map((configuration) => `${configuration.projectPath ?? ''}|${configuration.name ?? ''}`)
  );
  return (gradleModel?.resolutionResults ?? []).filter((item) =>
    dependencyBearingConfigurations.has(`${item.projectPath ?? ''}|${item.configuration ?? ''}`)
  );
}

function uniqueResolvedModuleCount(gradleModel: GradleModelAnalysis | undefined): number {
  return new Set(
    successfulResolvedGradleDependencies(gradleModel)
      .map((dependency) => `${dependency.group ?? ''}:${dependency.artifact ?? ''}:${dependency.version ?? ''}`)
      .filter((value) => value !== '::')
  ).size;
}

function resolvedStackEntries(
  gradleModel: GradleModelAnalysis | undefined
): Array<{ label: string; version: string }> {
  const entries = [
    ['Spring Boot', findResolvedDependencyVersionFromModel(gradleModel, 'org.springframework.boot', ['spring-boot'])],
    ['Spring Framework', findResolvedDependencyVersionFromModel(gradleModel, 'org.springframework', ['spring-core', 'spring-context'])],
    ['Tomcat', findResolvedDependencyVersionFromModel(gradleModel, 'org.apache.tomcat.embed', ['tomcat-embed-core'])],
    ['Jackson', findResolvedDependencyVersionFromModel(gradleModel, 'com.fasterxml.jackson.core', ['jackson-databind', 'jackson-core'])],
    ['Micrometer', findResolvedDependencyVersionFromModel(gradleModel, 'io.micrometer', ['micrometer-core'])],
    ['Logback', findResolvedDependencyVersionFromModel(gradleModel, 'ch.qos.logback', ['logback-classic'])],
    ['PostgreSQL driver', findResolvedDependencyVersionFromModel(gradleModel, 'org.postgresql', ['postgresql'])],
    ['Flyway', findResolvedDependencyVersionFromModel(gradleModel, 'org.flywaydb', ['flyway-core'])],
    ['Hibernate Validator', findResolvedDependencyVersionFromModel(gradleModel, 'org.hibernate.validator', ['hibernate-validator'])]
  ] as Array<[string, string | null]>;
  return entries
    .filter((entry): entry is [string, string] => Boolean(entry[1]))
    .map(([label, version]) => ({ label, version }));
}

function findResolvedDependencyVersionFromModel(
  gradleModel: GradleModelAnalysis | undefined,
  group: string,
  artifacts: string[]
): string | null {
  for (const dependency of successfulResolvedGradleDependencies(gradleModel)) {
    if (dependency.group === group && artifacts.includes(dependency.artifact ?? '') && dependency.version?.trim()) {
      return dependency.version;
    }
  }
  return null;
}

function selectedReasonSummary(dependency: GradleResolvedDependencyModel): string {
  const raw = dependency.selectedReason?.toLowerCase() ?? '';
  const labels: string[] = [];
  if (dependency.direct && raw.includes('requested')) {
    labels.push('Direct dependency');
  }
  if (raw.includes('by ancestor')) {
    labels.push('Transitive dependency');
  }
  if (raw.includes('constraint')) {
    labels.push('Version constraint');
  }
  if (raw.includes('selected by rule')) {
    labels.push('Managed by rule/BOM');
  }
  if (raw.includes('consistent resolution')) {
    labels.push('Consistent resolution');
  }
  if (!dependency.direct && raw) {
    labels.unshift('Transitive dependency');
  }
  if (!dependency.direct && labels.length === 0) {
    labels.push('Transitive dependency');
  }
  if (labels.length === 0) {
    return dependency.direct ? 'Direct dependency' : 'Transitive dependency';
  }
  return [...new Set(labels)].join(', ');
}

function springFrameworkVersionLabel(result: AnalyzeRepositoryResponse): string {
  const resolved = findResolvedDependencyVersion(result, 'org.springframework', [
    'spring-core',
    'spring-context',
    'spring-web',
    'spring-webmvc',
    'spring-webflux'
  ]);
  if (resolved) {
    return resolved;
  }
  return result.springBootDetected ? 'Managed by Spring Boot' : 'Unknown';
}

function springFrameworkVersionMeta(result: AnalyzeRepositoryResponse): string | null {
  const resolved = findResolvedDependencyVersion(result, 'org.springframework', [
    'spring-core',
    'spring-context',
    'spring-web',
    'spring-webmvc',
    'spring-webflux'
  ]);
  if (resolved) {
    return 'Resolved from Gradle dependency model';
  }
  if (result.springBootDetected) {
    return 'Exact version not confirmed from resolved dependencies';
  }
  return null;
}

function findResolvedDependencyVersion(
  result: AnalyzeRepositoryResponse,
  group: string,
  artifacts: string[]
): string | null {
  for (const dependency of successfulResolvedGradleDependencies(result.gradleModelAnalysis)) {
    if (dependency.group === group && artifacts.includes(dependency.artifact ?? '') && dependency.version?.trim()) {
      return dependency.version;
    }
  }
  return null;
}

function dedupeGradleRepositories(repositories: GradleRepositoryModel[]): GradleRepositoryModel[] {
  const unique = new Map<string, GradleRepositoryModel>();
  for (const repository of repositories) {
    const key = canonicalRepositoryKey(repository);
    const existing = unique.get(key);
    if (!existing) {
      unique.set(key, repository);
      continue;
    }
    const mergedProjectPath = uniqueValues([existing.projectPath, repository.projectPath]).join(', ');
    unique.set(key, {
      ...existing,
      projectPath: mergedProjectPath || existing.projectPath
    });
  }
  return [...unique.values()];
}

function canonicalRepositoryKey(repository: GradleRepositoryModel): string {
  const url = canonicalRepositoryUrl(repository.url);
  if (url) {
    return url;
  }
  return `${repository.name ?? ''}|${repository.type ?? ''}`;
}

function canonicalRepositoryUrl(url: string | null | undefined): string | null {
  if (!url) {
    return null;
  }
  const trimmed = url.trim();
  if (!trimmed) {
    return null;
  }
  if (trimmed.startsWith('file:')) {
    return trimmed.replace(/\\/g, '/').replace(/\/+$/, '/');
  }
  return trimmed.replace(/\/+$/, '/').toLowerCase();
}

function normalizedRepositoryName(repository: GradleRepositoryModel): string {
  const canonicalUrl = canonicalRepositoryUrl(repository.url);
  if (!canonicalUrl) {
    return repository.name ?? 'Repository';
  }
  if (canonicalUrl.includes('/gradle-plugin-cache/m2/')) {
    return 'Analyzer plugin cache';
  }
  if (canonicalUrl === 'https://plugins.gradle.org/m2/') {
    return 'Gradle Plugin Portal';
  }
  if (canonicalUrl === 'https://repo.maven.apache.org/maven2/') {
    return 'Maven Central';
  }
  return repository.name ?? canonicalUrl;
}

function normalizedRepositoryUrlLabel(repository: GradleRepositoryModel): string {
  const canonicalUrl = canonicalRepositoryUrl(repository.url);
  if (!canonicalUrl) {
    return '—';
  }
  if (canonicalUrl.includes('/gradle-plugin-cache/m2/')) {
    return 'Analyzer plugin cache';
  }
  return repository.url ?? canonicalUrl;
}

function filterVisibleAppliedPlugins(plugins: GradlePluginModel[]): GradlePluginModel[] {
  return plugins.filter((plugin) => {
    const identity = `${plugin.pluginId ?? ''} ${plugin.implementationClass ?? ''}`;
    if (!identity.trim()) {
      return false;
    }
    return /SpringBootPlugin|DependencyManagementPlugin|FlywayPlugin|JavaPlugin|Kotlin|NodePlugin|CheckstylePlugin|JacocoPlugin/.test(identity)
      || !/org\.gradle\.api\.plugins\..*_Decorated/.test(identity);
  });
}

function schedulingLabel(runtime: RuntimeStackAnalysis): string {
  return runtime.virtualThreads?.scheduledWorkDetected ? 'Detected' : 'Not detected';
}

function schedulingMeta(runtime: RuntimeStackAnalysis): string | null {
  const evidence = runtime.virtualThreads?.evidence ?? [];
  const schedulingEvidence = evidence.filter((item) => item.includes('@Scheduled') || item.includes('@EnableScheduling'));
  if (schedulingEvidence.length > 0) {
    return schedulingEvidence[0];
  }
  return runtime.virtualThreads?.scheduledWorkDetected ? 'Scheduled work signals were detected' : 'No scheduling signal was detected';
}

function actuatorLabel(result: AnalyzeRepositoryResponse): string {
  const hasExposure = (result.httpSurfaceAnalysis?.actuatorExposures ?? []).length > 0;
  const hasDependency = (result.dependencies ?? []).some((dependency) => dependency.includes('spring-boot-starter-actuator'));
  return hasExposure || hasDependency ? 'Present' : 'Not detected';
}

function actuatorMeta(result: AnalyzeRepositoryResponse): string | null {
  const exposures = result.httpSurfaceAnalysis?.actuatorExposures ?? [];
  if (exposures.length > 0) {
    return `${exposures.length} actuator exposure signal${exposures.length === 1 ? '' : 's'} detected`;
  }
  const hasDependency = (result.dependencies ?? []).some((dependency) => dependency.includes('spring-boot-starter-actuator'));
  return hasDependency ? 'Actuator dependency found' : 'No actuator signal was detected';
}

function summarizeFindings(findings: Finding[]): string | null {
  if (findings.length === 0) {
    return null;
  }
  const errors = findings.filter((finding) => normalizeSeverity(finding.severity) === 'ERROR').length;
  const warnings = findings.filter((finding) => normalizeSeverity(finding.severity) === 'WARNING').length;
  const info = findings.filter((finding) => normalizeSeverity(finding.severity) === 'INFO').length;
  const parts = [
    errors > 0 ? `${errors} error${errors === 1 ? '' : 's'}` : null,
    warnings > 0 ? `${warnings} warning${warnings === 1 ? '' : 's'}` : null,
    info > 0 ? `${info} info` : null
  ].filter((value): value is string => Boolean(value));
  return parts.join(', ');
}

function deriveFindingPresentation(finding: Finding): PresentedFinding {
  const message = finding.message ?? 'No message';
  const shortMessage = finding.shortMessage?.trim() || message;
  const normalized = message.toLowerCase();
  let ruleType = finding.title || finding.ruleId || finding.rule || finding.category || 'Static finding';
  let title = finding.title?.trim() || ruleType;
  let target = finding.target || finding.location || '—';

  const riskyMatch = message.match(/^(?<property>[\w.-]+)=.+ is risky in production\./i);
  if (riskyMatch?.groups?.property) {
    ruleType = 'Risky production config';
    title = 'Risky production config';
    target = riskyMatch.groups.property;
  } else if (normalized.startsWith('sensitive configuration property appears to use a literal value')) {
    ruleType = 'Sensitive literal value';
    title = 'Sensitive literal value';
    const propertyMatch = message.match(/:\s*([\w.-]+)\s*$/);
    target = propertyMatch?.[1] ?? target;
  } else if (normalized.startsWith('profile-specific configuration files')) {
    ruleType = 'Profile-specific config';
    title = 'Profile-specific config';
    target = 'profiles';
  } else if (normalized.includes('@configurationproperties prefix was found')) {
    ruleType = 'Orphan configuration prefix';
    title = 'Orphan configuration prefix';
    const prefixMatch = message.match(/prefix\s+"([^"]+)"/i);
    target = prefixMatch?.[1] ?? target;
  } else if (normalized.includes('management.endpoint.health.show-details=always')) {
    ruleType = 'Health details exposure';
    title = 'Health details exposure';
    target = 'management.endpoint.health.show-details';
  } else if (normalized.includes('management.endpoints.web.exposure.include=*')
    || normalized.includes("actuator web exposure includes '*'")) {
    ruleType = 'Actuator exposure';
    title = 'Actuator exposure';
    target = 'management.endpoints.web.exposure.include';
  } else if (normalized.startsWith('gradle executed successfully, but no dependency-bearing configuration resolved a dependency graph')) {
    ruleType = 'Gradle model incomplete';
    title = 'Gradle model incomplete';
    target = 'dependency graph';
  } else if (normalized.startsWith('dependency resolution failed for ')) {
    ruleType = 'Gradle dependency resolution';
    title = 'Gradle dependency resolution';
    const configurationMatch = message.match(/^Dependency resolution failed for\s+([^:.]+)(?:[:.].*)?$/i);
    target = configurationMatch?.[1]?.trim() ?? target;
  } else if (normalized.includes('plain http://')) {
    ruleType = 'Plain HTTP endpoint';
    title = 'Plain HTTP endpoint';
    target = target === '—' ? 'external endpoint' : target;
  } else if (normalized.startsWith('reactive apis were detected in code')) {
    ruleType = 'Reactive API usage in Servlet application';
    title = 'Reactive API usage in Servlet application';
    target = 'reactive APIs';
  } else if (normalized.startsWith('build-aware analysis disabled')
    || normalized.startsWith('gradle model analysis was requested, but analyzer.gradle.enabled=false')) {
    ruleType = 'Build-aware analysis disabled';
    title = 'Build-aware analysis disabled';
    target = 'Gradle model';
  } else if (normalized.includes('outside the main application package')) {
    ruleType = 'Component outside main package';
    title = 'Component outside main package';
  } else if (title === 'Static finding' || title === 'Analyzer finding') {
    title = fallbackFindingTitle(finding);
    ruleType = title;
  }

  const location = buildFindingLocation(finding);
  const locationDetails = deriveLocationDetails(finding);
  return {
    severity: normalizeSeverity(finding.severity),
    title,
    category: findingCategoryLabel(normalizeFindingCategory(finding.category)),
    confidence: confidenceLabel(normalizeConfidence(finding.confidence)),
    runtimeDetection: runtimeDetectionLabel(normalizeRuntimeDetection(finding.runtimeDetection)),
    heuristic: isHeuristicFinding(finding),
    ruleType,
    target,
    location,
    locationShort: middleEllipsis(location, 88),
    locationMeta: locationDetails.meta,
    locationKnown: locationDetails.known,
    message,
    summary: shortMessage,
    finding
  };
}

function primaryFindingLocation(finding: Finding): SourceLocation | null {
  if (finding.primaryLocation?.filePath) {
    return finding.primaryLocation;
  }
  if (finding.sourceFile) {
    return {
      filePath: finding.sourceFile,
      startLine: finding.line ?? undefined,
      endLine: finding.line ?? undefined,
      symbol: finding.target ?? undefined,
      language: inferCodeLanguage(finding.sourceFile),
      githubUrl: null
    };
  }
  return null;
}

function fallbackFindingTitle(finding: Finding): string {
  const category = findingCategoryLabel(normalizeFindingCategory(finding.category));
  const message = (finding.message ?? '').trim();
  if (!message) {
    return `${category} finding`;
  }
  const normalized = message.charAt(0).toUpperCase() + message.slice(1);
  return normalized.length > 72 ? `${category} finding` : normalized;
}

function deriveLocationDetails(finding: Finding): { meta: string; known: boolean } {
  const primaryLocation = primaryFindingLocation(finding);
  if (primaryLocation?.filePath) {
    if (!primaryLocation.startLine) {
      return { meta: 'Exact line not resolved statically', known: false };
    }
    if (!primaryLocation.endLine || primaryLocation.endLine <= primaryLocation.startLine) {
      return { meta: `Line ${primaryLocation.startLine}`, known: true };
    }
    return { meta: `Lines ${primaryLocation.startLine}-${primaryLocation.endLine}`, known: true };
  }
  return { meta: '', known: false };
}

function isHeuristicFinding(finding: Finding): boolean {
  const ruleId = (finding.ruleId ?? '').trim();
  return [
    'CONFIG_CODE_REFERENCE_MISSING',
    'SPRING_REACTIVE_API_IN_SERVLET_APP',
    'SPRING_TRANSACTION_MISSING_BOUNDARY',
    'SPRING_SIDE_EFFECT_ORCHESTRATION_NO_BOUNDARY',
    'SPRING_REPEATED_FALLBACK_PARSING_PATTERN',
    'SPRING_BROAD_EXCEPTION_HANDLER'
  ].includes(ruleId);
}

function inferCodeLanguage(filePath: string): string {
  const normalized = filePath.toLowerCase();
  if (normalized.endsWith('.java')) {
    return 'java';
  }
  if (normalized.endsWith('.properties')) {
    return 'properties';
  }
  if (normalized.endsWith('.yaml') || normalized.endsWith('.yml')) {
    return 'yaml';
  }
  if (normalized.endsWith('.xml')) {
    return 'xml';
  }
  if (normalized.endsWith('.gradle') || normalized.endsWith('.gradle.kts')) {
    return 'gradle';
  }
  return 'text';
}

function hashString(value: string): string {
  let hash = 0;
  for (let index = 0; index < value.length; index++) {
    hash = (hash * 31 + value.charCodeAt(index)) >>> 0;
  }
  return hash.toString(36);
}

function groupFindings(findings: Finding[]): GroupedPresentedFinding[] {
  const grouped = new Map<string, ReturnType<typeof deriveFindingPresentation>[]>();
  for (const finding of findings) {
    const derived = deriveFindingPresentation(finding);
    const key = [
      finding.ruleId ?? derived.ruleType,
      normalizeFindingCategory(finding.category),
      normalizeRuntimeDetection(finding.runtimeDetection),
      findingGroupingTargetKind(finding),
      normalizeGroupingText(finding.title ?? derived.title ?? derived.ruleType),
      normalizeGroupingText(derived.ruleType)
    ].join('|');
    const bucket = grouped.get(key) ?? [];
    bucket.push(derived);
    grouped.set(key, bucket);
  }
  return [...grouped.values()].map((bucket) => {
    const representative = chooseFindingGroupRepresentative(bucket);
    const uniqueTargets = uniqueValues(bucket.map((item) => item.target).filter((value) => value && value !== '—'));
    const target = uniqueTargets.length <= 1
      ? (uniqueTargets[0] ?? representative.target)
      : groupedTargetSummary(representative, uniqueTargets.length);
    return {
      severity: representative.severity,
      title: representative.title,
      category: representative.category,
      confidence: representative.confidence,
      runtimeDetection: representative.runtimeDetection,
      heuristic: representative.heuristic,
      ruleType: representative.ruleType,
      target,
      message: representative.message,
      summary: representative.summary,
      location: representative.location,
      locationShort: middleEllipsis(representative.location, 88),
      locationMeta: representative.locationMeta,
      locationKnown: representative.locationKnown,
      occurrences: bucket.length,
      items: bucket,
      finding: representative.finding
    };
  });
}

function chooseFindingGroupRepresentative(bucket: PresentedFinding[]): PresentedFinding {
  return [...bucket].sort((left, right) => {
    const severity = findingPriority(left.severity, ['ERROR', 'WARNING', 'INFO'])
      - findingPriority(right.severity, ['ERROR', 'WARNING', 'INFO']);
    if (severity !== 0) {
      return severity;
    }
    const confidence = findingPriority(left.confidence, ['High', 'Medium', 'Low'])
      - findingPriority(right.confidence, ['High', 'Medium', 'Low']);
    if (confidence !== 0) {
      return confidence;
    }
    return compareValues(left.location, right.location, 'asc');
  })[0];
}

function findingPriority(value: string, ranking: string[]): number {
  const normalized = value.toLowerCase();
  const index = ranking.findIndex((candidate) => candidate.toLowerCase() === normalized);
  return index < 0 ? ranking.length : index;
}

function findingGroupingTargetKind(finding: Finding): string {
  const target = defaultText(finding.target).toLowerCase();
  const why = defaultText(finding.whyBadPractice).toLowerCase();
  if (target.includes('#parse') || target.includes('#tryparse') || target.includes('#extract') || target.includes('#decode') || target.includes('#convert') || target.includes('#normalize') || why.includes('best-effort parsing')) {
    return 'parser-helper';
  }
  if (target.includes('controller#')) {
    return 'controller';
  }
  if (target.includes('service#')) {
    return 'service';
  }
  if (target.includes('repository#')) {
    return 'repository';
  }
  if (target.includes('#')) {
    return 'method';
  }
  return 'general';
}

function groupedTargetSummary(representative: PresentedFinding, targetCount: number): string {
  if (representative.category === 'Exception handling') {
    return targetCount > 1 ? 'Multiple methods' : representative.target;
  }
  return targetCount > 1 ? 'Multiple targets' : representative.target;
}

function sortFindingGroups(findings: GroupedPresentedFinding[], sort: TableSortState): GroupedPresentedFinding[] {
  return [...findings].sort((left, right) => compareFindings(left, right, sort));
}

function componentDisplayName(component: Partial<DetectedClass>): string {
  if (component.fullyQualifiedClassName) {
    return component.fullyQualifiedClassName;
  }
  if (component.packageName && (component.simpleClassName || component.simpleName)) {
    return `${component.packageName}.${component.simpleClassName ?? component.simpleName ?? ''}`;
  }
  return component.simpleClassName ?? component.simpleName ?? 'Unknown component';
}

function componentPrimaryName(component: Partial<DetectedClass>): string {
  if (component.simpleClassName?.trim()) {
    return component.simpleClassName.trim();
  }
  if (component.simpleName?.trim()) {
    return component.simpleName.trim();
  }
  const displayName = componentDisplayName(component);
  const lastDot = displayName.lastIndexOf('.');
  return lastDot >= 0 ? displayName.slice(lastDot + 1) : displayName;
}

function componentQualifiedName(component: Partial<DetectedClass>): string {
  if (component.fullyQualifiedClassName?.trim()) {
    return component.fullyQualifiedClassName.trim();
  }
  if (component.packageName && (component.simpleClassName || component.simpleName)) {
    return `${component.packageName}.${component.simpleClassName ?? component.simpleName ?? ''}`;
  }
  return componentDisplayName(component);
}

function componentClassCell(component: Partial<DetectedClass>): HTMLTableCellElement {
  const cell = document.createElement('td');
  cell.className = 'component-class-cell';
  const primary = componentPrimaryName(component);
  const qualified = componentQualifiedName(component);
  const wrapper = element('div', { className: 'component-class-block' });
  wrapper.appendChild(
    element('div', {
      className: 'component-class-primary',
      text: primary,
      attributes: { title: qualified }
    })
  );
  if (qualified && qualified !== primary) {
    wrapper.appendChild(
      element('div', {
        className: 'component-class-secondary',
        text: qualified,
        attributes: { title: qualified }
      })
    );
  }
  cell.appendChild(wrapper);
  return cell;
}

function componentAnnotationsCell(component: Partial<DetectedClass>): HTMLTableCellElement {
  const annotations = (component.annotationNames ?? component.annotations ?? []).filter((value): value is string => Boolean(value?.trim()));
  const text = annotations.join(', ') || '—';
  const cell = technicalClampCell(text, undefined, 'component-annotations-cell');
  if (isRedundantComponentAnnotation(component, annotations)) {
    cell.classList.add('is-redundant');
  }
  return cell;
}

function isRedundantComponentAnnotation(component: Partial<DetectedClass>, annotations: string[]): boolean {
  if (annotations.length !== 1) {
    return false;
  }
  const annotation = annotations[0].trim().toLowerCase();
  const componentType = normalizeComponentType(component);
  return (
    (componentType === 'COMPONENT' && annotation === 'component')
    || (componentType === 'SERVICE' && annotation === 'service')
    || (componentType === 'REPOSITORY' && annotation === 'repository')
    || (componentType === 'CONFIGURATION' && annotation === 'configuration')
    || (componentType === 'CONTROLLER' && annotation === 'controller')
    || (componentType === 'REST_CONTROLLER' && annotation === 'restcontroller')
    || (componentType === 'CONFIGURATION_PROPERTIES' && annotation === 'configurationproperties')
  );
}

function normalizeFindingCategory(value: string | undefined): string {
  if (!value || !value.trim()) {
    return 'MAINTAINABILITY';
  }
  return value.trim().toUpperCase();
}

function normalizeRuntimeDetection(value: string | undefined): string {
  if (!value || !value.trim()) {
    return 'NOT_NORMALLY_DETECTED';
  }
  return value.trim().toUpperCase();
}

function normalizeConfidence(value: string | undefined): string {
  if (!value || !value.trim()) {
    return 'MEDIUM';
  }
  return value.trim().toUpperCase();
}

function findingCategoryLabel(value: string): string {
  switch (value) {
    case 'PROFILE_DRIFT':
      return 'Profile drift';
    case 'EXCEPTION_HANDLING':
      return 'Exception handling';
    case 'CONDITIONAL_BEAN':
      return 'Conditional bean';
    case 'API_SURFACE':
      return 'API surface';
    default:
      return value
        .toLowerCase()
        .split('_')
        .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
        .join(' ');
  }
}

function runtimeDetectionLabel(value: string): string {
  switch (value) {
    case 'NOT_NORMALLY_DETECTED':
      return 'Detected statically';
    case 'ACTIVE_PROFILE_RUNTIME_MAY_DETECT':
      return 'Depends on active profile';
    case 'RUNTIME_REQUIRED':
      return 'Requires runtime verification';
    default:
      return value;
  }
}

function confidenceLabel(value: string): string {
  switch (value) {
    case 'HIGH':
      return 'High';
    case 'MEDIUM':
      return 'Medium';
    case 'LOW':
      return 'Low';
    default:
      return value;
  }
}

function redactSensitiveValues(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(redactSensitiveValues);
  }
  if (!value || typeof value !== 'object') {
    return value;
  }
  const copy: Record<string, unknown> = {};
  for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
    if (key.toLowerCase().includes('token') || key.toLowerCase().includes('credential')) {
      copy[key] = '[redacted]';
    } else {
      copy[key] = redactSensitiveValues(entry);
    }
  }
  return copy;
}

function propertyMeaning(property: ApplicationProperty): string {
  const configured = property.value !== null && property.value !== undefined && property.value !== '';
  const hasReferences = (property.references?.length ?? 0) > 0;
  if (property.kind === 'CODE_REFERENCED' || property.kind === 'CONDITIONAL_PROPERTY' || hasReferences) {
    return configured
      ? 'Referenced in code and configured in the scanned files.'
      : 'Referenced in code but no matching configured property was found in the scanned files.';
  }
  if (property.documentation?.description) {
    return property.documentation.description;
  }
  if (property.kind === 'THIRD_PARTY') {
    return 'Provided by a known third-party library.';
  }
  if (property.kind === 'UNKNOWN') {
    return 'No Spring Boot or custom metadata found for this property.';
  }
  if (property.kind === 'CUSTOM_CONFIGURATION_PROPERTIES') {
    return 'Custom application configuration property.';
  }
  return 'No description available.';
}

function compactPropertyMeaning(property: ApplicationProperty): string {
  const meaning = propertyMeaning(property);
  if (property.kind === 'CUSTOM_CONFIGURATION_PROPERTIES') {
    const normalized = meaning.toLowerCase();
    if (
      normalized.startsWith('custom property defined by ')
      || normalized.startsWith('custom application configuration property')
    ) {
      const namespace = dominantPropertyPrefix(property.name);
      return namespace ? `Custom ${namespace} property.` : 'Custom application property.';
    }
  }
  return meaning;
}

function collectChangedPropertyNames(properties: ApplicationProperty[]): Set<string> {
  return new Set(
    buildProfileComparisonGroups(properties)
      .filter((group) => group.changed)
      .map((group) => group.name)
  );
}

function configurationReviewSignals(
  properties: ApplicationProperty[],
  codeReferences: PropertyReference[],
  changedPropertyNames: Set<string>
): { sensitive: number; unknown: number; changed: number; codeReferenced: number; total: number } {
  const sensitive = properties.filter((property) => Boolean(property.valueRedacted)).length;
  const unknown = properties.filter((property) => (property.kind ?? 'UNKNOWN') === 'UNKNOWN').length;
  const codeReferenced = properties.filter((property) => {
    const kind = property.kind ?? 'UNKNOWN';
    return kind === 'CODE_REFERENCED' || kind === 'CONDITIONAL_PROPERTY';
  }).length || codeReferences.length;
  const changed = changedPropertyNames.size;
  return {
    sensitive,
    unknown,
    changed,
    codeReferenced,
    total: sensitive + unknown + changed + codeReferenced
  };
}

function dominantCustomPropertyNamespace(
  properties: ApplicationProperty[]
): { namespace: string; count: number } | null {
  const counts = new Map<string, number>();
  properties
    .filter((property) => (property.kind ?? 'UNKNOWN') === 'CUSTOM_CONFIGURATION_PROPERTIES')
    .forEach((property) => {
      const namespace = dominantPropertyPrefix(property.name);
      if (!namespace) {
        return;
      }
      counts.set(namespace, (counts.get(namespace) ?? 0) + 1);
    });
  const sorted = [...counts.entries()].sort((left, right) => right[1] - left[1]);
  if (sorted.length === 0) {
    return null;
  }
  const [namespace, count] = sorted[0];
  return count >= 3 ? { namespace, count } : null;
}

function dominantPropertyPrefix(propertyName: string | undefined): string | null {
  if (!propertyName) {
    return null;
  }
  const parts = propertyName.split('.').filter(Boolean);
  if (parts.length >= 2) {
    return `${parts[0]}.${parts[1]}`;
  }
  if (parts.length === 1) {
    return parts[0];
  }
  return null;
}

function normalizeGroupingText(value: string): string {
  return value.trim().toLowerCase().replace(/\s+/g, ' ');
}

function isStaticOnlyDependencyMode(gradleModel: GradleModelAnalysis | undefined): boolean {
  const status = gradleModel?.status ?? null;
  return !gradleModel || status === 'NOT_REQUESTED' || status === 'DISABLED';
}

function gradleModelSummaryLabel(gradleModel: GradleModelAnalysis | undefined): string {
  const status = gradleModel?.status ?? null;
  switch (status) {
    case 'SUCCESS':
    case 'SUCCESS_WITH_WORKAROUND':
      return 'Success';
    case 'PARTIAL':
      return 'Partial';
    case 'FAILED':
      return 'Failed';
    case 'TIMED_OUT':
      return 'Timed out';
    case 'DISABLED':
      return 'Disabled';
    case 'NOT_REQUESTED':
    default:
      return 'Not requested';
  }
}

function displayPropertyValue(property: ApplicationProperty): string {
  if (property.valueRedacted) {
    return '[redacted]';
  }
  if (property.value === null || property.value === undefined || property.value === '') {
    return 'Not configured';
  }
  return property.value;
}

function sourceLabel(sourceFile?: string | null, line?: number | null, endLine?: number | null): string {
  if (sourceFile && line) {
    if (endLine && endLine > line) {
      return `${sourceFile}:${line}-${endLine}`;
    }
    return `${sourceFile}:${line}`;
  }
  return sourceFile ?? 'Source reference';
}

function buildFindingLocation(finding: Finding): string {
  const primaryLocation = primaryFindingLocation(finding);
  if (primaryLocation?.filePath) {
    return sourceLabel(primaryLocation.filePath, primaryLocation.startLine ?? null, primaryLocation.endLine ?? null);
  }
  const source = sourceLabel(finding.sourceFile, finding.line, finding.line);
  if (source !== '—') {
    return source;
  }
  const locationParts = [finding.location, finding.target].filter((value): value is string => Boolean(value));
  return locationParts.join(' | ') || '—';
}

function middleEllipsis(value: string, maxLength: number): string {
  if (!value || value.length <= maxLength) {
    return value;
  }
  const visible = Math.max(maxLength - 1, 6);
  const head = Math.ceil(visible / 2);
  const tail = Math.floor(visible / 2);
  return `${value.slice(0, head)}…${value.slice(value.length - tail)}`;
}

function configurationRowKey(property: ApplicationProperty): string {
  return [property.name ?? '', property.profile ?? 'default', property.sourceFile ?? '', property.line ?? '']
    .join('|');
}

function uniqueValues(values: Array<string | null | undefined>): string[] {
  return [...new Set(values.filter((value): value is string => Boolean(value)).sort((left, right) => left.localeCompare(right)))];
}

function defaultText(...values: Array<string | null | undefined>): string {
  for (const value of values) {
    if (value && value.trim()) {
      return value;
    }
  }
  return '';
}

function normalizedProfile(value: string | null | undefined): string {
  return value && value.trim() ? value.trim().toLowerCase() : 'default';
}

function formatReferences(references: PropertyReference[]): string {
  return references
    .map((reference) =>
      [
        reference.referenceType,
        reference.className,
        reference.sourceFile,
        reference.defaultValue ? `default=${reference.defaultValue}` : null,
        reference.expectedValue ? `expected=${reference.expectedValue}` : null,
        reference.matchIfMissing !== undefined && reference.matchIfMissing !== null
          ? `matchIfMissing=${reference.matchIfMissing}`
          : null
      ]
        .filter(Boolean)
        .join(' | ')
    )
    .join('; ');
}

function buildProfileComparisonGroups(properties: ApplicationProperty[]): Array<{
  name: string;
  valuesByProfile: Map<string, string>;
  kind: string;
  meaning: string;
  changed: boolean;
}> {
  const grouped = new Map<string, ApplicationProperty[]>();
  for (const property of properties) {
    const bucket = grouped.get(property.name ?? 'Unknown') ?? [];
    bucket.push(property);
    grouped.set(property.name ?? 'Unknown', bucket);
  }

  return [...grouped.entries()]
    .map(([name, entries]) => {
      const valuesByProfile = new Map<string, string>();
      for (const entry of entries) {
        valuesByProfile.set(entry.profile ?? 'default', displayPropertyValue(entry));
      }
      return {
        name,
        valuesByProfile,
        kind: configurationKindLabel(entries[0]?.kind ?? 'UNKNOWN'),
        meaning: propertyMeaning(entries[0]),
        changed: new Set(valuesByProfile.values()).size > 1
      };
    })
    .sort((left, right) => left.name.localeCompare(right.name));
}

function sortFindings(findings: Finding[], sort: TableSortState): Finding[] {
  return [...findings].sort((left, right) => compareFindings(deriveFindingPresentation(left), deriveFindingPresentation(right), sort));
}

function compareFindings(left: PresentedFinding, right: PresentedFinding, sort: TableSortState): number {
  if (sort.key === 'severity') {
    const severityResult = rankedCompare(left.severity, right.severity, ['ERROR', 'WARNING', 'INFO'], sort.direction);
    if (severityResult !== 0) {
      return severityResult;
    }
    const confidenceResult = rankedCompare(left.confidence, right.confidence, ['High', 'Medium', 'Low'], sort.direction);
    if (confidenceResult !== 0) {
      return confidenceResult;
    }
    const runtimeResult = rankedCompare(
      left.runtimeDetection,
      right.runtimeDetection,
      ['Detected statically', 'Depends on active profile', 'Requires runtime verification'],
      sort.direction
    );
    if (runtimeResult !== 0) {
      return runtimeResult;
    }
    return compareValues(left.location, right.location, 'asc');
  }
  return compareValues(findingGroupSortValue(left, sort.key), findingGroupSortValue(right, sort.key), sort.direction);
}

function rankedCompare(left: string, right: string, ranking: string[], direction: SortDirection): number {
  const leftRank = rankValue(left, ranking);
  const rightRank = rankValue(right, ranking);
  if (leftRank === rightRank) {
    return 0;
  }
  return direction === 'asc' ? rightRank - leftRank : leftRank - rightRank;
}

function rankValue(value: string, ranking: string[]): number {
  const normalized = value.toLowerCase();
  const index = ranking.findIndex((candidate) => candidate.toLowerCase() === normalized);
  return index < 0 ? ranking.length : index;
}

function sortInboundEndpoints(endpoints: InboundEndpoint[], sort: TableSortState): InboundEndpoint[] {
  return [...endpoints].sort((left, right) => compareValues(inboundSortValue(left, sort.key), inboundSortValue(right, sort.key), sort.direction));
}

function sortOutboundEndpoints(endpoints: OutboundEndpoint[], sort: TableSortState): OutboundEndpoint[] {
  return [...endpoints].sort((left, right) => compareValues(outboundSortValue(left, sort.key), outboundSortValue(right, sort.key), sort.direction));
}

function sortConfiguredUrls(urls: ConfiguredUrl[], sort: TableSortState): ConfiguredUrl[] {
  return [...urls].sort((left, right) => compareValues(configuredUrlSortValue(left, sort.key), configuredUrlSortValue(right, sort.key), sort.direction));
}

function sortConfigurationProperties(properties: ApplicationProperty[], sort: TableSortState): ApplicationProperty[] {
  return [...properties].sort((left, right) => compareValues(configurationSortValue(left, sort.key), configurationSortValue(right, sort.key), sort.direction));
}

function sortComponents(components: DetectedClass[], sort: TableSortState): DetectedClass[] {
  return [...components].sort((left, right) => compareValues(componentSortValue(left, sort.key), componentSortValue(right, sort.key), sort.direction));
}

function sortDependencies(dependencies: string[], sort: TableSortState): string[] {
  return [...dependencies].sort((left, right) => {
    const leftParts = parseDependency(left);
    const rightParts = parseDependency(right);
    return compareValues(
      dependencySortValue(left, leftParts, sort.key),
      dependencySortValue(right, rightParts, sort.key),
      sort.direction
    );
  });
}

function sortResolvedDependencies(
  dependencies: GradleResolvedDependencyModel[],
  sort: TableSortState
): GradleResolvedDependencyModel[] {
  return [...dependencies].sort((left, right) => {
    const leftValue = sort.key === 'group'
      ? left.group ?? ''
      : sort.key === 'artifact'
        ? left.artifact ?? ''
        : sort.key === 'version'
          ? left.version ?? ''
          : left.configuration ?? '';
    const rightValue = sort.key === 'group'
      ? right.group ?? ''
      : sort.key === 'artifact'
        ? right.artifact ?? ''
        : sort.key === 'version'
          ? right.version ?? ''
          : right.configuration ?? '';
    return compareValues(leftValue, rightValue, sort.direction);
  });
}

function compareValues(left: string, right: string, direction: SortDirection): number {
  const result = left.localeCompare(right, undefined, { sensitivity: 'base', numeric: true });
  return direction === 'asc' ? result : -result;
}

function findingSortValue(finding: Finding, key: string): string {
  const derived = deriveFindingPresentation(finding);
  return findingGroupSortValue(derived, key);
}

function findingGroupSortValue(
  finding: PresentedFinding,
  key: string
): string {
  return key === 'severity'
    ? finding.severity
    : key === 'rule'
      ? finding.ruleType
      : key === 'target'
        ? finding.target
        : key === 'location'
          ? finding.location
          : finding.message;
}

function inboundSortValue(endpoint: InboundEndpoint, key: string): string {
  return key === 'method'
    ? endpoint.httpMethod ?? ''
    : key === 'controller'
      ? endpoint.controllerClass ?? ''
      : endpoint.path ?? '';
}

function outboundSortValue(endpoint: OutboundEndpoint, key: string): string {
  return key === 'client'
    ? endpoint.clientType ?? ''
    : key === 'method'
      ? endpoint.method ?? ''
      : key === 'host'
        ? endpoint.host ?? ''
        : endpoint.fullUrlPreview ?? endpoint.urlOrTemplate ?? '';
}

function configuredUrlSortValue(item: ConfiguredUrl, key: string): string {
  return key === 'kind'
    ? urlKindLabel(item.kind)
    : key === 'host'
      ? item.host ?? ''
      : key === 'profile'
        ? item.profile ?? 'default'
        : key === 'source'
          ? sourceLabel(item.sourceFile, item.line)
          : item.propertyName ?? '';
}

function configurationSortValue(property: ApplicationProperty, key: string): string {
  return key === 'profile'
    ? property.profile ?? 'default'
    : key === 'kind'
      ? configurationKindLabel(property.kind ?? 'UNKNOWN')
      : key === 'source'
        ? sourceLabel(property.sourceFile, property.line)
        : key === 'type'
          ? property.documentation?.type ?? ''
          : property.name ?? '';
}

function componentSortValue(component: DetectedClass, key: string): string {
  return key === 'type'
    ? componentTypeLabel(normalizeComponentType(component))
    : key === 'package'
      ? component.packageName ?? ''
      : key === 'source'
        ? component.filePath ?? ''
        : componentDisplayName(component);
}

function dependencySortValue(dependency: string, parts: ReturnType<typeof parseDependency>, key: string): string {
  return key === 'group'
    ? parts.group
    : key === 'artifact'
      ? parts.artifact
      : key === 'version'
        ? parts.version
        : dependency;
}

function parseDependency(value: string): {
  group: string;
  artifact: string;
  version: string;
} {
  const parts = value.split(':');
  if (parts.length >= 3) {
    return {
      group: parts[0],
      artifact: parts[1],
      version: parts.slice(2).join(':')
    };
  }
  if (parts.length === 2) {
    return {
      group: parts[0],
      artifact: parts[1],
      version: ''
    };
  }
  return {
    group: '',
    artifact: value,
    version: ''
  };
}

function urlKindLabel(value: string | null | undefined): string {
  switch (value) {
    case 'HTTP_URL':
      return 'HTTP URL';
    case 'JDBC_URL':
      return 'JDBC URL';
    case 'MAIL_HOST':
      return 'Mail host';
    case 'MESSAGE_BROKER_URL':
      return 'Message broker';
    case 'REDIS_HOST':
      return 'Redis host';
    case 'OAUTH_OIDC_URL':
      return 'OAuth/OIDC URL';
    case 'OBSERVABILITY_ENDPOINT':
      return 'Observability endpoint';
    case 'PROPERTY_REFERENCE':
      return 'Property reference';
    case 'OTHER':
      return 'Other';
    default:
      return 'Unknown';
  }
}




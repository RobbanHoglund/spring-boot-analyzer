import { element } from '../dom';
import type {
  ActuatorEndpointExposure,
  AnalyzeRepositoryResponse,
  ApplicationProperty,
  ConfiguredUrl,
  DetectedClass,
  Finding,
  HttpSurfaceAnalysis,
  InboundEndpoint,
  OutboundEndpoint,
  PropertyReference,
  RuntimeStackAnalysis
} from '../types';

const FINDING_SEVERITIES = ['ALL', 'ERROR', 'WARNING', 'INFO'] as const;
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
type PresentedFinding = {
  severity: string;
  ruleType: string;
  target: string;
  location: string;
  message: string;
};

export interface TableSortState {
  key: string;
  direction: SortDirection;
}

export interface ResultsViewState {
  findingsSeverity: string;
  findingsText: string;
  findingsExpanded: boolean;
  findingsGrouped: boolean;
  configurationSearch: string;
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
  rawJsonExpanded: boolean;
  httpInboundExpanded: boolean;
  httpOutboundExpanded: boolean;
  httpConfiguredExpanded: boolean;
  httpActuatorExpanded: boolean;
}

export interface ResultsViewActions {
  onFindingsSeverityChange: (value: string) => void;
  onFindingsTextChange: (value: string) => void;
  onToggleFindingsExpanded: () => void;
  onFindingsGroupedChange: (value: boolean) => void;
  onConfigurationSearchChange: (value: string) => void;
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
  onRawJsonExpandedChange: (value: boolean) => void;
  onToggleHttpInboundExpanded: () => void;
  onToggleHttpOutboundExpanded: () => void;
  onToggleHttpConfiguredExpanded: () => void;
  onToggleHttpActuatorExpanded: () => void;
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
          text: 'The backend clones the repository into a temporary workspace and performs static analysis only.'
        })
      )
    );
    return panel;
  }

  panel.appendChild(renderSectionJumpNav());
  panel.appendChild(renderProjectSection(result));
  panel.appendChild(renderRuntimeStackSection(result));
  panel.appendChild(renderFindingsSection(result.findings ?? [], state, actions));
  panel.appendChild(renderHttpSurfaceSection(result.httpSurfaceAnalysis, state, actions));
  panel.appendChild(renderConfigurationSection(result, state, actions));
  panel.appendChild(renderSpringApiUsageSection(result));
  panel.appendChild(renderComponentsSection(result.detectedComponents ?? [], state, actions));
  panel.appendChild(renderDependenciesSection(result.dependencies ?? [], state, actions));
  panel.appendChild(renderRawJsonSection(result, state, actions));
  return panel;
}

function renderProjectSection(result: AnalyzeRepositoryResponse): HTMLElement {
  const section = resultsSection('Project', 'results-project');
  const block = element('div', { className: 'project-summary-card project-summary-card-compact' });
  const rows = element('div', { className: 'project-summary-grid project-summary-grid-compact' });
  rows.appendChild(summaryValueRow('Repository URL', result.repositoryUrl ?? 'Unknown', true));
  rows.appendChild(summaryValueRow('Branch', result.branch?.trim() ? result.branch : 'default branch'));
  rows.appendChild(summaryValueRow('Workspace ID', result.workspaceId ?? 'Not returned', true));
  block.appendChild(rows);
  section.appendChild(block);
  return section;
}

function renderRuntimeStackSection(result: AnalyzeRepositoryResponse): HTMLElement {
  const runtime = result.runtimeStackAnalysis ?? {};
  const httpSummary = result.httpSurfaceAnalysis?.summary ?? {};
  const configSummary = result.configurationAnalysis?.summary ?? {};
  const findings = result.findings ?? [];
  const section = resultsSection('Runtime and stack', 'results-runtime');
  const overview = element('div', { className: 'runtime-overview' });
  const stackPanel = element(
    'div',
    { className: 'runtime-stack-panel' },
    element('div', { className: 'subsection-title', text: 'Stack overview' })
  );
  const stackList = element('div', { className: 'runtime-kv-list' });
  const mainClass = runtime.mainClass ?? normalizeMainApplicationClasses(result.mainApplicationClasses)[0]?.name ?? 'Unknown';

  const rows: Array<{ label: string; value: string; meta?: string | null; wide?: boolean }> = [
    { label: 'Spring Boot', value: runtime.springBootVersion ?? 'Unknown', meta: runtime.springBootVersionSource },
    { label: 'Java', value: runtime.javaVersion ?? result.javaVersionHint ?? 'Unknown' },
    { label: 'Build tool', value: result.buildTool ?? 'Unknown' },
    { label: 'Web stack', value: webStackLabel(runtime.webStack), meta: runtime.webStackReason },
    { label: 'Virtual threads', value: virtualThreadLabel(runtime), meta: runtimeVirtualThreadsCompactMeta(runtime) },
    { label: 'Main class', value: mainClass, wide: true }
  ];

  for (const row of rows) {
    stackList.appendChild(renderRuntimeKeyValueRow(row.label, row.value, row.meta, row.wide));
  }
  stackPanel.appendChild(stackList);

  const metricsPanel = element(
    'div',
    { className: 'runtime-metrics-panel' },
    element('div', { className: 'subsection-title', text: 'Metrics' })
  );
  const metricsGrid = element('div', { className: 'runtime-metric-grid' });
  const findingsMeta = summarizeFindings(findings);
  const metrics: Array<{ label: string; value: string; meta?: string | null; warning?: boolean }> = [
    { label: 'Inbound endpoints', value: String(httpSummary.inboundEndpointCount ?? 0) },
    { label: 'Outbound endpoints', value: String(httpSummary.outboundEndpointCount ?? 0) },
    {
      label: 'Configured properties',
      value: String(configSummary.configuredPropertyCount ?? (result.configurationAnalysis?.properties ?? []).length)
    },
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
  return section;
}

function renderFindingsSection(
  findings: Finding[],
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const section = resultsSection(`Findings (${findings.length})`, 'results-findings');
  if (findings.length === 0) {
    section.appendChild(
      element('div', { className: 'empty-note success-note', text: 'No issues detected by the current checks.' })
    );
    return section;
  }

  const summary = element('div', { className: 'stat-grid compact' });
  for (const severity of FINDING_SEVERITIES.slice(1)) {
    summary.appendChild(
      element(
        'div',
        { className: `mini-stat severity-${severity.toLowerCase()}` },
        element('div', { className: 'mini-stat-label', text: severityLabel(severity) }),
        element('div', {
          className: 'mini-stat-value',
          text: String(findings.filter((finding) => normalizeSeverity(finding.severity) === severity).length)
        })
      )
    );
  }
  section.appendChild(summary);

  const controls = element('div', { className: 'filter-row compact-filter-row' });
  controls.append(
    labeledInlineField(
      'Severity',
      selectInput(
        FINDING_SEVERITIES.map((value) => ({ value, label: value === 'ALL' ? 'All severities' : value })),
        state.findingsSeverity,
        actions.onFindingsSeverityChange
      )
    ),
    labeledInlineField('Text', textInput(state.findingsText, 'Filter findings', actions.onFindingsTextChange)),
    labeledInlineField('Grouping', checkboxField('Group similar findings', state.findingsGrouped, actions.onFindingsGroupedChange))
  );
  section.appendChild(controls);

  const filtered = findings.filter((finding) => {
    const severityMatches =
      state.findingsSeverity === 'ALL' || normalizeSeverity(finding.severity) === state.findingsSeverity;
    const textNeedle = state.findingsText.trim().toLowerCase();
    const derived = deriveFindingPresentation(finding);
    const haystack = [finding.message, finding.location, finding.rule, finding.category, derived.ruleType, derived.target]
      .filter((value): value is string => Boolean(value))
      .join(' ')
      .toLowerCase();
    return severityMatches && (!textNeedle || haystack.includes(textNeedle));
  });

  if (filtered.length === 0) {
    section.appendChild(element('p', { className: 'muted-text', text: 'No findings match the current filters.' }));
    return section;
  }

  const grouped = state.findingsGrouped ? groupFindings(filtered) : null;
  const sortedFindings: PresentedFinding[] = state.findingsGrouped
    ? sortFindingGroups(grouped ?? [], state.findingsSort)
    : sortFindings(filtered, state.findingsSort).map(deriveFindingPresentation);
  const visible = state.findingsExpanded ? sortedFindings : sortedFindings.slice(0, 25);
  const table = createTable([
    sortableHeader('Severity', 'severity', state.findingsSort, actions.onSetFindingsSort),
    sortableHeader('Rule / Type', 'rule', state.findingsSort, actions.onSetFindingsSort),
    sortableHeader('Target', 'target', state.findingsSort, actions.onSetFindingsSort),
    sortableHeader('Location', 'location', state.findingsSort, actions.onSetFindingsSort),
    sortableHeader('Message', 'message', state.findingsSort, actions.onSetFindingsSort)
  ], 'findings-table');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const derived of visible) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, [
      badgeCell(derived.severity, `badge badge-${derived.severity.toLowerCase()}`),
      truncateCell(derived.ruleType),
      truncateCell(derived.target),
      truncateCell(derived.location),
      truncateCell(derived.message, 'cell-wrap-two')
    ]);
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

  const section = resultsSection('HTTP surface', 'results-http');
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
  ], 'http-table');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const endpoint of visible) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, [
      badgeCell(endpoint.httpMethod ?? 'ANY', `badge badge-http`),
      truncateCell(endpoint.path ?? '/'),
      truncateCell(endpoint.controllerClass ?? 'Unknown'),
      truncateCell(endpoint.handlerMethod ?? 'Unknown'),
      truncateCell(sourceLabel(endpoint.sourceFile, endpoint.line)),
      truncateCell((endpoint.parameters ?? []).join(', ') || '—')
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
  ], 'http-table');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const endpoint of visible) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, [
      truncateCell(endpoint.clientType ?? 'HTTP client'),
      truncateCell(endpoint.method ?? 'REQUEST'),
      truncateCell(endpoint.fullUrlPreview ?? endpoint.urlOrTemplate ?? 'Unknown'),
      truncateCell(endpoint.host ?? '—'),
      truncateCell(sourceLabel(endpoint.sourceFile, endpoint.line)),
      truncateCell(endpoint.configurationPropertyName ?? '—')
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
  ], 'http-table');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const item of visible) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, [
      truncateCell(item.propertyName ?? 'Unknown'),
      truncateCell(item.referencedPropertyName ? `${item.value ?? '—'} -> ${item.referencedPropertyName}` : item.value ?? '—'),
      truncateCell(urlKindLabel(item.kind)),
      truncateCell(item.host ?? '—'),
      truncateCell(item.profile ?? 'default'),
      truncateCell(sourceLabel(item.sourceFile, item.line))
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
  const table = createTable(['Property', 'Value', 'Profile', 'Source', 'Exposed endpoints'], 'http-table');
  const tbody = table.querySelector('tbody') as HTMLTableSectionElement;
  for (const exposure of visible) {
    const row = tbody.insertRow();
    row.className = 'data-row';
    appendCells(row, [
      truncateCell(exposure.propertyName ?? 'Unknown'),
      truncateCell(exposure.value ?? '—'),
      truncateCell(exposure.profile ?? 'default'),
      truncateCell(sourceLabel(exposure.sourceFile, exposure.line)),
      truncateCell((exposure.exposedEndpoints ?? []).join(', ') || '—')
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
  const section = resultsSection('Configuration', 'results-configuration');

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

  if (properties.length === 0) {
    section.appendChild(element('div', { className: 'empty-note', text: 'No application configuration files or property references were detected.' }));
    return section;
  }

  const profiles = uniqueValues(properties.map((property) => property.profile ?? 'default'));
  const sourceFiles = uniqueValues(properties.map((property) => property.sourceFile));

  const primaryControls = element('div', { className: 'filter-row compact-filter-row configuration-primary-filters' });
  primaryControls.append(
    labeledInlineField('Search', textInput(state.configurationSearch, 'Search property', actions.onConfigurationSearchChange)),
    labeledInlineField(
      'Profile',
      selectInput(
        [{ value: 'ALL', label: 'All profiles' }, ...profiles.map((value) => ({ value, label: value }))],
        state.configurationProfile,
        actions.onConfigurationProfileChange
      )
    ),
    labeledInlineField(
      'Kind',
      selectInput(
        CONFIGURATION_KINDS.map((value) => ({
          value,
          label: value === 'ALL' ? 'All kinds' : configurationKindLabel(value)
        })),
        state.configurationKind,
        actions.onConfigurationKindChange
      )
    ),
    labeledInlineField('Sensitive', checkboxField('Sensitive only', state.configurationSensitiveOnly, actions.onConfigurationSensitiveOnlyChange))
  );
  section.appendChild(primaryControls);

  const secondaryControls = element('div', { className: 'filter-row compact-filter-row configuration-secondary-filters' });
  secondaryControls.append(
    labeledInlineField(
      'Source',
      selectInput(
        [{ value: 'ALL', label: 'All sources' }, ...sourceFiles.map((value) => ({ value, label: value }))],
        state.configurationSource,
        actions.onConfigurationSourceChange
      )
    )
  );
  section.appendChild(secondaryControls);

  const chipRow = element('div', { className: 'mode-switcher compact-chip-row' });
  chipRow.append(
    toggleChip('Flat list', state.configurationView === 'flat', () => actions.onConfigurationViewChange('flat')),
    toggleChip('Compare profiles', state.configurationView === 'compare', () => actions.onConfigurationViewChange('compare')),
    toggleChip('All', state.configurationKind === 'ALL' && !state.configurationSensitiveOnly && !state.configurationChangedOnly, () => {
      actions.onConfigurationKindChange('ALL');
      actions.onConfigurationSensitiveOnlyChange(false);
      actions.onConfigurationChangedOnlyChange(false);
    }),
    toggleChip('Spring Boot', state.configurationKind === 'SPRING_BOOT', () => actions.onConfigurationKindChange('SPRING_BOOT')),
    toggleChip('Custom', state.configurationKind === 'CUSTOM_CONFIGURATION_PROPERTIES', () => actions.onConfigurationKindChange('CUSTOM_CONFIGURATION_PROPERTIES')),
    toggleChip('Code referenced', state.configurationKind === 'CODE_REFERENCED', () => actions.onConfigurationKindChange('CODE_REFERENCED')),
    toggleChip('Third-party', state.configurationKind === 'THIRD_PARTY', () => actions.onConfigurationKindChange('THIRD_PARTY')),
    toggleChip('Unknown', state.configurationKind === 'UNKNOWN', () => actions.onConfigurationKindChange('UNKNOWN')),
    toggleChip('Sensitive', state.configurationSensitiveOnly, () => actions.onConfigurationSensitiveOnlyChange(!state.configurationSensitiveOnly)),
    toggleChip('Changed across profiles', state.configurationChangedOnly, () => actions.onConfigurationChangedOnlyChange(!state.configurationChangedOnly))
  );
  section.appendChild(chipRow);

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

  const filtered = properties.filter((property) => configurationMatches(property, state));
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
  const section = resultsSection('Spring API usage', 'results-spring-api');
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
  if ((result.dependencies ?? []).some((dependency) => dependency.includes('spring-boot-starter-actuator'))) {
    add('Actuator', 'spring-boot-starter-actuator');
  }
  if ((result.detectedComponents ?? []).some((component) => (component.annotationNames ?? []).includes('EnableScheduling'))) {
    add('Scheduling', '@EnableScheduling');
  }

  if (groups.size === 0) {
    section.appendChild(element('p', { className: 'muted-text', text: 'No Spring API usage patterns were detected.' }));
    return section;
  }

  const grid = element('div', { className: 'summary-grid runtime-grid' });
  for (const category of ['Bootstrap', 'Web', 'Configuration', 'Scheduling', 'Persistence', 'Actuator']) {
    const values = groups.get(category);
    if (!values?.length) {
      continue;
    }
    grid.appendChild(
      element(
        'div',
        { className: 'summary-card compact-summary-card' },
        element('div', { className: 'summary-label', text: category }),
        element('div', { className: 'summary-value', text: values.join(', ') })
      )
    );
  }
  section.appendChild(grid);
  return section;
}

function renderComponentsSection(
  components: DetectedClass[],
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const section = resultsSection(`Components (${components.length})`, 'results-components');
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
          actions.onComponentTypeChange
        )
      ),
      labeledInlineField('Text', textInput(state.componentText, 'Filter components', actions.onComponentTextChange))
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
      truncateCell(componentDisplayName(component)),
      badgeCell(componentTypeLabel(normalizeComponentType(component)), `badge badge-kind`),
      truncateCell(component.packageName ?? '—'),
      truncateCell(component.filePath ?? '—'),
      truncateCell((component.annotationNames ?? component.annotations ?? []).join(', ') || '—')
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
  dependencies: string[],
  state: ResultsViewState,
  actions: ResultsViewActions
): HTMLElement {
  const section = resultsSection(`Dependencies (${dependencies.length})`, 'results-dependencies');
  section.appendChild(textInput(state.dependencyText, 'Filter dependencies', actions.onDependencyTextChange));
  const filtered = dependencies.filter((dependency) =>
    dependency.toLowerCase().includes(state.dependencyText.trim().toLowerCase())
  );
  if (filtered.length === 0) {
    section.appendChild(element('p', { className: 'muted-text', text: 'No dependencies match the current filter.' }));
    return section;
  }

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
      truncateCell(propertyMeaning(property))
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

function resultsSection(title: string, id?: string): HTMLElement {
  return element('section', { className: 'results-section', attributes: id ? { id } : undefined }, element('h3', { text: title }));
}

function wrapTable(table: HTMLTableElement): HTMLElement {
  return element('div', { className: 'data-table-wrapper' }, table);
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

function badgeCell(text: string, badgeClass: string): HTMLTableCellElement {
  const cell = document.createElement('td');
  const badge = element('span', { className: badgeClass, text });
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
  onChange: (value: string) => void
): HTMLSelectElement {
  const select = element('select', { className: 'select-input' }) as HTMLSelectElement;
  for (const option of options) {
    select.appendChild(new Option(option.label, option.value, false, option.value === selectedValue));
  }
  select.value = selectedValue;
  select.addEventListener('change', () => onChange(select.value));
  return select;
}

function textInput(value: string, placeholder: string, onChange: (value: string) => void): HTMLInputElement {
  const input = element('input', {
    className: 'text-input',
    attributes: { type: 'text', placeholder }
  }) as HTMLInputElement;
  input.value = value;
  input.addEventListener('input', () => onChange(input.value));
  return input;
}

function checkboxField(label: string, checked: boolean, onChange: (value: boolean) => void): HTMLElement {
  const wrapper = element('label', { className: 'inline-checkbox' });
  const input = element('input', { attributes: { type: 'checkbox' } }) as HTMLInputElement;
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

function configurationMatches(property: ApplicationProperty, state: ResultsViewState): boolean {
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
  return (!searchNeedle || haystack.includes(searchNeedle)) && kindMatches && profileMatches && sourceMatches && sensitiveMatches;
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
  const button = element('button', {
    className: 'table-sort-button',
    text: sort.key === key ? `${label} ${sort.direction === 'asc' ? '↑' : '↓'}` : label,
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
  const normalized = message.toLowerCase();
  let ruleType = finding.rule || finding.category || 'Analyzer finding';
  let target = finding.location || '—';

  const riskyMatch = message.match(/^(?<property>[\w.-]+)=.+ is risky in production\./i);
  if (riskyMatch?.groups?.property) {
    ruleType = 'Risky production config';
    target = riskyMatch.groups.property;
  } else if (normalized.startsWith('sensitive configuration property appears to use a literal value')) {
    ruleType = 'Sensitive literal value';
    const propertyMatch = message.match(/:\s*([\w.-]+)\s*$/);
    target = propertyMatch?.[1] ?? target;
  } else if (normalized.startsWith('profile-specific configuration files')) {
    ruleType = 'Profile-specific config';
    target = 'profiles';
  } else if (normalized.includes('@configurationproperties prefix was found')) {
    ruleType = 'Orphan configuration prefix';
    const prefixMatch = message.match(/prefix\s+"([^"]+)"/i);
    target = prefixMatch?.[1] ?? target;
  }

  return {
    severity: normalizeSeverity(finding.severity),
    ruleType,
    target,
    location: buildFindingLocation(finding),
    message
  };
}

function groupFindings(findings: Finding[]): PresentedFinding[] {
  const grouped = new Map<string, ReturnType<typeof deriveFindingPresentation>[]>();
  for (const finding of findings) {
    const derived = deriveFindingPresentation(finding);
    const key = [derived.severity, derived.ruleType, derived.target].join('|');
    const bucket = grouped.get(key) ?? [];
    bucket.push(derived);
    grouped.set(key, bucket);
  }
  return [...grouped.values()].map((bucket) => ({
    severity: bucket[0].severity,
    ruleType: bucket[0].ruleType,
    target: bucket[0].target,
    message: bucket[0].message,
    location: [...new Set(bucket.map((item) => item.location))].join('; ')
  }));
}

function sortFindingGroups(findings: PresentedFinding[], sort: TableSortState): PresentedFinding[] {
  return [...findings].sort((left, right) =>
    compareValues(findingGroupSortValue(left, sort.key), findingGroupSortValue(right, sort.key), sort.direction)
  );
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
  if (property.kind === 'CODE_REFERENCED' || property.kind === 'CONDITIONAL_PROPERTY') {
    return 'Referenced in code but not configured in the scanned files.';
  }
  return 'No description available.';
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

function sourceLabel(sourceFile?: string | null, line?: number | null): string {
  if (sourceFile && line) {
    return `${sourceFile}:${line}`;
  }
  return sourceFile ?? 'Code reference';
}

function buildFindingLocation(finding: Finding): string {
  const locationParts = [finding.location, finding.rule, finding.category].filter(
    (value): value is string => Boolean(value)
  );
  return locationParts.join(' | ') || '—';
}

function configurationRowKey(property: ApplicationProperty): string {
  return [property.name ?? '', property.profile ?? 'default', property.sourceFile ?? '', property.line ?? '']
    .join('|');
}

function uniqueValues(values: Array<string | null | undefined>): string[] {
  return [...new Set(values.filter((value): value is string => Boolean(value)).sort((left, right) => left.localeCompare(right)))];
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
  return [...findings].sort((left, right) => compareValues(findingSortValue(left, sort.key), findingSortValue(right, sort.key), sort.direction));
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


import './styles.css';

import { analyzeRepository, ApiError, fetchSourceSnippet } from './api';
import { loadAnalysisSession, saveAnalysisSession } from './analysisSessionStore';
import { buildGitHubBlobUrl } from './code/githubLink';
import { clear, element } from './dom';
import {
  addOrUpdateRepositoryProfile,
  clearRepositoryFormState,
  deleteRepositoryProfile,
  findRepositoryById,
  inferAuthMode,
  loadRepositoryProfiles
} from './repositoryStore';
import { createTabNavigation, type AppTab } from './router';
import {
  addOrUpdateTokenProfile,
  deleteTokenProfile,
  findMatchingTokenProfile,
  findTokenProfileById,
  inferRepositoryHost,
  loadTokenProfiles,
  maskToken
} from './tokenStore';
import type {
  AnalysisMode,
  AnalyzeRepositoryRequest,
  AnalyzeRepositoryResponse,
  Finding,
  FindingOccurrence,
  HighlightRange,
  RepositoryProfile,
  SourceLocation,
  SourceSnippetResponse,
  TokenProfile
} from './types';
import { renderAnalyzeView } from './views/analyzeView';
import { type CodeSnippetModalState, type FindingCodeOccurrence, type ResultsViewState } from './views/resultsView';
import { renderSettingsView, type RepositoryFormModel, type TokenFormModel } from './views/settingsView';

const DEFAULT_REPOSITORY_URL = 'https://github.com/RobbanHoglund/tradingbot.git';
const THEME_STORAGE_KEY = 'spring-boot-analyzer-theme';
const THEME_OPTIONS = ['system', 'light', 'dark'] as const;

type ThemePreference = (typeof THEME_OPTIONS)[number];

interface AppState {
  currentTab: AppTab;
  themePreference: ThemePreference;
  analyzeMode: 'saved' | 'oneTime';
  tokenProfiles: TokenProfile[];
  repositoryProfiles: RepositoryProfile[];
  selectedSavedRepositoryId: string;
  oneTimeRepositoryUrl: string;
  oneTimeBranch: string;
  oneTimeTokenProfileId: string;
  analysisMode: AnalysisMode;
  statusMessage: string;
  errorMessage: string;
  warningMessage: string;
  isAnalyzing: boolean;
  analysisProgressIndex: number;
  result: AnalyzeRepositoryResponse | null;
  sidebarCollapsed: boolean;
  repositoryForm: RepositoryFormModel;
  tokenForm: TokenFormModel;
  resultsViewState: ResultsViewState;
}

interface FocusSnapshot {
  elementId: string;
  selectionStart: number | null;
  selectionEnd: number | null;
}

interface ScrollSnapshot {
  scrollX: number;
  scrollY: number;
}

const root = requiredElement<HTMLDivElement>('app');
const systemThemeMedia = typeof window !== 'undefined'
  ? window.matchMedia('(prefers-color-scheme: dark)')
  : null;

let state: AppState = createInitialState();
let analysisProgressTimer: number | null = null;
let pendingFocusElementId: string | null = null;
let codeModalRequestToken = 0;
applyThemePreference(state.themePreference);
systemThemeMedia?.addEventListener('change', handleSystemThemeChange);
render();

function createInitialState(): AppState {
  const tokenProfiles = loadTokenProfiles();
  const repositoryProfiles = loadRepositoryProfiles();
  const defaultOneTimeTokenProfile = findMatchingTokenProfile(DEFAULT_REPOSITORY_URL, tokenProfiles);
  const persistedSession = loadAnalysisSession();
  const defaultResultsViewState = createDefaultResultsViewState();
  const restoredSavedRepositoryId = persistedSession?.selectedSavedRepositoryId;
  const selectedSavedRepositoryId = restoredSavedRepositoryId && repositoryProfiles.some((profile) => profile.id === restoredSavedRepositoryId)
    ? restoredSavedRepositoryId
    : repositoryProfiles[0]?.id ?? '';
  const restoredOneTimeTokenProfileId = persistedSession?.oneTimeTokenProfileId;
  const oneTimeTokenProfileId = restoredOneTimeTokenProfileId && tokenProfiles.some((profile) => profile.id === restoredOneTimeTokenProfileId)
    ? restoredOneTimeTokenProfileId
    : defaultOneTimeTokenProfile?.id ?? '';

  return {
    currentTab: persistedSession?.currentTab === 'settings' ? 'settings' : 'analyze',
    themePreference: loadThemePreference(),
    analyzeMode: persistedSession?.analyzeMode === 'oneTime' ? 'oneTime' : 'saved',
    tokenProfiles,
    repositoryProfiles,
    selectedSavedRepositoryId,
    oneTimeRepositoryUrl: persistedSession?.oneTimeRepositoryUrl ?? DEFAULT_REPOSITORY_URL,
    oneTimeBranch: persistedSession?.oneTimeBranch ?? '',
    oneTimeTokenProfileId,
    analysisMode: persistedSession?.analysisMode === 'STATIC_PLUS_GRADLE_MODEL' ? 'STATIC_PLUS_GRADLE_MODEL' : 'STATIC_ONLY',
    statusMessage: persistedSession?.result ? 'Restored previous analysis from this browser tab.' : '',
    errorMessage: '',
    warningMessage: '',
    isAnalyzing: false,
    analysisProgressIndex: 0,
    result: persistedSession?.result ?? null,
    sidebarCollapsed: Boolean(persistedSession?.sidebarCollapsed),
    repositoryForm: createEmptyRepositoryForm(),
    tokenForm: createEmptyTokenForm(),
    resultsViewState: mergeResultsViewState(defaultResultsViewState, persistedSession?.resultsViewState)
  };
}

function createDefaultResultsViewState(): ResultsViewState {
  return {
    findingsSeverity: 'ALL',
    findingsCategory: 'ALL',
    findingsRuntimeDetection: 'ALL',
    findingsConfidence: 'ALL',
    findingsText: '',
    findingsExpanded: false,
    findingsGrouped: true,
    configurationSearch: '',
    configurationFocus: 'ALL',
    configurationProfile: 'ALL',
    configurationSource: 'ALL',
    configurationKind: 'ALL',
    configurationSensitiveOnly: false,
    configurationView: 'flat',
    configurationChangedOnly: false,
    configurationExpanded: false,
    configurationExpandedRowKey: null,
    findingsSort: { key: 'severity', direction: 'desc' },
    inboundSort: { key: 'path', direction: 'asc' },
    outboundSort: { key: 'host', direction: 'asc' },
    configuredUrlsSort: { key: 'property', direction: 'asc' },
    configurationSort: { key: 'property', direction: 'asc' },
    componentsSort: { key: 'class', direction: 'asc' },
    dependenciesSort: { key: 'group', direction: 'asc' },
    componentType: 'ALL',
    componentText: '',
    componentsExpanded: false,
    dependencyText: '',
    resolvedDependencyConfiguration: 'ALL',
    resolvedDependencyDirectOnly: false,
    rawJsonExpanded: false,
    httpInboundExpanded: false,
    httpOutboundExpanded: false,
    httpConfiguredExpanded: false,
    httpActuatorExpanded: false,
    codeModal: createClosedCodeModalState()
  };
}

function mergeResultsViewState(
  defaults: ResultsViewState,
  restored: Partial<Omit<ResultsViewState, 'codeModal'>> | undefined
): ResultsViewState {
  return {
    ...defaults,
    ...restored,
    findingsSort: mergeSortState(defaults.findingsSort, restored?.findingsSort),
    inboundSort: mergeSortState(defaults.inboundSort, restored?.inboundSort),
    outboundSort: mergeSortState(defaults.outboundSort, restored?.outboundSort),
    configuredUrlsSort: mergeSortState(defaults.configuredUrlsSort, restored?.configuredUrlsSort),
    configurationSort: mergeSortState(defaults.configurationSort, restored?.configurationSort),
    componentsSort: mergeSortState(defaults.componentsSort, restored?.componentsSort),
    dependenciesSort: mergeSortState(defaults.dependenciesSort, restored?.dependenciesSort),
    codeModal: createClosedCodeModalState()
  };
}

function mergeSortState(
  defaults: { key: string; direction: 'asc' | 'desc' },
  restored: Partial<{ key: string; direction: 'asc' | 'desc' }> | undefined
): { key: string; direction: 'asc' | 'desc' } {
  return {
    key: restored?.key ?? defaults.key,
    direction: restored?.direction === 'asc' || restored?.direction === 'desc' ? restored.direction : defaults.direction
  };
}

function createClosedCodeModalState(): CodeSnippetModalState {
  return {
    open: false,
    title: '',
    summary: '',
    ruleType: '',
    target: '—',
    severity: 'INFO',
    category: 'MAINTAINABILITY',
    confidence: 'MEDIUM',
    runtimeDetection: 'NOT_NORMALLY_DETECTED',
    analysisId: null,
    occurrences: [],
    selectedOccurrenceIndex: 0,
    snippet: null,
    loading: false,
    errorMessage: '',
    returnFocusId: null
  };
}

function render(): void {
  const focusSnapshot = captureFocusSnapshot();
  const scrollSnapshot = captureScrollSnapshot();
  clear(root);

  const shell = element('div', { className: 'page-shell' });
  shell.appendChild(
    element(
      'header',
      { className: 'page-header' },
      element(
        'div',
        { className: 'page-header-bar' },
        element(
          'div',
          { className: 'page-header-copy' },
          element('h1', { text: 'Spring Boot Analyzer' }),
          element(
            'p',
            {
              text: 'Clone and analyze Spring Boot repositories without starting the application.'
            }
          )
        ),
        renderThemeControl()
      )
    )
  );

  shell.appendChild(
    createTabNavigation(state.currentTab, (tab) => {
      state.currentTab = tab;
      render();
    })
  );

  if (state.currentTab === 'analyze') {
    shell.appendChild(
      renderAnalyzeView(
        {
          analyzeMode: state.analyzeMode,
          repositoryProfiles: state.repositoryProfiles,
          tokenProfiles: state.tokenProfiles,
          selectedSavedRepositoryId: state.selectedSavedRepositoryId,
          oneTimeRepositoryUrl: state.oneTimeRepositoryUrl,
          oneTimeBranch: state.oneTimeBranch,
          oneTimeTokenProfileId: state.oneTimeTokenProfileId,
          analysisMode: state.analysisMode,
          statusMessage: state.statusMessage,
          errorMessage: state.errorMessage,
          warningMessage: state.warningMessage,
          isAnalyzing: state.isAnalyzing,
          analysisProgressIndex: state.analysisProgressIndex,
          result: state.result,
          resultsViewState: state.resultsViewState,
          sidebarCollapsed: state.sidebarCollapsed
        },
        {
          onSetAnalyzeMode: (mode) => {
            state.analyzeMode = mode;
            render();
          },
          onSelectSavedRepository: (repositoryId) => {
            state.selectedSavedRepositoryId = repositoryId;
            render();
          },
          onAnalyzeSavedRepository: () => {
            void analyzeSavedRepository();
          },
          onUpdateOneTimeRepositoryUrl: (value) => {
            state.oneTimeRepositoryUrl = value;
            const matchingProfile = findMatchingTokenProfile(value, state.tokenProfiles);
            const isSsh = inferAuthMode(value) === 'ssh';
            if (isSsh) {
              state.warningMessage = state.oneTimeTokenProfileId
                ? 'SSH repository selected. Browser-stored HTTPS tokens will not be sent.'
                : '';
            } else if (matchingProfile) {
              state.oneTimeTokenProfileId = matchingProfile.id;
              state.warningMessage = '';
            } else {
              state.warningMessage = '';
            }
            render();
          },
          onUpdateOneTimeBranch: (value) => {
            state.oneTimeBranch = value;
            render();
          },
          onUpdateOneTimeTokenProfileId: (value) => {
            state.oneTimeTokenProfileId = value;
            render();
          },
          onAnalyzeOneTimeRepository: () => {
            void analyzeOneTimeRepository();
          },
          onAnalysisModeChange: (value) => {
            state.analysisMode = value;
            render();
          },
          onFindingsSeverityChange: (value) => {
            state.resultsViewState.findingsSeverity = value;
            render();
          },
          onFindingsCategoryChange: (value) => {
            state.resultsViewState.findingsCategory = value;
            render();
          },
          onFindingsRuntimeDetectionChange: (value) => {
            state.resultsViewState.findingsRuntimeDetection = value;
            render();
          },
          onFindingsConfidenceChange: (value) => {
            state.resultsViewState.findingsConfidence = value;
            render();
          },
          onFindingsTextChange: (value) => {
            state.resultsViewState.findingsText = value;
            render();
          },
          onToggleFindingsExpanded: () => {
            state.resultsViewState.findingsExpanded = !state.resultsViewState.findingsExpanded;
            render();
          },
          onFindingsGroupedChange: (value) => {
            state.resultsViewState.findingsGrouped = value;
            render();
          },
            onConfigurationSearchChange: (value) => {
              state.resultsViewState.configurationSearch = value;
              render();
            },
            onConfigurationFocusChange: (value) => {
              state.resultsViewState.configurationFocus = value;
              render();
            },
            onConfigurationProfileChange: (value) => {
              state.resultsViewState.configurationProfile = value;
              render();
            },
          onConfigurationSourceChange: (value) => {
            state.resultsViewState.configurationSource = value;
            render();
          },
          onConfigurationKindChange: (value) => {
            state.resultsViewState.configurationKind = value;
            render();
          },
          onConfigurationSensitiveOnlyChange: (value) => {
            state.resultsViewState.configurationSensitiveOnly = value;
            render();
          },
          onConfigurationViewChange: (value) => {
            state.resultsViewState.configurationView = value;
            render();
          },
          onConfigurationChangedOnlyChange: (value) => {
            state.resultsViewState.configurationChangedOnly = value;
            render();
          },
          onToggleConfigurationExpanded: () => {
            state.resultsViewState.configurationExpanded = !state.resultsViewState.configurationExpanded;
            render();
          },
          onToggleConfigurationRow: (key) => {
            state.resultsViewState.configurationExpandedRowKey =
              state.resultsViewState.configurationExpandedRowKey === key ? null : key;
            render();
          },
          onSetFindingsSort: (key) => {
            toggleSort(state.resultsViewState.findingsSort, key);
            render();
          },
          onSetInboundSort: (key) => {
            toggleSort(state.resultsViewState.inboundSort, key);
            render();
          },
          onSetOutboundSort: (key) => {
            toggleSort(state.resultsViewState.outboundSort, key);
            render();
          },
          onSetConfiguredUrlsSort: (key) => {
            toggleSort(state.resultsViewState.configuredUrlsSort, key);
            render();
          },
          onSetConfigurationSort: (key) => {
            toggleSort(state.resultsViewState.configurationSort, key);
            render();
          },
          onSetComponentsSort: (key) => {
            toggleSort(state.resultsViewState.componentsSort, key);
            render();
          },
          onSetDependenciesSort: (key) => {
            toggleSort(state.resultsViewState.dependenciesSort, key);
            render();
          },
          onComponentTypeChange: (value) => {
            state.resultsViewState.componentType = value;
            render();
          },
          onComponentTextChange: (value) => {
            state.resultsViewState.componentText = value;
            render();
          },
          onToggleComponentsExpanded: () => {
            state.resultsViewState.componentsExpanded = !state.resultsViewState.componentsExpanded;
            render();
          },
          onDependencyTextChange: (value) => {
            state.resultsViewState.dependencyText = value;
            render();
          },
          onResolvedDependencyConfigurationChange: (value) => {
            state.resultsViewState.resolvedDependencyConfiguration = value;
            render();
          },
          onResolvedDependencyDirectOnlyChange: (value) => {
            state.resultsViewState.resolvedDependencyDirectOnly = value;
            render();
          },
          onRawJsonExpandedChange: (value) => {
            state.resultsViewState.rawJsonExpanded = value;
            render();
          },
          onToggleHttpInboundExpanded: () => {
            state.resultsViewState.httpInboundExpanded = !state.resultsViewState.httpInboundExpanded;
            render();
          },
          onToggleHttpOutboundExpanded: () => {
            state.resultsViewState.httpOutboundExpanded = !state.resultsViewState.httpOutboundExpanded;
            render();
          },
          onToggleHttpConfiguredExpanded: () => {
            state.resultsViewState.httpConfiguredExpanded = !state.resultsViewState.httpConfiguredExpanded;
            render();
          },
          onToggleHttpActuatorExpanded: () => {
            state.resultsViewState.httpActuatorExpanded = !state.resultsViewState.httpActuatorExpanded;
            render();
          },
          onOpenFindingCode: (finding, groupedFindings, triggerId, selectedOccurrenceIndex) => {
            void openFindingCodeModal(finding, groupedFindings, triggerId, selectedOccurrenceIndex);
          },
          onCloseFindingCode: () => {
            closeFindingCodeModal();
          },
          onSelectFindingCodeOccurrence: (index) => {
            void selectFindingCodeOccurrence(index);
          },
          onToggleSidebarCollapsed: () => {
            state.sidebarCollapsed = !state.sidebarCollapsed;
            render();
          }
        }
      )
    );
  } else {
    shell.appendChild(
      renderSettingsView(
        {
          repositoryProfiles: state.repositoryProfiles,
          tokenProfiles: state.tokenProfiles,
          repositoryForm: state.repositoryForm,
          tokenForm: state.tokenForm
        },
        {
          onRepositoryFormChange: (field, value) => {
            updateRepositoryForm(field, value);
          },
          onSaveRepository: () => {
            saveRepositoryProfile();
          },
          onDeleteSelectedRepository: () => {
            deleteSelectedRepositoryProfile();
          },
          onClearRepositoryForm: () => {
            state.repositoryForm = createEmptyRepositoryForm();
            clearRepositoryFormState();
            render();
          },
          onEditRepository: (repositoryId) => {
            editRepositoryProfile(repositoryId);
          },
          onDeleteRepository: (repositoryId) => {
            deleteRepositoryById(repositoryId);
          },
          onAnalyzeRepository: (repositoryId) => {
            state.currentTab = 'analyze';
            state.analyzeMode = 'saved';
            state.selectedSavedRepositoryId = repositoryId;
            render();
            void analyzeSavedRepository();
          },
          onTokenFormChange: (field, value) => {
            updateTokenForm(field, value);
          },
          onSaveToken: () => {
            saveTokenProfile();
          },
          onDeleteSelectedToken: () => {
            deleteSelectedTokenProfile();
          },
          onClearTokenForm: () => {
            state.tokenForm = createEmptyTokenForm();
            render();
          },
          onEditToken: (tokenProfileId) => {
            editTokenProfile(tokenProfileId);
          },
          onDeleteToken: (tokenProfileId) => {
            deleteTokenById(tokenProfileId);
          }
        }
      )
    );
  }

  root.appendChild(shell);
  persistAnalysisSession();
  restoreFocusSnapshot(focusSnapshot);
  restorePendingFocus();
  restoreScrollSnapshot(scrollSnapshot);
}

function restorePendingFocus(): void {
  if (!pendingFocusElementId) {
    return;
  }
  const elementToFocus = document.getElementById(pendingFocusElementId) as HTMLElement | null;
  pendingFocusElementId = null;
  elementToFocus?.focus();
}

async function analyzeSavedRepository(): Promise<void> {
  const repository = state.repositoryProfiles.find(
    (profile) => profile.id === state.selectedSavedRepositoryId
  );

  if (!repository) {
    state.errorMessage = 'Select a saved repository before analyzing.';
    render();
    return;
  }

  const request: AnalyzeRepositoryRequest = {
    repositoryUrl: repository.repositoryUrl,
    analysisMode: state.analysisMode
  };

  if (repository.branch?.trim()) {
    request.branch = repository.branch;
  }

  state.warningMessage = '';

  if (repository.authMode === 'token') {
    if (!repository.tokenProfileId) {
      state.errorMessage = 'The saved repository requires a default token profile, but none is configured.';
      render();
      return;
    }

    const tokenProfile = state.tokenProfiles.find((profile) => profile.id === repository.tokenProfileId);
    if (!tokenProfile) {
      state.errorMessage = 'The default token profile for this repository is missing.';
      render();
      return;
    }

    if (inferAuthMode(repository.repositoryUrl) === 'ssh') {
      state.warningMessage = 'SSH repositories use the backend server SSH configuration. Browser tokens are not sent.';
    } else {
      request.credentials = {
        username: tokenProfile.username,
        token: tokenProfile.token
      };
    }
  }

  if (repository.authMode === 'ssh') {
    state.warningMessage = 'SSH repositories use the backend server SSH configuration.';
  }

  await runAnalysis(request);
}

async function analyzeOneTimeRepository(): Promise<void> {
  const repositoryUrl = state.oneTimeRepositoryUrl.trim();
  if (!repositoryUrl) {
    state.errorMessage = 'Repository URL is required.';
    render();
    return;
  }

  const request: AnalyzeRepositoryRequest = {
    repositoryUrl,
    analysisMode: state.analysisMode
  };

  if (state.oneTimeBranch.trim()) {
    request.branch = state.oneTimeBranch.trim();
  }

  state.warningMessage = '';

  const urlAuthMode = inferAuthMode(repositoryUrl);
  if (urlAuthMode === 'ssh') {
    if (state.oneTimeTokenProfileId) {
      state.warningMessage =
        'SSH repository selected. Browser-stored HTTPS tokens were ignored. SSH repositories use the backend server SSH configuration.';
    } else {
      state.warningMessage = 'SSH repositories use the backend server SSH configuration.';
    }
  } else if (state.oneTimeTokenProfileId) {
    const tokenProfile = state.tokenProfiles.find((profile) => profile.id === state.oneTimeTokenProfileId);
    if (!tokenProfile) {
      state.errorMessage = 'The selected token profile could not be found.';
      render();
      return;
    }

    request.credentials = {
      username: tokenProfile.username,
      token: tokenProfile.token
    };
  }

  await runAnalysis(request);
}

async function runAnalysis(request: AnalyzeRepositoryRequest): Promise<void> {
  state.isAnalyzing = true;
  state.analysisProgressIndex = 0;
  state.statusMessage = 'Preparing analysis workspace...';
  state.errorMessage = '';
  state.resultsViewState.codeModal = createClosedCodeModalState();
  startAnalysisProgressTimer();
  render();

  try {
    state.result = await analyzeRepository(request);
    state.statusMessage = 'Analysis complete.';
    resetResultsViewState();
  } catch (error) {
    state.statusMessage = '';
    state.errorMessage = error instanceof ApiError ? error.message : 'Analysis request failed.';
  } finally {
    stopAnalysisProgressTimer();
    state.isAnalyzing = false;
    state.analysisProgressIndex = 0;
    render();
  }
}

async function openFindingCodeModal(
  finding: Finding,
  groupedFindings: Finding[],
  triggerId: string,
  selectedOccurrenceIndex = 0
): Promise<void> {
  const analysisId = state.result?.analysisId ?? state.result?.workspaceId ?? null;
  const occurrences = buildFindingCodeOccurrences(groupedFindings.length > 0 ? groupedFindings : [finding]);
  if (!analysisId || occurrences.length === 0) {
    return;
  }
  const clampedOccurrenceIndex = Math.min(Math.max(selectedOccurrenceIndex, 0), occurrences.length - 1);
  state.resultsViewState.codeModal = {
    open: true,
    title: finding.title ?? finding.ruleId ?? finding.rule ?? 'Finding',
    summary: finding.message ?? 'No summary available.',
    ruleType: finding.ruleId ?? finding.rule ?? '',
    target: finding.target ?? '—',
    severity: finding.severity ?? 'INFO',
    category: finding.category ?? 'MAINTAINABILITY',
    confidence: finding.confidence ?? 'MEDIUM',
    runtimeDetection: finding.runtimeDetection ?? 'NOT_NORMALLY_DETECTED',
    analysisId,
    occurrences,
    selectedOccurrenceIndex: clampedOccurrenceIndex,
    snippet: null,
    loading: true,
    errorMessage: '',
    returnFocusId: triggerId
  };
  pendingFocusElementId = 'code-snippet-modal-close';
  render();
  await loadCodeSnippetForSelection();
}

function closeFindingCodeModal(): void {
  const returnFocusId = state.resultsViewState.codeModal.returnFocusId;
  state.resultsViewState.codeModal = createClosedCodeModalState();
  pendingFocusElementId = returnFocusId;
  render();
}

async function selectFindingCodeOccurrence(index: number): Promise<void> {
  const modal = state.resultsViewState.codeModal;
  if (!modal.open || index < 0 || index >= modal.occurrences.length) {
    return;
  }
  modal.selectedOccurrenceIndex = index;
  modal.loading = true;
  modal.errorMessage = '';
  modal.snippet = null;
  render();
  await loadCodeSnippetForSelection();
}

async function loadCodeSnippetForSelection(): Promise<void> {
  const modal = state.resultsViewState.codeModal;
  if (!modal.open || !modal.analysisId) {
    return;
  }
  const occurrence = modal.occurrences[modal.selectedOccurrenceIndex];
  const filePath = occurrence?.location?.filePath;
  const startLine = occurrence?.location?.startLine;
  const endLine = occurrence?.location?.endLine ?? startLine;
  if (!filePath) {
    modal.loading = false;
    modal.errorMessage = 'Source location is unavailable for this finding.';
    render();
    return;
  }

  const requestToken = ++codeModalRequestToken;
  try {
    const snippet = await fetchSourceSnippet(
      modal.analysisId,
      filePath,
      typeof startLine === 'number' ? startLine : null,
      typeof startLine === 'number' ? (endLine ?? startLine) : null,
      6
    );
    if (requestToken !== codeModalRequestToken || !state.resultsViewState.codeModal.open) {
      return;
    }
    state.resultsViewState.codeModal.snippet = mergeSnippetHighlights(snippet, occurrence.highlightRanges);
    state.resultsViewState.codeModal.loading = false;
    state.resultsViewState.codeModal.errorMessage = '';
    pendingFocusElementId = 'code-snippet-modal-close';
    render();
  } catch (error) {
    if (requestToken !== codeModalRequestToken || !state.resultsViewState.codeModal.open) {
      return;
    }
    state.resultsViewState.codeModal.snippet = null;
    state.resultsViewState.codeModal.loading = false;
    state.resultsViewState.codeModal.errorMessage =
      error instanceof ApiError ? error.message : 'Source snippet could not be loaded.';
    pendingFocusElementId = 'code-snippet-modal-close';
    render();
  }
}

function buildFindingCodeOccurrences(findings: Finding[]): FindingCodeOccurrence[] {
  const occurrences: FindingCodeOccurrence[] = [];
  findings.forEach((finding, findingIndex) => {
    const explicitOccurrences = finding.occurrences ?? [];
    if (explicitOccurrences.length > 0) {
      explicitOccurrences.forEach((occurrence, occurrenceIndex) => {
        const location = withGitHubUrl(normalizeSourceLocation(occurrence.location ?? undefined, finding));
        if (!location?.filePath) {
          return;
        }
        occurrences.push({
          key: `${findingIndex}-${occurrenceIndex}-${location.filePath}-${location.startLine ?? 'file'}`,
          label: sourceLocationLabel(location),
          summary: occurrence.message ?? finding.message ?? 'No summary available.',
          location,
          highlightRanges: normalizeHighlightRanges(occurrence.highlightRanges, location)
        });
      });
      return;
    }

    const location = withGitHubUrl(normalizeSourceLocation(finding.primaryLocation, finding));
    if (!location?.filePath) {
      return;
    }
    occurrences.push({
      key: `${findingIndex}-primary-${location.filePath}-${location.startLine ?? 'file'}`,
      label: sourceLocationLabel(location),
      summary: finding.message ?? 'No summary available.',
      location,
      highlightRanges: normalizeHighlightRanges(finding.highlightRanges, location)
    });
  });
  return occurrences;
}

function normalizeSourceLocation(location: SourceLocation | undefined, finding: Finding): SourceLocation | null {
  if (location?.filePath) {
    const startLine = location.startLine && location.startLine > 0 ? location.startLine : undefined;
    const endLine = location.endLine && location.endLine > 0 ? location.endLine : startLine;
    return {
      ...location,
      startLine,
      endLine,
      language: location.language ?? inferSourceLanguage(location.filePath)
    };
  }
  if (!finding.sourceFile) {
    return null;
  }
  return {
    filePath: finding.sourceFile,
    startLine: finding.line ?? undefined,
    endLine: finding.line ?? undefined,
    symbol: finding.target ?? undefined,
    language: inferSourceLanguage(finding.sourceFile)
  };
}

function withGitHubUrl(location: SourceLocation | null): SourceLocation | null {
  if (!location?.filePath) {
    return location;
  }
  if (location.githubUrl) {
    return location;
  }
  const githubUrl = buildGitHubBlobUrl(
    state.result?.repositoryUrl,
    state.result?.commitSha ?? state.result?.branch ?? null,
    location.filePath,
    location.startLine,
    location.endLine
  );
  return githubUrl ? { ...location, githubUrl } : location;
}

function normalizeHighlightRanges(ranges: HighlightRange[] | undefined, location: SourceLocation): HighlightRange[] {
  if (ranges && ranges.length > 0) {
    return ranges;
  }
  if (!location.startLine) {
    return [];
  }
  const startLine = location.startLine;
  const endLine = location.endLine ?? startLine;
  return [{ startLine, endLine, kind: 'issue' }];
}

function sourceLocationLabel(location: SourceLocation): string {
  if (!location.filePath) {
    return 'Source unavailable';
  }
  if (!location.startLine) {
    return location.filePath;
  }
  if (!location.endLine || location.endLine <= location.startLine) {
    return `${location.filePath}:${location.startLine}`;
  }
  return `${location.filePath}:${location.startLine}-${location.endLine}`;
}

function mergeSnippetHighlights(
  snippet: SourceSnippetResponse,
  ranges: HighlightRange[]
): SourceSnippetResponse {
  return {
    ...snippet,
    highlightRanges: ranges.length > 0 ? ranges : snippet.highlightRanges
  };
}

function inferSourceLanguage(filePath: string): string {
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

function startAnalysisProgressTimer(): void {
  stopAnalysisProgressTimer();
  analysisProgressTimer = window.setInterval(() => {
    if (!state.isAnalyzing) {
      stopAnalysisProgressTimer();
      return;
    }

    const nextIndex = Math.min(state.analysisProgressIndex + 1, ANALYSIS_PROGRESS_STEPS.length - 1);
    state.analysisProgressIndex = nextIndex;
    state.statusMessage = ANALYSIS_PROGRESS_STEPS[nextIndex].description;
    render();
  }, 1600);
}

function stopAnalysisProgressTimer(): void {
  if (analysisProgressTimer !== null) {
    window.clearInterval(analysisProgressTimer);
    analysisProgressTimer = null;
  }
}

function saveTokenProfile(): void {
  const form = state.tokenForm;
  if (!form.name.trim() || !form.host.trim() || !form.username.trim()) {
    state.tokenForm.errorMessage = 'Profile name, host, and username are required.';
    render();
    return;
  }

  try {
    state.tokenProfiles = addOrUpdateTokenProfile({
      id: form.id || undefined,
      name: form.name,
      provider: form.provider,
      host: form.host,
      username: form.username,
      token: form.token
    });

    const savedProfile = form.id
      ? state.tokenProfiles.find((profile) => profile.id === form.id)
      : state.tokenProfiles[0];

    state.tokenForm = savedProfile ? tokenFormFromProfile(savedProfile) : createEmptyTokenForm();
    syncRepositoryFormTokenSelection();
  } catch (error) {
    state.tokenForm.errorMessage = error instanceof Error ? error.message : 'Failed to save token profile.';
  }

  render();
}

function deleteSelectedTokenProfile(): void {
  if (!state.tokenForm.id) {
    state.tokenForm.errorMessage = 'Select a token profile to delete it.';
    render();
    return;
  }

  deleteTokenById(state.tokenForm.id);
}

function deleteTokenById(tokenProfileId: string): void {
  state.tokenProfiles = deleteTokenProfile(tokenProfileId);
  state.tokenForm = createEmptyTokenForm();

  if (state.oneTimeTokenProfileId === tokenProfileId) {
    state.oneTimeTokenProfileId = '';
  }

  state.repositoryProfiles = state.repositoryProfiles.map((repository) =>
    repository.tokenProfileId === tokenProfileId
      ? { ...repository, tokenProfileId: null, authMode: repository.authMode === 'token' ? 'none' : repository.authMode }
      : repository
  );

  saveRepositoryProfilesToState();
  render();
}

function editTokenProfile(tokenProfileId: string): void {
  const profile = state.tokenProfiles.find((entry) => entry.id === tokenProfileId);
  if (!profile) {
    return;
  }

  state.tokenForm = tokenFormFromProfile(profile);
  render();
}

function saveRepositoryProfile(): void {
  const form = state.repositoryForm;
  if (!form.name.trim() || !form.repositoryUrl.trim()) {
    state.repositoryForm.errorMessage = 'Repository name and repository URL are required.';
    render();
    return;
  }

  if (form.authMode === 'token' && !form.tokenProfileId) {
    state.repositoryForm.errorMessage = 'Choose a token profile when auth mode is HTTPS token.';
    render();
    return;
  }

  try {
    state.repositoryProfiles = addOrUpdateRepositoryProfile({
      id: form.id || undefined,
      name: form.name,
      repositoryUrl: form.repositoryUrl,
      branch: form.branch,
      authMode: form.authMode,
      tokenProfileId: form.tokenProfileId || null,
      notes: form.notes
    });

    const savedRepository = form.id
      ? state.repositoryProfiles.find((repository) => repository.id === form.id)
      : state.repositoryProfiles[0];

    if (savedRepository) {
      state.repositoryForm = repositoryFormFromProfile(savedRepository);
      state.selectedSavedRepositoryId = savedRepository.id;
    } else {
      state.repositoryForm = createEmptyRepositoryForm();
    }
  } catch (error) {
    state.repositoryForm.errorMessage =
      error instanceof Error ? error.message : 'Failed to save repository profile.';
  }

  render();
}

function deleteSelectedRepositoryProfile(): void {
  if (!state.repositoryForm.id) {
    state.repositoryForm.errorMessage = 'Select a repository profile to delete it.';
    render();
    return;
  }

  deleteRepositoryById(state.repositoryForm.id);
}

function deleteRepositoryById(repositoryId: string): void {
  state.repositoryProfiles = deleteRepositoryProfile(repositoryId);
  state.repositoryForm = createEmptyRepositoryForm();

  if (state.selectedSavedRepositoryId === repositoryId) {
    state.selectedSavedRepositoryId = state.repositoryProfiles[0]?.id ?? '';
  }

  render();
}

function editRepositoryProfile(repositoryId: string): void {
  const repository = state.repositoryProfiles.find((entry) => entry.id === repositoryId);
  if (!repository) {
    return;
  }

  state.repositoryForm = repositoryFormFromProfile(repository);
  render();
}

function updateRepositoryForm(field: keyof RepositoryFormModel, value: string): void {
  const next = { ...state.repositoryForm, [field]: value, errorMessage: '' };

  if (field === 'repositoryUrl') {
    const inferredAuthMode = inferAuthMode(value);
    if (inferredAuthMode === 'ssh') {
      next.authMode = 'ssh';
      next.tokenProfileId = '';
    } else if (next.authMode === 'ssh') {
      next.authMode = 'none';
    }

    const matchingProfile = findMatchingTokenProfile(value, state.tokenProfiles);
    if (matchingProfile && next.authMode === 'token' && !next.tokenProfileId) {
      next.tokenProfileId = matchingProfile.id;
    }
  }

  if (field === 'authMode') {
    const authMode = value as RepositoryProfile['authMode'];
    next.authMode = authMode;
    if (authMode !== 'token') {
      next.tokenProfileId = '';
    } else if (!next.tokenProfileId) {
      const matchingProfile = findMatchingTokenProfile(next.repositoryUrl, state.tokenProfiles);
      if (matchingProfile) {
        next.tokenProfileId = matchingProfile.id;
      }
    }
  }

  if (field === 'tokenProfileId' && next.authMode !== 'token') {
    next.tokenProfileId = '';
  }

  state.repositoryForm = next;
  render();
}

function updateTokenForm(field: keyof TokenFormModel, value: string): void {
  state.tokenForm = {
    ...state.tokenForm,
    [field]: value,
    errorMessage: ''
  };
  render();
}

function syncRepositoryFormTokenSelection(): void {
  if (state.repositoryForm.authMode !== 'token') {
    return;
  }

  if (state.repositoryForm.tokenProfileId) {
    const exists = state.tokenProfiles.some((profile) => profile.id === state.repositoryForm.tokenProfileId);
    if (!exists) {
      state.repositoryForm.tokenProfileId = '';
    }
  }

  if (!state.repositoryForm.tokenProfileId) {
    const matchingProfile = findMatchingTokenProfile(state.repositoryForm.repositoryUrl, state.tokenProfiles);
    if (matchingProfile) {
      state.repositoryForm.tokenProfileId = matchingProfile.id;
    }
  }
}

function saveRepositoryProfilesToState(): void {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem('springBootAnalyzer.repositories.v1', JSON.stringify(state.repositoryProfiles));
  }
}

function createEmptyTokenForm(): TokenFormModel {
  return {
    id: '',
    name: '',
    provider: 'github',
    host: '',
    username: '',
    token: '',
    maskedTokenHint: '',
    errorMessage: ''
  };
}

function createEmptyRepositoryForm(): RepositoryFormModel {
  return {
    id: '',
    name: '',
    repositoryUrl: '',
    branch: '',
    authMode: 'none',
    tokenProfileId: '',
    notes: '',
    errorMessage: ''
  };
}

function tokenFormFromProfile(profile: TokenProfile): TokenFormModel {
  return {
    id: profile.id,
    name: profile.name,
    provider: profile.provider,
    host: profile.host,
    username: profile.username,
    token: '',
    maskedTokenHint: maskToken(profile.token),
    errorMessage: ''
  };
}

function repositoryFormFromProfile(profile: RepositoryProfile): RepositoryFormModel {
  return {
    id: profile.id,
    name: profile.name,
    repositoryUrl: profile.repositoryUrl,
    branch: profile.branch ?? '',
    authMode: profile.authMode,
    tokenProfileId: profile.tokenProfileId ?? '',
    notes: profile.notes ?? '',
    errorMessage: ''
  };
}

function resetResultsViewState(): void {
  state.resultsViewState = createDefaultResultsViewState();
}

function persistAnalysisSession(): void {
  saveAnalysisSession({
    currentTab: state.currentTab,
    analyzeMode: state.analyzeMode,
    selectedSavedRepositoryId: state.selectedSavedRepositoryId,
    oneTimeRepositoryUrl: state.oneTimeRepositoryUrl,
    oneTimeBranch: state.oneTimeBranch,
    oneTimeTokenProfileId: state.oneTimeTokenProfileId,
    analysisMode: state.analysisMode,
    sidebarCollapsed: state.sidebarCollapsed,
    result: state.result,
    resultsViewState: state.resultsViewState
  });
}

function toggleSort(sortState: { key: string; direction: 'asc' | 'desc' }, key: string): void {
  if (sortState.key === key) {
    sortState.direction = sortState.direction === 'asc' ? 'desc' : 'asc';
    return;
  }
  sortState.key = key;
  sortState.direction = 'asc';
}

function renderThemeControl(): HTMLElement {
  const group = element('div', {
    className: 'theme-control',
    attributes: { role: 'group', 'aria-label': 'Color theme' }
  });
  group.appendChild(element('span', { className: 'theme-control-label', text: 'Theme' }));
  const segmented = element('div', { className: 'theme-segmented-control' });
  for (const option of THEME_OPTIONS) {
    const button = element('button', {
      className: state.themePreference === option ? 'theme-button active' : 'theme-button',
      text: capitalize(option),
      attributes: {
        type: 'button',
        'aria-label': `${capitalize(option)} theme`,
        'aria-pressed': String(state.themePreference === option)
      }
    });
    button.addEventListener('click', () => {
      if (state.themePreference === option) {
        return;
      }
      state.themePreference = option;
      saveThemePreference(option);
      applyThemePreference(option);
      render();
    });
    segmented.appendChild(button);
  }
  group.appendChild(segmented);
  return group;
}

function loadThemePreference(): ThemePreference {
  if (typeof window === 'undefined') {
    return 'system';
  }
  const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
  return stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system';
}

function saveThemePreference(preference: ThemePreference): void {
  if (typeof window === 'undefined') {
    return;
  }
  window.localStorage.setItem(THEME_STORAGE_KEY, preference);
}

function applyThemePreference(preference: ThemePreference): void {
  const resolvedTheme = preference === 'system'
    ? (systemThemeMedia?.matches ? 'dark' : 'light')
    : preference;
  document.documentElement.dataset.theme = resolvedTheme;
  document.documentElement.dataset.themePreference = preference;
  document.documentElement.style.colorScheme = resolvedTheme;
}

function handleSystemThemeChange(): void {
  if (state.themePreference !== 'system') {
    return;
  }
  applyThemePreference('system');
  render();
}

function capitalize(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

function requiredElement<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) {
    throw new Error(`Missing required element: ${id}`);
  }
  return node as T;
}

function captureFocusSnapshot(): FocusSnapshot | null {
  const activeElement = document.activeElement;
  if (!(activeElement instanceof HTMLElement) || !activeElement.id) {
    return null;
  }

  if (activeElement instanceof HTMLInputElement || activeElement instanceof HTMLTextAreaElement) {
    return {
      elementId: activeElement.id,
      selectionStart: activeElement.selectionStart,
      selectionEnd: activeElement.selectionEnd
    };
  }

  return {
    elementId: activeElement.id,
    selectionStart: null,
    selectionEnd: null
  };
}

function restoreFocusSnapshot(snapshot: FocusSnapshot | null): void {
  if (!snapshot) {
    return;
  }

  const nextElement = document.getElementById(snapshot.elementId);
  if (!(nextElement instanceof HTMLElement)) {
    return;
  }

  focusWithoutScrolling(nextElement);

  if (
    (nextElement instanceof HTMLInputElement || nextElement instanceof HTMLTextAreaElement) &&
    snapshot.selectionStart !== null &&
    snapshot.selectionEnd !== null
  ) {
    nextElement.setSelectionRange(snapshot.selectionStart, snapshot.selectionEnd);
  }
}

function captureScrollSnapshot(): ScrollSnapshot {
  return {
    scrollX: typeof window !== 'undefined' ? window.scrollX : 0,
    scrollY: typeof window !== 'undefined' ? window.scrollY : 0
  };
}

function restoreScrollSnapshot(snapshot: ScrollSnapshot): void {
  if (typeof window === 'undefined') {
    return;
  }
  window.scrollTo(snapshot.scrollX, snapshot.scrollY);
  window.requestAnimationFrame(() => {
    window.scrollTo(snapshot.scrollX, snapshot.scrollY);
  });
}

function focusWithoutScrolling(element: HTMLElement): void {
  try {
    element.focus({ preventScroll: true });
  } catch {
    element.focus();
  }
}

const ANALYSIS_PROGRESS_STEPS = [
  {
    title: 'Preparing workspace',
    description: 'Preparing analysis workspace...'
  },
  {
    title: 'Cloning repository',
    description: 'Cloning repository into an isolated workspace...'
  },
  {
    title: 'Inspecting project',
    description: 'Inspecting build files and Spring Boot signals...'
  },
  {
    title: 'Analyzing code',
    description: 'Analyzing source, configuration, and HTTP surface...'
  },
  {
    title: 'Building results',
    description: 'Preparing results for display...'
  }
] as const;

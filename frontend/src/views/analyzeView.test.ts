import { beforeEach, describe, expect, it } from 'vitest';

import { renderAnalyzeView, type AnalyzeViewActions, type AnalyzeViewModel } from './analyzeView';
import type { ResultsViewState } from './resultsView';
import type { AnalysisMode } from '../types';

function model(analysisMode: AnalysisMode): AnalyzeViewModel {
  return {
    analyzeMode: 'saved',
    repositoryProfiles: [],
    tokenProfiles: [],
    selectedSavedRepositoryId: '',
    oneTimeRepositoryUrl: '',
    oneTimeBranch: '',
    oneTimeTokenProfileId: '',
    analysisMode,
    statusMessage: '',
    errorMessage: '',
    warningMessage: '',
    isAnalyzing: false,
    analysisProgressIndex: 0,
    result: null,
    resultsViewState: {} as ResultsViewState,
    sidebarCollapsed: false
  };
}

function actions(): AnalyzeViewActions {
  const noop = (..._args: Array<unknown>) => undefined;
  return {
    onSetAnalyzeMode: noop,
    onSelectSavedRepository: noop,
    onAnalyzeSavedRepository: noop,
    onUpdateOneTimeRepositoryUrl: noop,
    onUpdateOneTimeBranch: noop,
    onUpdateOneTimeTokenProfileId: noop,
    onAnalyzeOneTimeRepository: noop,
    onRetryAnalysis: noop,
    onOpenSettings: noop,
    onToggleSidebarCollapsed: noop,
    onAnalysisModeChange: noop,
    onFindingsSeverityChange: noop,
    onFindingsCategoryChange: noop,
    onFindingsRuntimeDetectionChange: noop,
    onFindingsConfidenceChange: noop,
    onFindingsTriageStatusChange: noop,
    onFindingsTextChange: noop,
    onToggleFindingsExpanded: noop,
    onFindingsGroupedChange: noop,
    onSetFindingTriageStatus: noop,
    onSetFindingTriageStatuses: noop,
    onClearFindingsFilters: noop,
    onClearFindingsTriage: noop,
    onConfigurationSearchChange: noop,
    onConfigurationFocusChange: noop,
    onConfigurationProfileChange: noop,
    onConfigurationSourceChange: noop,
    onConfigurationKindChange: noop,
    onConfigurationSensitiveOnlyChange: noop,
    onConfigurationViewChange: noop,
    onConfigurationChangedOnlyChange: noop,
    onToggleConfigurationExpanded: noop,
    onToggleConfigurationRow: noop,
    onSetFindingsSort: noop,
    onSetInboundSort: noop,
    onSetOutboundSort: noop,
    onSetConfiguredUrlsSort: noop,
    onSetConfigurationSort: noop,
    onSetComponentsSort: noop,
    onSetDependenciesSort: noop,
    onComponentTypeChange: noop,
    onComponentTextChange: noop,
    onToggleComponentsExpanded: noop,
    onDependencyTextChange: noop,
    onResolvedDependencyConfigurationChange: noop,
    onResolvedDependencyDirectOnlyChange: noop,
    onRawJsonExpandedChange: noop,
    onToggleHttpInboundExpanded: noop,
    onToggleHttpOutboundExpanded: noop,
    onToggleHttpConfiguredExpanded: noop,
    onToggleHttpActuatorExpanded: noop,
    onOpenFindingCode: noop,
    onOpenPropertySource: noop,
    onOpenComponentSource: noop,
    onOpenHttpSource: noop,
    onCloseFindingCode: noop,
    onSelectFindingCodeOccurrence: noop
  };
}

describe('renderAnalyzeView analysis mode copy', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('describes static-only mode without claiming Gradle execution', () => {
    const view = renderAnalyzeView(model('STATIC_ONLY'), actions());
    document.body.appendChild(view);

    expect(document.body.textContent).toContain('Static only scans source, configuration, and build metadata without running Gradle build logic.');
    expect(document.body.textContent).not.toContain('Static + Gradle model runs Gradle build logic');
  });

  it('describes extended mode and shows the trust warning', () => {
    const view = renderAnalyzeView(model('EXTENDED'), actions());
    document.body.appendChild(view);

    expect(document.body.textContent).toContain('Static + Gradle model runs Gradle build logic in an isolated workspace');
    expect(document.body.textContent).toContain("Only use this with repositories you trust.");
  });
});

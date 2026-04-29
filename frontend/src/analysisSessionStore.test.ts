import { beforeEach, describe, expect, it } from 'vitest';

import { clearAnalysisSession, loadAnalysisSession, saveAnalysisSession, type AnalysisSessionSnapshot } from './analysisSessionStore';

function baseSnapshot(): AnalysisSessionSnapshot {
  return {
    currentTab: 'analyze',
    analyzeMode: 'saved',
    selectedSavedRepositoryId: 'repo-1',
    oneTimeRepositoryUrl: 'https://github.com/example/demo.git',
    oneTimeBranch: 'main',
    oneTimeTokenProfileId: 'token-1',
    analysisMode: 'STATIC_ONLY',
    sidebarCollapsed: false,
    result: {
      analysisId: 'analysis-1',
      workspaceId: 'workspace-1',
      repositoryUrl: 'https://github.com/example/demo.git',
      findings: [{ title: 'Empty catch block', severity: 'WARNING' }]
    },
    resultsViewState: {
      findingsSeverity: 'WARNING',
      findingsCategory: 'ALL',
      findingsRuntimeDetection: 'ALL',
      findingsConfidence: 'ALL',
      findingsText: 'catch',
      findingsExpanded: true,
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
      httpInboundExpanded: true,
      httpOutboundExpanded: false,
      httpConfiguredExpanded: false,
      httpActuatorExpanded: false,
      codeModal: {
        open: true,
        title: 'Should not persist',
        summary: 'Transient modal state',
        ruleType: 'JAVA_EMPTY_CATCH_BLOCK',
        target: 'Demo#run',
        severity: 'WARNING',
        category: 'EXCEPTION_HANDLING',
        confidence: 'HIGH',
        runtimeDetection: 'NOT_NORMALLY_DETECTED',
        isPropertySource: false,
        analysisId: 'analysis-1',
        occurrences: [],
        selectedOccurrenceIndex: 0,
        snippet: null,
        loading: true,
        errorMessage: '',
        returnFocusId: 'button-1'
      }
    }
  };
}

describe('analysisSessionStore', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it('round-trips the latest analysis snapshot and strips transient modal state', () => {
    saveAnalysisSession(baseSnapshot());

    const restored = loadAnalysisSession();

    expect(restored?.result?.analysisId).toBe('analysis-1');
    expect(restored?.resultsViewState?.findingsSeverity).toBe('WARNING');
    expect(restored?.resultsViewState?.httpInboundExpanded).toBe(true);
    expect(restored?.resultsViewState).not.toHaveProperty('codeModal');
  });

  it('clears the stored analysis snapshot', () => {
    saveAnalysisSession(baseSnapshot());
    clearAnalysisSession();

    expect(loadAnalysisSession()).toBeNull();
  });
});

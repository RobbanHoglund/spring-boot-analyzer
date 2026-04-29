import type { AnalysisMode, AnalyzeRepositoryResponse } from './types';
import type { ResultsViewState } from './views/resultsView';

const ANALYSIS_SESSION_STORAGE_KEY = 'spring-boot-analyzer.analysis-session.v1';

export interface AnalysisSessionSnapshot {
  currentTab: 'analyze' | 'settings';
  analyzeMode: 'saved' | 'oneTime';
  selectedSavedRepositoryId: string;
  oneTimeRepositoryUrl: string;
  oneTimeBranch: string;
  oneTimeTokenProfileId: string;
  analysisMode: AnalysisMode;
  sidebarCollapsed: boolean;
  result: AnalyzeRepositoryResponse | null;
  resultsViewState: ResultsViewState;
}

type PersistedResultsViewState = Omit<ResultsViewState, 'codeModal'>;

interface PersistedAnalysisSessionSnapshot extends Omit<AnalysisSessionSnapshot, 'resultsViewState'> {
  resultsViewState: PersistedResultsViewState;
}

export function saveAnalysisSession(snapshot: AnalysisSessionSnapshot): void {
  if (typeof window === 'undefined') {
    return;
  }

  const persisted: PersistedAnalysisSessionSnapshot = {
    ...snapshot,
    resultsViewState: stripTransientResultsViewState(snapshot.resultsViewState)
  };

  try {
    window.sessionStorage.setItem(ANALYSIS_SESSION_STORAGE_KEY, JSON.stringify(persisted));
  } catch {
    // Ignore storage quota and serialization failures; persistence is best-effort.
  }
}

export function loadAnalysisSession(): Partial<PersistedAnalysisSessionSnapshot> | null {
  if (typeof window === 'undefined') {
    return null;
  }

  try {
    const raw = window.sessionStorage.getItem(ANALYSIS_SESSION_STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as Partial<PersistedAnalysisSessionSnapshot> | null;
    if (!parsed || typeof parsed !== 'object') {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

export function clearAnalysisSession(): void {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.sessionStorage.removeItem(ANALYSIS_SESSION_STORAGE_KEY);
  } catch {
    // Ignore storage failures.
  }
}

function stripTransientResultsViewState(resultsViewState: ResultsViewState): PersistedResultsViewState {
  const { codeModal: _codeModal, ...persisted } = resultsViewState;
  return persisted;
}

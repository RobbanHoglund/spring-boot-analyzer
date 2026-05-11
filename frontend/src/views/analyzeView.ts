import { element } from '../dom';
import type { AnalysisMode, AnalyzeRepositoryResponse, RepositoryProfile, TokenProfile } from '../types';
import type { ResultsViewActions, ResultsViewState } from './resultsView';
import { renderResultsView } from './resultsView';

export interface AnalyzeViewModel {
  analyzeMode: 'saved' | 'oneTime';
  repositoryProfiles: RepositoryProfile[];
  tokenProfiles: TokenProfile[];
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
  resultsViewState: ResultsViewState;
  sidebarCollapsed: boolean;
}

export interface AnalyzeViewActions extends ResultsViewActions {
  onSetAnalyzeMode: (mode: 'saved' | 'oneTime') => void;
  onSelectSavedRepository: (repositoryId: string) => void;
  onAnalyzeSavedRepository: () => void;
  onUpdateOneTimeRepositoryUrl: (value: string) => void;
  onUpdateOneTimeBranch: (value: string) => void;
  onUpdateOneTimeTokenProfileId: (value: string) => void;
  onAnalyzeOneTimeRepository: () => void;
  onToggleSidebarCollapsed: () => void;
  onAnalysisModeChange: (value: AnalysisMode) => void;
}

export function renderAnalyzeView(model: AnalyzeViewModel, actions: AnalyzeViewActions): HTMLElement {
  const page = element('div', { className: model.sidebarCollapsed ? 'analyze-layout analyze-layout-collapsed' : 'analyze-layout' });
  const sidebar = element('aside', { className: model.sidebarCollapsed ? 'analyze-sidebar collapsed' : 'analyze-sidebar' });
  const main = element('div', { className: 'analyze-main' });

  sidebar.appendChild(renderRepositoryPanel(model, actions));
  main.appendChild(
    renderResultsView(model.result, model.resultsViewState, actions, {
      statusMessage: model.statusMessage,
      errorMessage: model.errorMessage,
      warningMessage: model.warningMessage,
      isAnalyzing: model.isAnalyzing,
      analysisMode: model.analysisMode
    })
  );

  page.append(sidebar, main);
  const wrapper = element('div', { className: 'analyze-view-shell' }, page);
  if (model.isAnalyzing) {
    wrapper.appendChild(renderAnalysisProgressModal(model));
  }
  return wrapper;
}

function renderAnalysisProgressModal(model: AnalyzeViewModel): HTMLElement {
  const steps = [
    {
      title: 'Preparing workspace',
      detail: 'Create a temporary workspace and validate your request.'
    },
    {
      title: 'Cloning repository',
      detail: 'Clone the selected repository into the isolated workspace.'
    },
    {
      title: 'Inspecting project',
      detail: 'Read build files, detect Spring Boot signals, and inspect structure.'
    },
    {
      title: 'Analyzing code',
      detail: 'Scan source, configuration, components, and HTTP surface.'
    },
    {
      title: 'Building results',
      detail: 'Assemble sections, findings, and summaries for the results view.'
    }
  ];

  const progressList = element('div', { className: 'analysis-progress-steps' });
  for (const [index, step] of steps.entries()) {
    const stateClass =
      index < model.analysisProgressIndex
        ? 'completed'
        : index === model.analysisProgressIndex
          ? 'active'
          : 'pending';
    progressList.appendChild(
      element(
        'div',
        { className: `analysis-progress-step ${stateClass}` },
        element('div', { className: 'analysis-progress-step-marker', text: index < model.analysisProgressIndex ? '✓' : String(index + 1) }),
        element(
          'div',
          { className: 'analysis-progress-step-body' },
          element('div', { className: 'analysis-progress-step-title', text: step.title }),
          element('div', { className: 'analysis-progress-step-detail', text: step.detail })
        )
      )
    );
  }

  return element(
    'div',
    { className: 'analysis-progress-overlay', attributes: { role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Analysis in progress' } },
    element(
      'div',
      { className: 'analysis-progress-modal' },
      element(
        'div',
        { className: 'analysis-progress-header' },
        element('div', { className: 'analysis-progress-eyebrow', text: 'Analysis running' }),
        element('h3', { text: 'Working through the repository' }),
        element('p', {
          className: 'analysis-progress-message',
          text: model.statusMessage || 'Running analysis...'
        })
      ),
      element(
        'div',
        { className: 'analysis-progress-bar' },
        element('div', { className: 'analysis-progress-bar-fill' })
      ),
      progressList,
      element('p', {
        className: 'analysis-progress-note',
        text: 'This window stays open until the analysis finishes. Large repositories or Gradle model analysis can take a little while.'
      })
    )
  );
}

function renderRepositoryPanel(model: AnalyzeViewModel, actions: AnalyzeViewActions): HTMLElement {
  const panel = element('section', { className: 'panel panel-compact sidebar-panel' });
  const header = element('div', { className: 'sidebar-header' });
  header.appendChild(element('h2', { text: 'Repository' }));
  const collapseButton = element('button', {
    className: 'secondary-button sidebar-toggle-button',
    text: model.sidebarCollapsed ? 'Expand' : 'Collapse',
    attributes: { type: 'button' }
  });
  collapseButton.addEventListener('click', actions.onToggleSidebarCollapsed);
  header.appendChild(collapseButton);
  panel.appendChild(header);

  if (model.sidebarCollapsed) {
    panel.appendChild(renderCollapsedSidebar(model, actions));
    return panel;
  }

  panel.appendChild(
    element('p', {
      className: 'helper-text',
      text: 'Run analysis from a saved profile or a one-time repository URL.'
    })
  );
  panel.appendChild(renderAnalysisModeSelector(model, actions));

  const switcher = element('div', { className: 'mode-switcher' });
  for (const mode of [
    { id: 'saved' as const, label: 'Saved repository' },
    { id: 'oneTime' as const, label: 'One-time repository' }
  ]) {
    const button = element('button', {
      className: model.analyzeMode === mode.id ? 'mode-button active' : 'mode-button',
      text: mode.label,
      attributes: { type: 'button' }
    });
    button.addEventListener('click', () => actions.onSetAnalyzeMode(mode.id));
    switcher.appendChild(button);
  }
  panel.appendChild(switcher);

  panel.appendChild(
    model.analyzeMode === 'saved'
      ? renderSavedRepositoryMode(model, actions)
      : renderOneTimeRepositoryMode(model, actions)
  );

  return panel;
}

function renderAnalysisModeSelector(model: AnalyzeViewModel, actions: AnalyzeViewActions): HTMLElement {
  const select = element('select', { className: 'select-input' }) as HTMLSelectElement;
  select.appendChild(new Option('Static only', 'STATIC_ONLY', false, model.analysisMode === 'STATIC_ONLY'));
  select.appendChild(
    new Option(
      'Static + Gradle model',
      'EXTENDED',
      false,
      model.analysisMode === 'EXTENDED'
    )
  );
  select.value = model.analysisMode;
  select.addEventListener('change', () => actions.onAnalysisModeChange(select.value as AnalysisMode));

  const helperText = element('p', {
    className: 'helper-text',
    text: 'Static + Gradle model runs Gradle build logic in an isolated workspace to resolve dependencies. It does not start the target Spring Boot application.'
  });

  const warning =
    model.analysisMode === 'EXTENDED'
      ? element('p', {
          className: 'helper-text warning-text',
          text: "Extended mode executes the repository's Gradle wrapper or a configured Gradle installation. Only use this with repositories you trust."
        })
      : null;

  const children: HTMLElement[] = [element('span', { text: 'Analysis mode' }), select, helperText];
  if (warning) {
    children.push(warning);
  }
  return element('div', { className: 'field' }, ...children);
}

function renderCollapsedSidebar(model: AnalyzeViewModel, actions: AnalyzeViewActions): HTMLElement {
  const selectedRepository = model.repositoryProfiles.find(
    (repository) => repository.id === model.selectedSavedRepositoryId
  );
  const summary = element(
    'div',
    { className: 'collapsed-sidebar-summary' },
    element('div', { className: 'collapsed-sidebar-title', text: selectedRepository?.name ?? 'Repository controls' }),
    element('div', {
      className: 'collapsed-sidebar-meta',
      text:
        model.analyzeMode === 'saved'
          ? selectedRepository?.branch?.trim()
            ? selectedRepository.branch
            : 'default branch'
          : model.oneTimeBranch.trim() || 'default branch'
    })
  );
  const analyzeButton = element('button', {
    className: 'primary-button',
    text: model.isAnalyzing ? 'Analyzing…' : 'Analyze again',
    attributes: { type: 'button' }
  });
  analyzeButton.toggleAttribute('disabled', model.isAnalyzing);
  analyzeButton.addEventListener('click', () => {
    if (model.analyzeMode === 'saved') {
      actions.onAnalyzeSavedRepository();
    } else {
      actions.onAnalyzeOneTimeRepository();
    }
  });
  return element('div', { className: 'stack' }, summary, analyzeButton);
}

function renderSavedRepositoryMode(model: AnalyzeViewModel, actions: AnalyzeViewActions): HTMLElement {
  const wrapper = element('div', { className: 'stack' });
  const select = element('select', {
    className: 'select-input',
    attributes: { id: 'analyze-saved-repository-select' }
  }) as HTMLSelectElement;
  select.appendChild(new Option('Select a saved repository', '', false, model.selectedSavedRepositoryId === ''));

  for (const repository of model.repositoryProfiles) {
    select.appendChild(new Option(repository.name, repository.id, false, repository.id === model.selectedSavedRepositoryId));
  }

  select.value = model.selectedSavedRepositoryId;
  select.addEventListener('change', () => actions.onSelectSavedRepository(select.value));

  wrapper.appendChild(
    element('label', { className: 'field' }, element('span', { text: 'Saved repository' }), select)
  );

  const selectedRepository = model.repositoryProfiles.find(
    (repository) => repository.id === model.selectedSavedRepositoryId
  );

  if (!selectedRepository) {
    wrapper.appendChild(
      element('p', { className: 'muted-text', text: 'Create a repository profile in Settings to reuse it here.' })
    );
    return wrapper;
  }

  const tokenProfile =
    selectedRepository.tokenProfileId
      ? model.tokenProfiles.find((profile) => profile.id === selectedRepository.tokenProfileId)
      : undefined;

  wrapper.appendChild(
    element(
      'div',
      { className: 'detail-card detail-card-compact' },
      detailRow('Repository URL', selectedRepository.repositoryUrl),
      detailRow('Branch', selectedRepository.branch?.trim() ? selectedRepository.branch : 'default branch'),
      detailRow('Auth mode', authModeLabel(selectedRepository.authMode)),
      detailRow('Default token profile', tokenProfile?.name ?? 'None'),
      selectedRepository.notes ? detailRow('Notes', selectedRepository.notes) : null
    )
  );

  if (selectedRepository.authMode === 'ssh') {
    wrapper.appendChild(
      element('p', { className: 'helper-text', text: 'SSH repositories use the backend server SSH configuration.' })
    );
  }

  const analyzeButton = element('button', {
    className: 'primary-button',
    text: model.isAnalyzing ? 'Cloning and analyzing repository...' : 'Analyze selected repository',
    attributes: { type: 'button' }
  });
  analyzeButton.toggleAttribute('disabled', model.isAnalyzing);
  analyzeButton.addEventListener('click', actions.onAnalyzeSavedRepository);
  wrapper.appendChild(analyzeButton);
  return wrapper;
}

function renderOneTimeRepositoryMode(model: AnalyzeViewModel, actions: AnalyzeViewActions): HTMLElement {
  const wrapper = element('div', { className: 'stack' });

  const repositoryUrlInput = element('input', {
    className: 'text-input',
    attributes: {
      id: 'analyze-one-time-repository-url',
      type: 'text',
      autocomplete: 'off',
      placeholder: 'https://github.com/example/app.git'
    }
  }) as HTMLInputElement;
  repositoryUrlInput.value = model.oneTimeRepositoryUrl;
  repositoryUrlInput.addEventListener('input', () => actions.onUpdateOneTimeRepositoryUrl(repositoryUrlInput.value));

  const branchInput = element('input', {
    className: 'text-input',
    attributes: {
      id: 'analyze-one-time-branch',
      type: 'text',
      autocomplete: 'off',
      placeholder: 'main'
    }
  }) as HTMLInputElement;
  branchInput.value = model.oneTimeBranch;
  branchInput.addEventListener('input', () => actions.onUpdateOneTimeBranch(branchInput.value));

  const tokenSelect = element('select', {
    className: 'select-input',
    attributes: { id: 'analyze-one-time-token-profile' }
  }) as HTMLSelectElement;
  tokenSelect.appendChild(new Option('No token profile', '', false, model.oneTimeTokenProfileId === ''));
  for (const profile of model.tokenProfiles) {
    tokenSelect.appendChild(
      new Option(`${profile.name} (${profile.host})`, profile.id, false, profile.id === model.oneTimeTokenProfileId)
    );
  }
  tokenSelect.value = model.oneTimeTokenProfileId;
  tokenSelect.addEventListener('change', () => actions.onUpdateOneTimeTokenProfileId(tokenSelect.value));

  wrapper.append(
    element(
      'label',
      { className: 'field' },
      element('span', { text: 'Repository URL' }),
      repositoryUrlInput,
      element('div', { className: 'helper-list compact-helper-list' }, 'https://github.com/example/app.git'),
      element('div', { className: 'helper-list compact-helper-list' }, 'git@github.com:example/app.git'),
      element('div', { className: 'helper-list compact-helper-list' }, 'ssh://git@github.com/example/app.git')
    ),
    element('label', { className: 'field' }, element('span', { text: 'Branch optional' }), branchInput),
    element('label', { className: 'field' }, element('span', { text: 'Token profile optional' }), tokenSelect),
    element('p', {
      className: 'helper-text',
      text: 'SSH repositories use the backend server SSH configuration. Browser-stored HTTPS tokens are not sent for SSH URLs.'
    })
  );

  const analyzeButton = element('button', {
    className: 'primary-button',
    text: model.isAnalyzing ? 'Cloning and analyzing repository...' : 'Clone and analyze',
    attributes: { type: 'button' }
  });
  analyzeButton.toggleAttribute('disabled', model.isAnalyzing);
  analyzeButton.addEventListener('click', actions.onAnalyzeOneTimeRepository);
  wrapper.appendChild(analyzeButton);
  return wrapper;
}

function detailRow(label: string, value: string): HTMLElement {
  return element(
    'div',
    { className: 'detail-row' },
    element('span', { className: 'detail-label', text: label }),
    element('span', { className: 'detail-value', text: value })
  );
}

function authModeLabel(authMode: RepositoryProfile['authMode']): string {
  if (authMode === 'token') {
    return 'HTTPS token';
  }
  if (authMode === 'ssh') {
    return 'SSH server config';
  }
  return 'None/public';
}

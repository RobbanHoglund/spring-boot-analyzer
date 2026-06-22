import { element } from '../dom';
import { findMatchingTokenProfile, maskToken } from '../tokenStore';
import type { RepositoryProfile, RuleInfo, TokenProfile, TokenProvider } from '../types';

export type RuleSeverityFilter = 'ALL' | 'ERROR' | 'WARNING' | 'INFO';
export type RuleStatusFilter = 'ALL' | 'ENABLED' | 'DISABLED';
export type RuleCategoryExpansionState = 'review' | 'all' | 'none';

export interface RuleManagementViewState {
  searchText: string;
  severity: RuleSeverityFilter;
  status: RuleStatusFilter;
  categoryExpansion: RuleCategoryExpansionState;
}

export interface TokenFormModel {
  id: string;
  name: string;
  provider: TokenProvider;
  host: string;
  username: string;
  token: string;
  maskedTokenHint: string;
  errorMessage: string;
}

export interface RepositoryFormModel {
  id: string;
  name: string;
  repositoryUrl: string;
  branch: string;
  authMode: RepositoryProfile['authMode'];
  tokenProfileId: string;
  notes: string;
  errorMessage: string;
}

export interface SettingsViewModel {
  repositoryProfiles: RepositoryProfile[];
  tokenProfiles: TokenProfile[];
  repositoryForm: RepositoryFormModel;
  tokenForm: TokenFormModel;
  ruleSettings: RuleInfo[] | null;
  ruleSettingsLoading: boolean;
  ruleSettingsError: string;
  ruleManagement: RuleManagementViewState;
}

export interface SettingsViewActions {
  onRepositoryFormChange: (field: keyof RepositoryFormModel, value: string) => void;
  onSaveRepository: () => void;
  onDeleteSelectedRepository: () => void;
  onClearRepositoryForm: () => void;
  onEditRepository: (repositoryId: string) => void;
  onDeleteRepository: (repositoryId: string) => void;
  onAnalyzeRepository: (repositoryId: string) => void;
  onTokenFormChange: (field: keyof TokenFormModel, value: string) => void;
  onSaveToken: () => void;
  onDeleteSelectedToken: () => void;
  onClearTokenForm: () => void;
  onEditToken: (tokenProfileId: string) => void;
  onDeleteToken: (tokenProfileId: string) => void;
  onToggleRule: (ruleId: string, enabled: boolean) => void;
  onEnableAllRules: () => void;
  onSetAllBySeverity: (severity: string, enabled: boolean) => void;
  onRuleSearchChange: (value: string) => void;
  onRuleSeverityFilterChange: (value: RuleSeverityFilter) => void;
  onRuleStatusFilterChange: (value: RuleStatusFilter) => void;
  onRuleCategoryExpansionChange: (value: RuleCategoryExpansionState) => void;
  onClearRuleFilters: () => void;
}

export function renderSettingsView(
  model: SettingsViewModel,
  actions: SettingsViewActions
): HTMLElement {
  const page = element('div', { className: 'settings-grid' });
  page.appendChild(renderRepositorySection(model, actions));
  page.appendChild(renderTokenSection(model, actions));
  page.appendChild(renderRulesSection(model, actions));
  return page;
}

function renderRepositorySection(
  model: SettingsViewModel,
  actions: SettingsViewActions
): HTMLElement {
  const panel = element('section', { className: 'panel panel-compact' });
  panel.appendChild(element('h2', { text: 'Saved repositories' }));
  panel.appendChild(
    element(
      'p',
      {
        className: 'helper-text',
        text: 'Save repository profiles for faster analysis. SSH repositories use backend server SSH configuration.'
      }
    )
  );

  const repositoryNameInput = textInput('settings-repository-name', model.repositoryForm.name, 'Repository name', (value) =>
    actions.onRepositoryFormChange('name', value)
  );
  const repositoryUrlInput = textInput('settings-repository-url', model.repositoryForm.repositoryUrl, 'https://github.com/example/app.git', (value) =>
    actions.onRepositoryFormChange('repositoryUrl', value)
  );
  const branchInput = textInput('settings-repository-branch', model.repositoryForm.branch, 'main', (value) =>
    actions.onRepositoryFormChange('branch', value)
  );
  const notesInput = textareaInput('settings-repository-notes', model.repositoryForm.notes, 'Optional notes', (value) =>
    actions.onRepositoryFormChange('notes', value)
  );

  const authSelect = element('select', {
    className: 'select-input',
    attributes: { id: 'settings-repository-auth-mode' }
  }) as HTMLSelectElement;
  for (const option of [
    ['none', 'None/public'],
    ['token', 'HTTPS token'],
    ['ssh', 'SSH server config']
  ]) {
    authSelect.appendChild(
      new Option(option[1], option[0], false, option[0] === model.repositoryForm.authMode)
    );
  }
  authSelect.value = model.repositoryForm.authMode;
  authSelect.addEventListener('change', () => actions.onRepositoryFormChange('authMode', authSelect.value));

  const tokenSelect = element('select', {
    className: 'select-input',
    attributes: { id: 'settings-repository-token-profile' }
  }) as HTMLSelectElement;
  tokenSelect.appendChild(new Option('Select token profile', '', false, model.repositoryForm.tokenProfileId === ''));
  for (const profile of model.tokenProfiles) {
    tokenSelect.appendChild(
      new Option(`${profile.name} (${profile.host})`, profile.id, false, profile.id === model.repositoryForm.tokenProfileId)
    );
  }
  tokenSelect.value = model.repositoryForm.tokenProfileId;
  tokenSelect.disabled = model.repositoryForm.authMode !== 'token';
  tokenSelect.addEventListener('change', () => actions.onRepositoryFormChange('tokenProfileId', tokenSelect.value));

  const matchingToken =
    model.repositoryForm.repositoryUrl.trim() && model.repositoryForm.authMode !== 'ssh'
      ? findMatchingTokenProfile(model.repositoryForm.repositoryUrl, model.tokenProfiles)
      : null;

  panel.append(
    field('Repository name', repositoryNameInput),
    field('Repository URL', repositoryUrlInput),
    field('Branch optional', branchInput),
    field('Auth mode', authSelect),
    field('Default token profile', tokenSelect),
    field('Notes optional', notesInput)
  );

  if (matchingToken && model.repositoryForm.authMode !== 'token') {
    panel.appendChild(
      element(
        'p',
        {
          className: 'helper-text',
          text: `Matching token profile available: ${matchingToken.name}. Switch auth mode to HTTPS token to use it.`
        }
      )
    );
  }

  if (model.repositoryForm.authMode === 'ssh') {
    panel.appendChild(
      element('p', { className: 'helper-text', text: 'SSH repositories do not use browser-stored token profiles.' })
    );
  }

  if (model.repositoryForm.errorMessage) {
    panel.appendChild(element('p', { className: 'error-text', text: model.repositoryForm.errorMessage }));
  }

  const actionsRow = element('div', { className: 'actions' });
  const saveButton = element('button', {
    className: 'primary-button',
    text: 'Save repository',
    attributes: { type: 'button' }
  });
  saveButton.addEventListener('click', actions.onSaveRepository);

  const deleteButton = element('button', {
    className: 'danger-button',
    text: 'Delete selected repository',
    attributes: { type: 'button' }
  });
  deleteButton.addEventListener('click', actions.onDeleteSelectedRepository);

  const clearButton = element('button', {
    className: 'secondary-button',
    text: 'Clear repository form',
    attributes: { type: 'button' }
  });
  clearButton.addEventListener('click', actions.onClearRepositoryForm);

  actionsRow.append(saveButton, deleteButton, clearButton);
  panel.appendChild(actionsRow);

  panel.appendChild(element('h3', { text: 'Saved repositories' }));

  const list = element('div', { className: 'compact-card-list dense-list' });
  if (model.repositoryProfiles.length === 0) {
    list.appendChild(element('p', { className: 'muted-text', text: 'No saved repositories yet.' }));
  } else {
    for (const repository of model.repositoryProfiles) {
      const tokenName =
        repository.tokenProfileId
          ? model.tokenProfiles.find((profile) => profile.id === repository.tokenProfileId)?.name ?? 'Missing token profile'
          : 'None';

      const card = element(
        'article',
        { className: 'compact-card' },
        element('div', { className: 'compact-card-title', text: repository.name }),
        element('div', { className: 'compact-card-meta', text: repository.repositoryUrl }),
        element(
          'div',
          {
            className: 'compact-card-meta',
            text: `Branch: ${repository.branch?.trim() ? repository.branch : 'default branch'}`
          }
        ),
        element('div', { className: 'compact-card-meta', text: `Auth mode: ${authModeLabel(repository.authMode)}` }),
        element('div', { className: 'compact-card-meta', text: `Default token: ${tokenName}` }),
        repository.notes ? element('div', { className: 'compact-card-meta', text: repository.notes }) : null
      );

      const cardActions = element('div', { className: 'actions compact-actions' });
      const analyzeButton = element('button', {
        className: 'primary-button',
        text: 'Analyze',
        attributes: { type: 'button' }
      });
      analyzeButton.addEventListener('click', () => actions.onAnalyzeRepository(repository.id));

      const editButton = element('button', {
        className: 'secondary-button',
        text: 'Edit',
        attributes: { type: 'button' }
      });
      editButton.addEventListener('click', () => actions.onEditRepository(repository.id));

      const deleteAction = element('button', {
        className: 'danger-button',
        text: 'Delete',
        attributes: { type: 'button' }
      });
      deleteAction.addEventListener('click', () => actions.onDeleteRepository(repository.id));

      cardActions.append(analyzeButton, editButton, deleteAction);
      card.appendChild(cardActions);
      list.appendChild(card);
    }
  }

  panel.appendChild(list);
  return panel;
}

function renderTokenSection(
  model: SettingsViewModel,
  actions: SettingsViewActions
): HTMLElement {
  const panel = element('section', { className: 'panel panel-compact' });
  panel.appendChild(element('h2', { text: 'HTTPS token profiles' }));
  panel.appendChild(
    element(
      'p',
      {
        className: 'helper-text',
        text: 'Token profiles are stored locally in this browser. Use read-only repository scopes and remove profiles you no longer need.'
      }
    )
  );

  const providerSelect = element('select', {
    className: 'select-input',
    attributes: { id: 'settings-token-provider' }
  }) as HTMLSelectElement;
  for (const provider of [
    ['github', 'GitHub'],
    ['gitlab', 'GitLab'],
    ['bitbucket', 'Bitbucket'],
    ['other', 'Other']
  ]) {
    providerSelect.appendChild(
      new Option(provider[1], provider[0], false, provider[0] === model.tokenForm.provider)
    );
  }
  providerSelect.value = model.tokenForm.provider;
  providerSelect.addEventListener('change', () => actions.onTokenFormChange('provider', providerSelect.value));

  const tokenInput = element('input', {
    className: 'text-input',
    attributes: {
      id: 'settings-token-value',
      type: 'password',
      autocomplete: 'off',
      placeholder: model.tokenForm.id ? 'Leave blank to keep the existing token' : 'ghp_xxx'
    }
  }) as HTMLInputElement;
  tokenInput.value = model.tokenForm.token;
  tokenInput.addEventListener('input', () => actions.onTokenFormChange('token', tokenInput.value));

  panel.append(
    field('Profile name', textInput('settings-token-name', model.tokenForm.name, 'GitHub personal token', (value) => actions.onTokenFormChange('name', value))),
    field('Provider', providerSelect),
    field('Host', textInput('settings-token-host', model.tokenForm.host, 'github.com', (value) => actions.onTokenFormChange('host', value))),
    field('Username', textInput('settings-token-username', model.tokenForm.username, 'my-github-username', (value) => actions.onTokenFormChange('username', value))),
    field('Token/PAT', tokenInput)
  );

  if (model.tokenForm.maskedTokenHint) {
    panel.appendChild(
      element(
        'p',
        {
          className: 'helper-text',
          text: `Saved token ending in ${model.tokenForm.maskedTokenHint.slice(-4)}`
        }
      )
    );
  }

  if (model.tokenForm.errorMessage) {
    panel.appendChild(element('p', { className: 'error-text', text: model.tokenForm.errorMessage }));
  }

  const actionsRow = element('div', { className: 'actions' });
  const saveButton = element('button', {
    className: 'primary-button',
    text: 'Save token',
    attributes: { type: 'button' }
  });
  saveButton.addEventListener('click', actions.onSaveToken);

  const deleteButton = element('button', {
    className: 'danger-button',
    text: 'Delete selected token',
    attributes: { type: 'button' }
  });
  deleteButton.addEventListener('click', actions.onDeleteSelectedToken);

  const clearButton = element('button', {
    className: 'secondary-button',
    text: 'Clear token form',
    attributes: { type: 'button' }
  });
  clearButton.addEventListener('click', actions.onClearTokenForm);

  actionsRow.append(saveButton, deleteButton, clearButton);
  panel.appendChild(actionsRow);

  panel.appendChild(element('h3', { text: 'Saved token profiles' }));
  const list = element('div', { className: 'compact-card-list dense-list' });

  if (model.tokenProfiles.length === 0) {
    list.appendChild(element('p', { className: 'muted-text', text: 'No token profiles saved yet.' }));
  } else {
    for (const profile of model.tokenProfiles) {
      const card = element(
        'article',
        { className: 'compact-card' },
        element('div', { className: 'compact-card-title', text: profile.name }),
        element('div', { className: 'compact-card-meta', text: providerLabel(profile.provider) }),
        element('div', { className: 'compact-card-meta', text: `${profile.host} | ${profile.username}` }),
        element('div', { className: 'compact-card-meta', text: maskToken(profile.token) }),
        element('div', { className: 'compact-card-meta', text: `Created: ${formatDate(profile.createdAt)}` }),
        element('div', { className: 'compact-card-meta', text: `Updated: ${formatDate(profile.updatedAt)}` })
      );

      const cardActions = element('div', { className: 'actions compact-actions' });
      const editButton = element('button', {
        className: 'secondary-button',
        text: 'Edit',
        attributes: { type: 'button' }
      });
      editButton.addEventListener('click', () => actions.onEditToken(profile.id));

      const deleteAction = element('button', {
        className: 'danger-button',
        text: 'Delete',
        attributes: { type: 'button' }
      });
      deleteAction.addEventListener('click', () => actions.onDeleteToken(profile.id));

      cardActions.append(editButton, deleteAction);
      card.appendChild(cardActions);
      list.appendChild(card);
    }
  }

  panel.appendChild(list);
  return panel;
}

function field(label: string, control: HTMLElement): HTMLElement {
  return element('label', { className: 'field' }, element('span', { text: label }), control);
}

function textInput(
  id: string,
  value: string,
  placeholder: string,
  onChange: (value: string) => void
): HTMLInputElement {
  const input = element('input', {
    className: 'text-input',
    attributes: {
      id,
      type: 'text',
      autocomplete: 'off',
      placeholder
    }
  }) as HTMLInputElement;
  input.value = value;
  input.addEventListener('input', () => onChange(input.value));
  return input;
}

function textareaInput(
  id: string,
  value: string,
  placeholder: string,
  onChange: (value: string) => void
): HTMLTextAreaElement {
  const input = element('textarea', {
    className: 'text-area',
    attributes: {
      id,
      rows: '3',
      placeholder
    }
  }) as HTMLTextAreaElement;
  input.value = value;
  input.addEventListener('input', () => onChange(input.value));
  return input;
}

function providerLabel(provider: TokenProfile['provider']): string {
  if (provider === 'github') {
    return 'GitHub';
  }
  if (provider === 'gitlab') {
    return 'GitLab';
  }
  if (provider === 'bitbucket') {
    return 'Bitbucket';
  }
  return 'Other';
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

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
}

function renderRulesSection(model: SettingsViewModel, actions: SettingsViewActions): HTMLElement {
  const panel = element('section', { className: 'panel panel-compact rules-section' });
  panel.appendChild(element('h2', { text: 'Rule management' }));
  const helperText = element('p', { className: 'helper-text' });
  helperText.appendChild(
    document.createTextNode(
      'Disable rules you have reviewed and accepted for this installation. Disabled rules are saved in ~/.spring-boot-analyzer/rule-config.json and take effect for all future analyses. '
    )
  );
  const rulesLink = document.createElement('a');
  rulesLink.href =
    'https://github.com/RobbanHoglund/spring-boot-analyzer/blob/main/docs/RULES.md';
  rulesLink.target = '_blank';
  rulesLink.rel = 'noopener noreferrer';
  rulesLink.textContent = 'Full rule catalog →';
  helperText.appendChild(rulesLink);
  panel.appendChild(helperText);

  if (model.ruleSettingsLoading) {
    panel.appendChild(element('p', { className: 'muted-text', text: 'Loading rules…' }));
    return panel;
  }

  if (model.ruleSettingsError) {
    panel.appendChild(element('p', { className: 'error-text', text: model.ruleSettingsError }));
    return panel;
  }

  if (!model.ruleSettings || model.ruleSettings.length === 0) {
    panel.appendChild(element('p', { className: 'muted-text', text: 'No rules available.' }));
    return panel;
  }

  const disabledCount = model.ruleSettings.filter((r) => !r.enabled).length;
  const ruleManagement = normalizeRuleManagementState(model.ruleManagement);
  const filteredRules = filterRules(model.ruleSettings, ruleManagement);
  const activeFilterCount = countActiveRuleFilters(ruleManagement);
  const activeFilters = activeFilterCount > 0;

  panel.appendChild(
    element(
      'div',
      { className: 'rule-management-overview' },
      element(
        'div',
        { className: 'rule-count-summary' },
        element('strong', { text: `${filteredRules.length} of ${model.ruleSettings.length} rules` }),
        element('span', { text: ` | ${disabledCount} disabled` })
      ),
      element('div', {
        className: 'rule-count-meta',
        text: activeFilters
          ? `${activeFilterCount} active filter${activeFilterCount === 1 ? '' : 's'}`
          : 'Showing the full rule catalog'
      })
    )
  );

  const catalogDetails = document.createElement('details');
  catalogDetails.className = 'rule-catalog-details';
  if (activeFilters) {
    catalogDetails.open = true;
  }
  catalogDetails.appendChild(
    element(
      'summary',
      { className: 'rule-catalog-summary' },
      element('span', { className: 'rule-catalog-summary-title', text: 'Configure rule catalog' }),
      element('span', {
        className: 'rule-catalog-summary-meta',
        text: activeFilters
          ? 'Filters are active'
          : disabledCount > 0
            ? `${disabledCount} disabled`
            : 'Collapsed by default'
      })
    )
  );
  const catalogBody = element('div', { className: 'rule-catalog-body' });

  const filterRow = element('div', { className: 'rule-filter-row' });
  filterRow.append(
    field(
      'Search rules',
      textInput('settings-rule-search', ruleManagement.searchText, 'Rule title or id', actions.onRuleSearchChange)
    ),
    field(
      'Severity',
      selectControl(
        'settings-rule-severity',
        [
          ['ALL', 'All severities'],
          ['ERROR', 'Errors'],
          ['WARNING', 'Warnings'],
          ['INFO', 'Info']
        ],
        ruleManagement.severity,
        (value) => actions.onRuleSeverityFilterChange(value as RuleSeverityFilter)
      )
    ),
    field(
      'Status',
      selectControl(
        'settings-rule-status',
        [
          ['ALL', 'Enabled and disabled'],
          ['ENABLED', 'Enabled only'],
          ['DISABLED', 'Disabled only']
        ],
        ruleManagement.status,
        (value) => actions.onRuleStatusFilterChange(value as RuleStatusFilter)
      )
    )
  );
  catalogBody.appendChild(filterRow);

  const viewActions = element('div', { className: 'actions rule-view-actions' });
  const expandAllButton = element('button', {
    className: 'secondary-button utility-button',
    text: 'Expand all categories',
    attributes: { type: 'button' }
  });
  expandAllButton.addEventListener('click', () => actions.onRuleCategoryExpansionChange('all'));
  const collapseAllButton = element('button', {
    className: 'secondary-button utility-button',
    text: 'Collapse all categories',
    attributes: { type: 'button' }
  });
  collapseAllButton.addEventListener('click', () => actions.onRuleCategoryExpansionChange('none'));
  const reviewButton = element('button', {
    className: 'secondary-button utility-button',
    text: 'Review changed categories',
    attributes: { type: 'button' }
  });
  reviewButton.addEventListener('click', () => actions.onRuleCategoryExpansionChange('review'));
  viewActions.append(expandAllButton, collapseAllButton, reviewButton);

  if (activeFilters) {
    const clearFiltersButton = element('button', {
      className: 'secondary-button utility-button rule-filter-clear',
      text: 'Clear filters',
      attributes: { type: 'button' }
    });
    clearFiltersButton.addEventListener('click', actions.onClearRuleFilters);
    viewActions.appendChild(clearFiltersButton);
  }
  catalogBody.appendChild(viewActions);

  // Severity bulk-action bar
  const severityBar = element('div', { className: 'rule-severity-bar' });
  severityBar.appendChild(element('span', { className: 'rule-severity-bar-label', text: 'By severity:' }));
  for (const severity of ['ERROR', 'WARNING', 'INFO'] as const) {
    const rulesForSeverity = model.ruleSettings.filter((r) => r.severity === severity);
    const allDisabled = rulesForSeverity.every((r) => !r.enabled);
    const btn = element('button', {
      className: `severity-toggle-button severity-toggle-button--${severity.toLowerCase()}${allDisabled ? ' severity-toggle-button--off' : ''}`,
      text: allDisabled
        ? `Enable all ${severity} (${rulesForSeverity.length})`
        : `Disable all ${severity} (${rulesForSeverity.length})`,
      attributes: { type: 'button' }
    });
    btn.addEventListener('click', () => actions.onSetAllBySeverity(severity, allDisabled));
    severityBar.appendChild(btn);
  }
  if (disabledCount > 0) {
    const enableAll = element('button', {
      className: 'secondary-button rule-enable-all-button',
      text: `Re-enable all (${disabledCount} disabled)`,
      attributes: { type: 'button' }
    });
    enableAll.addEventListener('click', actions.onEnableAllRules);
    severityBar.appendChild(enableAll);
  }
  catalogBody.appendChild(severityBar);

  if (filteredRules.length === 0) {
    catalogBody.appendChild(element('p', { className: 'muted-text rule-empty-state', text: 'No rules match the current filters.' }));
    catalogDetails.appendChild(catalogBody);
    panel.appendChild(catalogDetails);
    return panel;
  }

  // Group rules by category
  const byCategory = new Map<string, RuleInfo[]>();
  for (const rule of filteredRules) {
    const list = byCategory.get(rule.category) ?? [];
    list.push(rule);
    byCategory.set(rule.category, list);
  }

  const sortedCategories = [...byCategory.keys()].sort((left, right) => {
    const leftDisabled = byCategory.get(left)!.filter((rule) => !rule.enabled).length;
    const rightDisabled = byCategory.get(right)!.filter((rule) => !rule.enabled).length;
    if (leftDisabled !== rightDisabled) {
      return rightDisabled - leftDisabled;
    }
    return categoryDisplayName(left).localeCompare(categoryDisplayName(right));
  });

  for (const category of sortedCategories) {
    const rules = sortRulesForDisplay(byCategory.get(category)!);
    const disabledInCategory = rules.filter((r) => !r.enabled).length;
    const totalInCategory = model.ruleSettings.filter((rule) => rule.category === category).length;

    const details = document.createElement('details');
    details.className = 'rule-category-details';
    if (
      ruleManagement.categoryExpansion === 'all'
      || (ruleManagement.categoryExpansion === 'review' && (disabledInCategory > 0 || activeFilters))
    ) {
      details.open = true;
    }

    const summary = document.createElement('summary');
    summary.className = 'rule-category-summary';
    const categoryLabel = element('span', { className: 'rule-category-name', text: categoryDisplayName(category) });
    const badge = element('span', {
      className: 'rule-category-badge',
      text: rules.length === totalInCategory ? String(rules.length) : `${rules.length}/${totalInCategory}`
    });
    summary.appendChild(categoryLabel);
    summary.appendChild(badge);
    if (disabledInCategory > 0) {
      summary.appendChild(
        element('span', {
          className: 'rule-disabled-badge',
          text: `${disabledInCategory} disabled`
        })
      );
    }
    details.appendChild(summary);

    const ruleList = element('div', { className: 'rule-list' });
    for (const rule of rules) {
      const row = element('div', { className: rule.enabled ? 'rule-row' : 'rule-row rule-row--disabled' });

      const toggle = document.createElement('input');
      toggle.type = 'checkbox';
      toggle.className = 'rule-toggle';
      toggle.id = `rule-toggle-${rule.ruleId}`;
      toggle.checked = rule.enabled;
      toggle.addEventListener('change', () => actions.onToggleRule(rule.ruleId, toggle.checked));

      const labelEl = document.createElement('label');
      labelEl.htmlFor = `rule-toggle-${rule.ruleId}`;
      labelEl.className = 'rule-label';

      const titleRow = element('div', { className: 'rule-title-row' });
      titleRow.appendChild(
        element('span', { className: 'rule-title', text: rule.title })
      );
      titleRow.appendChild(
        element('span', {
          className: `rule-severity rule-severity--${rule.severity.toLowerCase()}`,
          text: rule.severity
        })
      );

      const idRow = element('div', { className: 'rule-id', text: rule.ruleId });

      labelEl.appendChild(titleRow);
      labelEl.appendChild(idRow);

      row.appendChild(toggle);
      row.appendChild(labelEl);
      ruleList.appendChild(row);
    }

    details.appendChild(ruleList);
    catalogBody.appendChild(details);
  }

  catalogDetails.appendChild(catalogBody);
  panel.appendChild(catalogDetails);
  return panel;
}

function categoryDisplayName(category: string): string {
  const names: Record<string, string> = {
    SECURITY: 'Security',
    CONFIGURATION: 'Configuration',
    PROFILE_DRIFT: 'Profile drift',
    PERSISTENCE: 'Persistence',
    TRANSACTION: 'Transaction',
    SCHEDULING: 'Scheduling',
    HTTP: 'HTTP clients',
    EXCEPTION_HANDLING: 'Exception handling',
    VALIDATION: 'Validation',
    MAINTAINABILITY: 'Maintainability',
    OBSERVABILITY: 'Observability',
    CACHING: 'Caching',
    TESTING: 'Testing practice',
    CONDITIONAL_BEAN: 'Conditional beans',
    STARTUP: 'Startup',
    ACTUATOR: 'Actuator',
    API_SURFACE: 'API surface',
    DEPENDENCY: 'Dependency compatibility'
  };
  return names[category] ?? category;
}

function selectControl(
  id: string,
  options: Array<[string, string]>,
  value: string,
  onChange: (value: string) => void
): HTMLSelectElement {
  const select = element('select', {
    className: 'select-input',
    attributes: { id }
  }) as HTMLSelectElement;
  for (const [optionValue, label] of options) {
    select.appendChild(new Option(label, optionValue, false, optionValue === value));
  }
  select.value = value;
  select.addEventListener('change', () => onChange(select.value));
  return select;
}

function normalizeRuleManagementState(state: RuleManagementViewState): RuleManagementViewState {
  return {
    searchText: state.searchText ?? '',
    severity: ['ALL', 'ERROR', 'WARNING', 'INFO'].includes(state.severity) ? state.severity : 'ALL',
    status: ['ALL', 'ENABLED', 'DISABLED'].includes(state.status) ? state.status : 'ALL',
    categoryExpansion: ['review', 'all', 'none'].includes(state.categoryExpansion) ? state.categoryExpansion : 'review'
  };
}

function countActiveRuleFilters(state: RuleManagementViewState): number {
  return [
    state.searchText.trim() ? 1 : 0,
    state.severity !== 'ALL' ? 1 : 0,
    state.status !== 'ALL' ? 1 : 0
  ].reduce((sum, value) => sum + value, 0);
}

function filterRules(rules: RuleInfo[], state: RuleManagementViewState): RuleInfo[] {
  const needle = state.searchText.trim().toLowerCase();
  return rules.filter((rule) => {
    const matchesText = !needle
      || [
        rule.ruleId,
        rule.title,
        rule.category,
        rule.severity,
        rule.runtimeDetection
      ]
        .join(' ')
        .toLowerCase()
        .includes(needle);
    const matchesSeverity = state.severity === 'ALL' || rule.severity === state.severity;
    const matchesStatus =
      state.status === 'ALL'
      || (state.status === 'ENABLED' && rule.enabled)
      || (state.status === 'DISABLED' && !rule.enabled);
    return matchesText && matchesSeverity && matchesStatus;
  });
}

function sortRulesForDisplay(rules: RuleInfo[]): RuleInfo[] {
  const severityRank: Record<string, number> = { ERROR: 0, WARNING: 1, INFO: 2 };
  return [...rules].sort((left, right) => {
    if (left.enabled !== right.enabled) {
      return left.enabled ? 1 : -1;
    }
    const severity = (severityRank[left.severity] ?? 99) - (severityRank[right.severity] ?? 99);
    if (severity !== 0) {
      return severity;
    }
    return left.title.localeCompare(right.title);
  });
}

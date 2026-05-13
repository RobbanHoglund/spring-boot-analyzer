import { element } from '../dom';
import { findMatchingTokenProfile, maskToken } from '../tokenStore';
import type { RepositoryProfile, RuleInfo, TokenProfile, TokenProvider } from '../types';

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
        text: 'Tokens are stored in browser localStorage for local development convenience. This is not recommended for production secret storage. Do not save highly privileged tokens here.'
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
          text: `Saved token: ${model.tokenForm.maskedTokenHint}`
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
  panel.appendChild(severityBar);

  // Group rules by category
  const byCategory = new Map<string, RuleInfo[]>();
  for (const rule of model.ruleSettings) {
    const list = byCategory.get(rule.category) ?? [];
    list.push(rule);
    byCategory.set(rule.category, list);
  }

  const sortedCategories = [...byCategory.keys()].sort();

  for (const category of sortedCategories) {
    const rules = byCategory.get(category)!;
    const disabledInCategory = rules.filter((r) => !r.enabled).length;

    const details = document.createElement('details');
    details.className = 'rule-category-details';
    if (disabledInCategory > 0) {
      details.open = true;
    }

    const summary = document.createElement('summary');
    summary.className = 'rule-category-summary';
    const categoryLabel = element('span', { text: categoryDisplayName(category) });
    const badge = element('span', {
      className: 'rule-category-badge',
      text: String(rules.length)
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
    panel.appendChild(details);
  }

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

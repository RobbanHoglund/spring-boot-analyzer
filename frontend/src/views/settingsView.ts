import { element } from '../dom';
import { findMatchingTokenProfile, maskToken } from '../tokenStore';
import type { RepositoryProfile, TokenProfile, TokenProvider } from '../types';

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
}

export function renderSettingsView(
  model: SettingsViewModel,
  actions: SettingsViewActions
): HTMLElement {
  const page = element('div', { className: 'settings-grid' });
  page.appendChild(renderRepositorySection(model, actions));
  page.appendChild(renderTokenSection(model, actions));
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

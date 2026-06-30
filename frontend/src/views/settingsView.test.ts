import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  renderSettingsView,
  type RepositoryFormModel,
  type RuleManagementViewState,
  type SettingsViewActions,
  type SettingsViewModel,
  type TokenFormModel
} from './settingsView';
import type { RepositoryProfile, RuleInfo, TokenProfile } from '../types';

function defaultRepositoryForm(): RepositoryFormModel {
  return {
    id: '',
    name: '',
    repositoryUrl: '',
    branch: '',
    authMode: 'none',
    tokenProfileId: '',
    notes: '',
    templateSourceName: '',
    errorMessage: ''
  };
}

function defaultTokenForm(): TokenFormModel {
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

function defaultRuleManagement(overrides: Partial<RuleManagementViewState> = {}): RuleManagementViewState {
  return {
    searchText: '',
    severity: 'ALL',
    status: 'ALL',
    categoryExpansion: 'review',
    ...overrides
  };
}

function rule(overrides: Partial<RuleInfo>): RuleInfo {
  return {
    ruleId: 'SPRING_ACTUATOR_ENDPOINT_EXPOSED_PROD',
    title: 'Actuator endpoint exposed in production profile',
    severity: 'WARNING',
    category: 'ACTUATOR',
    runtimeDetection: 'ACTIVE_PROFILE_RUNTIME_MAY_DETECT',
    enabled: true,
    ...overrides
  };
}

function model(overrides: Partial<SettingsViewModel> = {}): SettingsViewModel {
  return {
    repositoryProfiles: [],
    tokenProfiles: [],
    repositoryForm: defaultRepositoryForm(),
    tokenForm: defaultTokenForm(),
    ruleSettings: [
      rule({
        ruleId: 'SPRING_RAW_EXCEPTION_MESSAGE_HTTP',
        title: 'Raw exception message exposed through HTTP response',
        category: 'SECURITY',
        enabled: false
      }),
      rule({
        ruleId: 'SPRING_ACTUATOR_ENDPOINT_EXPOSED_PROD',
        title: 'Actuator endpoint exposed in production profile',
        category: 'ACTUATOR'
      }),
      rule({
        ruleId: 'SPRING_CACHE_UNBOUNDED',
        title: 'Cache has no visible bound',
        severity: 'INFO',
        category: 'CACHING'
      })
    ],
    ruleSettingsLoading: false,
    ruleSettingsError: '',
    ruleManagement: defaultRuleManagement(),
    ...overrides
  };
}

function defaultActions(overrides: Partial<SettingsViewActions> = {}): SettingsViewActions {
  const noop = (..._args: Array<unknown>) => undefined;
  return {
    onRepositoryFormChange: noop,
    onSaveRepository: noop,
    onDeleteSelectedRepository: noop,
    onClearRepositoryForm: noop,
    onEditRepository: noop,
    onUseRepositoryAsTemplate: noop,
    onDeleteRepository: noop,
    onAnalyzeRepository: noop,
    onTokenFormChange: noop,
    onSaveToken: noop,
    onDeleteSelectedToken: noop,
    onClearTokenForm: noop,
    onEditToken: noop,
    onDeleteToken: noop,
    onToggleRule: noop,
    onEnableAllRules: noop,
    onSetAllBySeverity: noop,
    onRuleSearchChange: noop,
    onRuleSeverityFilterChange: noop,
    onRuleStatusFilterChange: noop,
    onRuleCategoryExpansionChange: noop,
    onClearRuleFilters: noop,
    ...overrides
  };
}

describe('renderSettingsView rule management', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('uses GA-safe token copy and only shows the masked suffix', () => {
    const tokenProfile: TokenProfile = {
      id: 'token-1',
      name: 'GitHub repo read',
      provider: 'github',
      host: 'github.com',
      username: 'octo',
      token: 'ghp_exampleTokenValue1234',
      createdAt: '2026-06-21T10:00:00.000Z',
      updatedAt: '2026-06-21T10:00:00.000Z'
    };
    const tokenForm = defaultTokenForm();
    tokenForm.id = tokenProfile.id;
    tokenForm.maskedTokenHint = '********1234';

    const view = renderSettingsView(
      model({
        tokenProfiles: [tokenProfile],
        tokenForm
      }),
      defaultActions()
    );
    document.body.appendChild(view);

    expect(document.body.textContent).toContain('Token profiles are stored locally in this browser.');
    expect(document.body.textContent).toContain('Saved token ending in 1234');
    expect(document.body.textContent).toContain('********1234');
    expect(document.body.textContent).not.toContain('ghp_');
    expect(document.body.textContent).not.toContain('localStorage');
  });

  it('uses provider-specific token placeholders', () => {
    for (const [provider, expected] of [
      ['github', ['GitHub personal token', 'github.com', 'my-github-username', 'ghp_xxx']],
      ['gitlab', ['GitLab access token', 'gitlab.com', 'my-gitlab-username', 'glpat-xxx']],
      ['bitbucket', ['Bitbucket app password', 'bitbucket.org', 'my-bitbucket-username', 'app password']],
      ['other', ['Repository access token', 'git.example.com', 'username', 'token']]
    ] as const) {
      document.body.innerHTML = '';
      const tokenForm = defaultTokenForm();
      tokenForm.provider = provider;
      const view = renderSettingsView(model({ tokenForm }), defaultActions());
      document.body.appendChild(view);

      expect((document.getElementById('settings-token-name') as HTMLInputElement).placeholder).toBe(expected[0]);
      expect((document.getElementById('settings-token-host') as HTMLInputElement).placeholder).toBe(expected[1]);
      expect((document.getElementById('settings-token-username') as HTMLInputElement).placeholder).toBe(expected[2]);
      expect((document.getElementById('settings-token-value') as HTMLInputElement).placeholder).toBe(expected[3]);
    }
  });

  it('wires saved repository template action', () => {
    const onUseRepositoryAsTemplate = vi.fn();
    const repository: RepositoryProfile = {
      id: 'repo-1',
      name: 'Trading Bot',
      repositoryUrl: 'https://github.com/example/tradingbot.git',
      branch: 'main',
      authMode: 'token',
      tokenProfileId: 'token-1',
      notes: 'Default setup',
      createdAt: '2026-06-21T10:00:00.000Z',
      updatedAt: '2026-06-21T10:00:00.000Z'
    };

    const view = renderSettingsView(
      model({ repositoryProfiles: [repository] }),
      defaultActions({ onUseRepositoryAsTemplate })
    );
    document.body.appendChild(view);

    const templateButton = Array.from(document.querySelectorAll('button')).find(
      (button) => button.textContent === 'Use as template'
    ) as HTMLButtonElement | undefined;

    expect(templateButton).toBeTruthy();
    templateButton?.click();
    expect(onUseRepositoryAsTemplate).toHaveBeenCalledWith('repo-1');
  });

  it('shows repository template context when present', () => {
    const repositoryForm = defaultRepositoryForm();
    repositoryForm.templateSourceName = 'Trading Bot';

    const view = renderSettingsView(model({ repositoryForm }), defaultActions());
    document.body.appendChild(view);

    expect(document.body.textContent).toContain('Using Trading Bot as template');
    expect(document.body.textContent).toContain('Enter the new repository URL');
  });

  it('filters rules by text, severity, and disabled status', () => {
    const view = renderSettingsView(
      model({
        ruleManagement: defaultRuleManagement({
          searchText: 'exception',
          severity: 'WARNING',
          status: 'DISABLED'
        })
      }),
      defaultActions()
    );
    document.body.appendChild(view);

    expect(document.querySelector('.rule-count-summary')?.textContent).toContain('1 of 3 rules');
    expect(document.querySelector('.rule-count-summary')?.textContent).toContain('1 disabled');
    expect(document.querySelectorAll('.rule-row')).toHaveLength(1);
    expect(document.querySelector('.rule-title')?.textContent).toBe('Raw exception message exposed through HTTP response');
    expect(document.querySelector('.rule-category-badge')?.textContent).toBe('1');
    expect((document.querySelector('.rule-category-details') as HTMLDetailsElement).open).toBe(true);
    expect(document.body.textContent).not.toContain('Actuator endpoint exposed in production profile');
  });

  it('wires rule filters and category expansion controls to actions', () => {
    const onRuleSearchChange = vi.fn();
    const onRuleSeverityFilterChange = vi.fn();
    const onRuleStatusFilterChange = vi.fn();
    const onRuleCategoryExpansionChange = vi.fn();
    const onClearRuleFilters = vi.fn();

    const view = renderSettingsView(
      model({
        ruleManagement: defaultRuleManagement({
          searchText: 'cache',
          categoryExpansion: 'none'
        })
      }),
      defaultActions({
        onRuleSearchChange,
        onRuleSeverityFilterChange,
        onRuleStatusFilterChange,
        onRuleCategoryExpansionChange,
        onClearRuleFilters
      })
    );
    document.body.appendChild(view);

    const search = document.getElementById('settings-rule-search') as HTMLInputElement;
    search.value = 'actuator';
    search.dispatchEvent(new Event('input'));
    expect(onRuleSearchChange).toHaveBeenCalledWith('actuator');

    const severity = document.getElementById('settings-rule-severity') as HTMLSelectElement;
    severity.value = 'INFO';
    severity.dispatchEvent(new Event('change'));
    expect(onRuleSeverityFilterChange).toHaveBeenCalledWith('INFO');

    const status = document.getElementById('settings-rule-status') as HTMLSelectElement;
    status.value = 'DISABLED';
    status.dispatchEvent(new Event('change'));
    expect(onRuleStatusFilterChange).toHaveBeenCalledWith('DISABLED');

    expect((document.querySelector('.rule-category-details') as HTMLDetailsElement).open).toBe(false);

    (document.querySelector('.rule-view-actions button') as HTMLButtonElement).click();
    expect(onRuleCategoryExpansionChange).toHaveBeenCalledWith('all');

    (document.querySelector('.rule-filter-clear') as HTMLButtonElement).click();
    expect(onClearRuleFilters).toHaveBeenCalledOnce();
  });

  it('collapses the full rule catalog when there are no disabled rules or active filters', () => {
    const view = renderSettingsView(
      model({
        ruleSettings: [
          rule({ ruleId: 'SPRING_RAW_EXCEPTION_MESSAGE_HTTP', title: 'Raw exception message exposed through HTTP response', category: 'SECURITY' }),
          rule({ ruleId: 'SPRING_ACTUATOR_ENDPOINT_EXPOSED_PROD', title: 'Actuator endpoint exposed in production profile', category: 'ACTUATOR' })
        ],
        ruleManagement: defaultRuleManagement()
      }),
      defaultActions()
    );
    document.body.appendChild(view);

    const catalog = document.querySelector('.rule-catalog-details') as HTMLDetailsElement;
    expect(catalog.open).toBe(false);
    expect(document.querySelector('.rule-count-meta')?.textContent).toBe('2 rules available');
    expect(document.body.textContent).not.toContain('Showing the full rule catalog');
    expect(document.querySelector('.rule-catalog-summary')?.textContent).toContain('Configure rule catalog');
    expect(document.getElementById('settings-rule-search')).not.toBeNull();
  });
});

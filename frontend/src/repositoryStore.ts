import type { RepositoryProfile, RepositoryProfileInput } from './types';

const STORAGE_KEY = 'springBootAnalyzer.repositories.v1';
const FORM_STATE_KEY = 'springBootAnalyzer.repositoryForm.v1';

export function loadRepositoryProfiles(): RepositoryProfile[] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return [];
    }

    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }

    return parsed.filter(isRepositoryProfileLike).map((profile) => ({
      id: String(profile.id),
      name: String(profile.name),
      repositoryUrl: String(profile.repositoryUrl),
      branch: normalizeOptionalString(profile.branch),
      authMode: normalizeAuthMode(profile.authMode),
      tokenProfileId: normalizeOptionalString(profile.tokenProfileId),
      notes: normalizeOptionalString(profile.notes),
      createdAt: String(profile.createdAt),
      updatedAt: String(profile.updatedAt)
    }));
  } catch {
    return [];
  }
}

export function saveRepositoryProfiles(repositories: RepositoryProfile[]): void {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(repositories));
}

export function addOrUpdateRepositoryProfile(input: RepositoryProfileInput): RepositoryProfile[] {
  const repositories = loadRepositoryProfiles();
  const trimmedId = input.id?.trim();
  const existing = trimmedId ? repositories.find((repository) => repository.id === trimmedId) : undefined;
  const trimmedName = input.name.trim();

  const duplicate = repositories.find(
    (repository) =>
      repository.name.trim().toLowerCase() === trimmedName.toLowerCase() && repository.id !== existing?.id
  );

  if (duplicate) {
    throw new Error('A saved repository with that name already exists.');
  }

  if (input.authMode === 'token' && !input.tokenProfileId?.trim()) {
    throw new Error('Choose a default token profile when auth mode is HTTPS token.');
  }

  const now = new Date().toISOString();
  const nextProfile: RepositoryProfile = {
    id: existing?.id ?? createId(),
    name: trimmedName,
    repositoryUrl: input.repositoryUrl.trim(),
    branch: normalizeOptionalString(input.branch),
    authMode: normalizeAuthMode(input.authMode),
    tokenProfileId: input.authMode === 'token' ? normalizeOptionalString(input.tokenProfileId) : null,
    notes: normalizeOptionalString(input.notes),
    createdAt: existing?.createdAt ?? now,
    updatedAt: now
  };

  const nextRepositories = existing
    ? repositories.map((repository) => (repository.id === existing.id ? nextProfile : repository))
    : [nextProfile, ...repositories];

  saveRepositoryProfiles(nextRepositories);
  return nextRepositories;
}

export function deleteRepositoryProfile(id: string): RepositoryProfile[] {
  const nextRepositories = loadRepositoryProfiles().filter((repository) => repository.id !== id);
  saveRepositoryProfiles(nextRepositories);
  return nextRepositories;
}

export function clearRepositoryFormState(): void {
  window.sessionStorage.removeItem(FORM_STATE_KEY);
}

export function findRepositoryById(id: string): RepositoryProfile | undefined {
  return loadRepositoryProfiles().find((repository) => repository.id === id);
}

export function inferAuthMode(repositoryUrl: string): 'none' | 'token' | 'ssh' {
  const trimmed = repositoryUrl.trim();
  if (!trimmed) {
    return 'none';
  }

  if (/^[^@]+@[^:]+:.+$/.test(trimmed)) {
    return 'ssh';
  }

  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol === 'ssh:') {
      return 'ssh';
    }
    if (parsed.protocol === 'https:') {
      return 'none';
    }
  } catch {
    return 'none';
  }

  return 'none';
}

export function inferRepositoryName(repositoryUrl: string): string | null {
  const trimmed = repositoryUrl.trim();
  if (!trimmed) {
    return null;
  }

  const scpLikeMatch = trimmed.match(/^[^@/\s]+@[^:\s]+:(.+)$/);
  if (scpLikeMatch) {
    return repositoryNameFromPath(scpLikeMatch[1]);
  }

  try {
    return repositoryNameFromPath(new URL(trimmed).pathname);
  } catch {
    return repositoryNameFromPath(trimmed);
  }
}

function isRepositoryProfileLike(value: unknown): value is RepositoryProfile {
  if (!value || typeof value !== 'object') {
    return false;
  }

  const repository = value as Record<string, unknown>;
  return (
    typeof repository.id === 'string' &&
    typeof repository.name === 'string' &&
    typeof repository.repositoryUrl === 'string' &&
    typeof repository.createdAt === 'string' &&
    typeof repository.updatedAt === 'string'
  );
}

function normalizeAuthMode(value: unknown): RepositoryProfile['authMode'] {
  if (value === 'token' || value === 'ssh') {
    return value;
  }
  return 'none';
}

function normalizeOptionalString(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }

  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

function createId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }

  return `repository-profile-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function repositoryNameFromPath(path: string): string | null {
  const cleanPath = path.split(/[?#]/)[0]?.replace(/\\/g, '/').replace(/\/+$/, '') ?? '';
  const pathSegments = cleanPath.split('/').filter(Boolean);
  const lastSegment = pathSegments[pathSegments.length - 1];
  if (!lastSegment) {
    return null;
  }

  const decodedSegment = safeDecodeURIComponent(lastSegment);
  const repositoryName = decodedSegment.replace(/\.git$/i, '').trim();
  return repositoryName || null;
}

function safeDecodeURIComponent(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

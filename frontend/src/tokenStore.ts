import type { TokenProfile, TokenProfileInput } from './types';

const STORAGE_KEY = 'springBootAnalyzer.tokenProfiles.v1';

export function loadTokenProfiles(): TokenProfile[] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return [];
    }

    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }

    return parsed.filter(isTokenProfileLike).map((profile) => ({
      id: String(profile.id),
      name: String(profile.name),
      provider: normalizeProvider(profile.provider),
      host: String(profile.host).trim().toLowerCase(),
      username: String(profile.username),
      token: String(profile.token),
      createdAt: String(profile.createdAt),
      updatedAt: String(profile.updatedAt)
    }));
  } catch {
    return [];
  }
}

export function saveTokenProfiles(profiles: TokenProfile[]): void {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(profiles));
}

export function addOrUpdateTokenProfile(input: TokenProfileInput): TokenProfile[] {
  const profiles = loadTokenProfiles();
  const trimmedId = input.id?.trim();
  const existing = trimmedId ? profiles.find((profile) => profile.id === trimmedId) : undefined;
  const now = new Date().toISOString();
  const nextToken = input.token.trim() || existing?.token || '';

  if (!existing && !nextToken) {
    throw new Error('Token/PAT is required for a new token profile.');
  }

  const nextProfile: TokenProfile = {
    id: existing?.id ?? createId(),
    name: input.name.trim(),
    provider: normalizeProvider(input.provider),
    host: input.host.trim().toLowerCase(),
    username: input.username.trim(),
    token: nextToken,
    createdAt: existing?.createdAt ?? now,
    updatedAt: now
  };

  const nextProfiles = existing
    ? profiles.map((profile) => (profile.id === existing.id ? nextProfile : profile))
    : [nextProfile, ...profiles];

  saveTokenProfiles(nextProfiles);
  return nextProfiles;
}

export function deleteTokenProfile(id: string): TokenProfile[] {
  const nextProfiles = loadTokenProfiles().filter((profile) => profile.id !== id);
  saveTokenProfiles(nextProfiles);
  return nextProfiles;
}

export function findTokenProfileById(id: string): TokenProfile | undefined {
  return loadTokenProfiles().find((profile) => profile.id === id);
}

export function findMatchingTokenProfile(
  repositoryUrl: string,
  profiles: TokenProfile[]
): TokenProfile | null {
  const host = inferRepositoryHost(repositoryUrl);
  if (!host) {
    return null;
  }

  return profiles.find((profile) => profile.host === host) ?? null;
}

export function maskToken(token: string): string {
  if (!token) {
    return '';
  }

  if (token.length <= 8) {
    return '••••••••';
  }

  const prefix = token.slice(0, 4);
  const suffix = token.slice(-4);
  const maskLength = Math.max(8, token.length - 8);
  return `${prefix}${'•'.repeat(maskLength)}${suffix}`;
}

export function inferRepositoryHost(repositoryUrl: string): string | null {
  const trimmed = repositoryUrl.trim();
  if (!trimmed) {
    return null;
  }

  const scpMatch = trimmed.match(/^[^@]+@([^:]+):.+$/);
  if (scpMatch) {
    return scpMatch[1].toLowerCase();
  }

  try {
    const parsed = new URL(trimmed);
    return parsed.hostname ? parsed.hostname.toLowerCase() : null;
  } catch {
    return null;
  }
}

function normalizeProvider(provider: unknown): TokenProfile['provider'] {
  if (provider === 'github' || provider === 'gitlab' || provider === 'bitbucket') {
    return provider;
  }
  return 'other';
}

function isTokenProfileLike(value: unknown): value is TokenProfile {
  if (!value || typeof value !== 'object') {
    return false;
  }

  const profile = value as Record<string, unknown>;
  return (
    typeof profile.id === 'string' &&
    typeof profile.name === 'string' &&
    typeof profile.host === 'string' &&
    typeof profile.username === 'string' &&
    typeof profile.token === 'string' &&
    typeof profile.createdAt === 'string' &&
    typeof profile.updatedAt === 'string'
  );
}

function createId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }

  return `token-profile-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

import { describe, expect, it } from 'vitest';

import { inferAuthMode, inferRepositoryName } from './repositoryStore';

describe('inferAuthMode', () => {
  it('detects SSH repository URLs', () => {
    expect(inferAuthMode('git@github.com:acme/risk-service.git')).toBe('ssh');
    expect(inferAuthMode('ssh://git@github.com/acme/risk-service.git')).toBe('ssh');
  });

  it('does not force token auth for HTTPS repository URLs', () => {
    expect(inferAuthMode('https://github.com/acme/risk-service.git')).toBe('none');
  });
});

describe('inferRepositoryName', () => {
  it('derives a friendly repository name from common Git URL formats', () => {
    expect(inferRepositoryName('https://github.com/acme/risk-service.git')).toBe('risk-service');
    expect(inferRepositoryName('git@github.com:acme/risk-service.git')).toBe('risk-service');
    expect(inferRepositoryName('ssh://git@github.com/acme/risk-service.git')).toBe('risk-service');
  });

  it('returns null when no repository segment is available', () => {
    expect(inferRepositoryName('')).toBeNull();
    expect(inferRepositoryName('https://github.com')).toBeNull();
  });
});

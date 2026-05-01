import { describe, expect, it } from 'vitest';

import { buildGitHubBlobUrl } from './githubLink';

describe('buildGitHubBlobUrl', () => {
  it('builds a blob URL for https remotes', () => {
    expect(
      buildGitHubBlobUrl(
        'https://github.com/example/demo.git',
        'main',
        'src/main/java/com/example/Demo.java',
        10,
        14
      )
    ).toBe('https://github.com/example/demo/blob/main/src/main/java/com/example/Demo.java#L10-L14');
  });

  it('builds a blob URL for ssh remotes', () => {
    expect(
      buildGitHubBlobUrl(
        'git@github.com:example/demo.git',
        'abc123',
        'src/main/java/com/example/Demo.java',
        7,
        7
      )
    ).toBe('https://github.com/example/demo/blob/abc123/src/main/java/com/example/Demo.java#L7');
  });

  it('preserves slashes in branch names', () => {
    expect(
      buildGitHubBlobUrl(
        'https://github.com/example/demo',
        'feature/ui-polish',
        'src/main/resources/application.properties',
        2,
        2
      )
    ).toBe('https://github.com/example/demo/blob/feature/ui-polish/src/main/resources/application.properties#L2');
  });

  it('returns null for unsupported remotes or missing refs', () => {
    expect(buildGitHubBlobUrl('https://gitlab.com/example/demo.git', 'main', 'src/App.java', 1, 1)).toBeNull();
    expect(buildGitHubBlobUrl('https://github.com/example/demo.git', '', 'src/App.java', 1, 1)).toBeNull();
  });
});

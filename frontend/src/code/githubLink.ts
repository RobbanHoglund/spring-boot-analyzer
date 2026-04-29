export function buildGitHubBlobUrl(
  repositoryUrl: string | null | undefined,
  ref: string | null | undefined,
  filePath: string | null | undefined,
  startLine?: number | null,
  endLine?: number | null
): string | null {
  const parsed = parseGitHubRepository(repositoryUrl);
  const normalizedRef = normalizeRef(ref);
  const normalizedPath = normalizeRepositoryPath(filePath);
  if (!parsed || !normalizedRef || !normalizedPath) {
    return null;
  }
  const lineFragment = buildLineFragment(startLine, endLine);
  return `https://github.com/${parsed.owner}/${parsed.repo}/blob/${normalizedRef}/${normalizedPath}${lineFragment}`;
}

interface ParsedGitHubRepository {
  owner: string;
  repo: string;
}

function parseGitHubRepository(repositoryUrl: string | null | undefined): ParsedGitHubRepository | null {
  const value = (repositoryUrl ?? '').trim();
  if (!value) {
    return null;
  }

  const httpsMatch = value.match(/^https:\/\/github\.com\/([^/]+)\/([^/]+?)(?:\.git)?\/?$/i);
  if (httpsMatch) {
    return { owner: httpsMatch[1], repo: httpsMatch[2] };
  }

  const sshMatch = value.match(/^git@github\.com:([^/]+)\/([^/]+?)(?:\.git)?$/i);
  if (sshMatch) {
    return { owner: sshMatch[1], repo: sshMatch[2] };
  }

  return null;
}

function normalizeRepositoryPath(filePath: string | null | undefined): string | null {
  const value = (filePath ?? '').trim().replace(/\\/g, '/').replace(/^\/+/, '');
  return value ? value.split('/').map(encodeURIComponent).join('/') : null;
}

function normalizeRef(ref: string | null | undefined): string | null {
  const value = (ref ?? '').trim().replace(/^\/+|\/+$/g, '');
  return value ? value.split('/').map(encodeURIComponent).join('/') : null;
}

function buildLineFragment(startLine?: number | null, endLine?: number | null): string {
  if (!startLine || startLine < 1) {
    return '';
  }
  if (!endLine || endLine <= startLine) {
    return `#L${startLine}`;
  }
  return `#L${startLine}-L${endLine}`;
}

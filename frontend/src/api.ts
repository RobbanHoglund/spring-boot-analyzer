import type {
  AnalyzeRepositoryRequest,
  AnalyzeRepositoryResponse,
  RulesConfig,
  SourceSnippetResponse
} from './types';

export class ApiError extends Error {
  readonly status: number;
  readonly details?: unknown;

  constructor(message: string, status: number, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.details = details;
  }
}

export async function analyzeRepository(
  request: AnalyzeRepositoryRequest
): Promise<AnalyzeRepositoryResponse> {
  const response = await fetch('/api/analyze', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(request)
  });

  const payload = await parsePayload(response);

  if (!response.ok) {
    throw new ApiError(buildErrorMessage(response.status, payload), response.status, payload);
  }

  return isObject(payload) ? (payload as AnalyzeRepositoryResponse) : {};
}

export async function fetchSourceSnippet(
  analysisId: string,
  path: string,
  startLine?: number | null,
  endLine?: number | null,
  context = 4
): Promise<SourceSnippetResponse> {
  const params = new URLSearchParams({
    path,
    context: String(context)
  });
  if (typeof startLine === 'number' && typeof endLine === 'number') {
    params.set('startLine', String(startLine));
    params.set('endLine', String(endLine));
  }
  const response = await fetch(`/api/analyses/${encodeURIComponent(analysisId)}/source-snippet?${params.toString()}`);
  const payload = await parsePayload(response);

  if (!response.ok) {
    throw new ApiError(buildErrorMessage(response.status, payload), response.status, payload);
  }

  return isObject(payload) ? (payload as SourceSnippetResponse) : {};
}

export async function fetchRuleSettings(): Promise<RulesConfig> {
  const response = await fetch('/api/settings/rules');
  const payload = await parsePayload(response);

  if (!response.ok) {
    throw new ApiError(buildErrorMessage(response.status, payload), response.status, payload);
  }

  return isObject(payload) ? (payload as RulesConfig) : { rules: [] };
}

export async function saveRuleSettings(disabledRuleIds: string[]): Promise<void> {
  const response = await fetch('/api/settings/rules', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ disabledRuleIds })
  });

  if (!response.ok) {
    const payload = await parsePayload(response);
    throw new ApiError(buildErrorMessage(response.status, payload), response.status, payload);
  }
}

async function parsePayload(response: Response): Promise<unknown> {
  const contentType = response.headers.get('content-type') ?? '';

  if (contentType.includes('application/json')) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  try {
    const text = await response.text();
    return text ? { detail: text } : null;
  } catch {
    return null;
  }
}

function buildErrorMessage(status: number, payload: unknown): string {
  if (isObject(payload)) {
    const errors = payload.errors;
    if (errors && typeof errors === 'object' && !Array.isArray(errors)) {
      const parts = Object.entries(errors as Record<string, unknown>).map(([field, message]) => {
        const safeMessage = typeof message === 'string' ? message : 'Invalid value';
        return `${field}: ${safeMessage}`;
      });
      if (parts.length > 0) {
        return `Request failed (${status}): ${parts.join('; ')}`;
      }
    }

    const detail = typeof payload.detail === 'string' ? payload.detail : '';
    const title = typeof payload.title === 'string' ? payload.title : '';
    const message = detail || title;
    if (message) {
      return `Request failed (${status}): ${message}`;
    }
  }

  return `Request failed with status ${status}.`;
}

function isObject(value: unknown): value is Record<string, any> {
  return value !== null && typeof value === 'object';
}

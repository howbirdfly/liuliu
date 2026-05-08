const DEFAULT_API_BASE_URL = 'http://localhost:8080';
const AUTH_TOKEN_KEY = 'citywalk_token';
const AUTH_REQUIRED_EVENT = 'auth:required';

type ApiRequestInit = RequestInit & {
  skipAuth?: boolean;
};

function getApiBaseUrl(): string {
  const value = import.meta.env.VITE_API_BASE_URL?.trim();
  return value ? value.replace(/\/$/, '') : DEFAULT_API_BASE_URL;
}

export async function apiRequest<T>(path: string, init?: ApiRequestInit): Promise<T> {
  const { skipAuth, headers, ...requestInit } = init ?? {};
  const token = typeof window !== 'undefined' ? window.localStorage.getItem(AUTH_TOKEN_KEY) : null;
  const response = await fetch(`${getApiBaseUrl()}${path}`, {
    ...requestInit,
    headers: {
      'Content-Type': 'application/json',
      ...(!skipAuth && token ? { Authorization: `Bearer ${token}` } : {}),
      ...(headers ?? {}),
    },
  });

  let json: any = null;
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    try {
      json = await response.json();
    } catch {
      json = null;
    }
  }

  if (!response.ok) {
    if (response.status === 401 && typeof window !== 'undefined') {
      window.localStorage.removeItem(AUTH_TOKEN_KEY);
      window.dispatchEvent(new CustomEvent(AUTH_REQUIRED_EVENT));
    }
    throw new Error(json?.message || `Request failed: ${response.status}`);
  }

  if (!json) {
    throw new Error('API request failed');
  }

  if (json?.code !== 0) {
    if (json?.code === 401 && typeof window !== 'undefined') {
      window.localStorage.removeItem(AUTH_TOKEN_KEY);
      window.dispatchEvent(new CustomEvent(AUTH_REQUIRED_EVENT));
    }
    throw new Error(json?.message || 'API request failed');
  }

  return json.data as T;
}

export function getApiBaseUrlForDebug(): string {
  return getApiBaseUrl();
}

export function getAuthTokenStorageKey(): string {
  return AUTH_TOKEN_KEY;
}

export function getAuthRequiredEventName(): string {
  return AUTH_REQUIRED_EVENT;
}

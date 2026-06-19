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

function getAuthStorage(): Storage | null {
  if (typeof window === 'undefined') {
    return null;
  }
  return window.localStorage;
}

export function readAuthToken(): string | null {
  const storage = getAuthStorage();
  if (!storage) {
    return null;
  }

  const persistedToken = storage.getItem(AUTH_TOKEN_KEY);
  if (persistedToken) {
    return persistedToken;
  }

  const legacyToken = window.sessionStorage.getItem(AUTH_TOKEN_KEY);
  if (!legacyToken) {
    return null;
  }

  storage.setItem(AUTH_TOKEN_KEY, legacyToken);
  window.sessionStorage.removeItem(AUTH_TOKEN_KEY);
  return legacyToken;
}

export function writeAuthToken(token: string): void {
  const storage = getAuthStorage();
  if (!storage) {
    return;
  }
  storage.setItem(AUTH_TOKEN_KEY, token);
  window.sessionStorage.removeItem(AUTH_TOKEN_KEY);
}

export function clearAuthToken(): void {
  const storage = getAuthStorage();
  storage?.removeItem(AUTH_TOKEN_KEY);
  if (typeof window !== 'undefined') {
    window.sessionStorage.removeItem(AUTH_TOKEN_KEY);
  }
}

export async function apiRequest<T>(path: string, init?: ApiRequestInit): Promise<T> {
  const { skipAuth, headers, ...requestInit } = init ?? {};
  const token = readAuthToken();
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
      clearAuthToken();
      window.dispatchEvent(new CustomEvent(AUTH_REQUIRED_EVENT));
    }
    throw new Error(json?.message || `Request failed: ${response.status}`);
  }

  if (!json) {
    throw new Error('API request failed');
  }

  if (json?.code !== 0) {
    if (json?.code === 401 && typeof window !== 'undefined') {
      clearAuthToken();
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

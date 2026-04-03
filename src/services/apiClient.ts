const DEFAULT_API_BASE_URL = 'http://localhost:8080';
const AUTH_TOKEN_KEY = 'citywalk_token';
const AUTH_REQUIRED_EVENT = 'auth:required';

function getApiBaseUrl(): string {
  const value = import.meta.env.VITE_API_BASE_URL?.trim();
  return value ? value.replace(/\/$/, '') : DEFAULT_API_BASE_URL;
}

export async function apiRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const token = typeof window !== 'undefined' ? window.localStorage.getItem(AUTH_TOKEN_KEY) : null;
  const response = await fetch(`${getApiBaseUrl()}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  });

  if (!response.ok) {
    if (response.status === 401 && typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent(AUTH_REQUIRED_EVENT));
    }
    throw new Error(`Request failed: ${response.status}`);
  }

  const json = await response.json();

  if (json?.code !== 0) {
    if (json?.code === 401 && typeof window !== 'undefined') {
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

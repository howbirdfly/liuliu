import { apiRequest, getApiBaseUrlForDebug, getAuthTokenStorageKey } from './apiClient';

export interface AppUser {
  id: number;
  nickname: string;
  avatar?: string;
}

interface WechatLoginUrlResponse {
  authUrl: string;
}

interface LoginCallbackPayload {
  token?: string;
  refreshToken?: string;
}

const TOKEN_KEY = getAuthTokenStorageKey();
const MOCK_LOGIN_FLAG = import.meta.env.VITE_USE_MOCK_LOGIN === 'true';

function buildCallbackUrl(): string {
  const url = new URL(window.location.href);
  url.searchParams.delete('token');
  url.searchParams.delete('refreshToken');
  url.searchParams.delete('code');
  url.searchParams.delete('state');
  return url.toString();
}

export function getStoredToken(): string | null {
  return window.localStorage.getItem(TOKEN_KEY);
}

export function saveToken(token: string): void {
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  window.localStorage.removeItem(TOKEN_KEY);
}

export function consumeLoginCallback(): LoginCallbackPayload | null {
  const url = new URL(window.location.href);
  const token = url.searchParams.get('token') || undefined;
  const refreshToken = url.searchParams.get('refreshToken') || undefined;
  const code = url.searchParams.get('code');
  const state = url.searchParams.get('state');

  if (!token && !refreshToken && !code && !state) {
    return null;
  }

  if (token) {
    saveToken(token);
  }

  url.searchParams.delete('token');
  url.searchParams.delete('refreshToken');
  url.searchParams.delete('code');
  url.searchParams.delete('state');
  window.history.replaceState({}, document.title, url.toString());

  return { token, refreshToken };
}

export async function loadCurrentUser(): Promise<AppUser> {
  return apiRequest<AppUser>('/api/v1/auth/me');
}

export async function updateUserProfile(payload: { nickname: string; avatar?: string }): Promise<AppUser> {
  return apiRequest<AppUser>('/api/v1/auth/profile', {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export async function mockLogin(): Promise<void> {
  const response = await apiRequest<{
    token: string;
    refreshToken: string;
    expiresIn: number;
    user: AppUser;
  }>('/api/v1/auth/mock-login', {
    method: 'POST',
    body: JSON.stringify({}),
  });

  saveToken(response.token);
}

export async function sendEmailCode(email: string, scene: 'register' | 'reset'): Promise<void> {
  await apiRequest('/api/v1/auth/email/send-code', {
    method: 'POST',
    body: JSON.stringify({ email, scene }),
  });
}

export async function registerWithEmail(email: string, password: string, code: string): Promise<void> {
  const response = await apiRequest<{
    token: string;
    refreshToken: string;
    expiresIn: number;
    user: AppUser;
  }>('/api/v1/auth/email/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, code }),
  });

  saveToken(response.token);
}

export async function loginWithEmail(email: string, password: string): Promise<void> {
  const response = await apiRequest<{
    token: string;
    refreshToken: string;
    expiresIn: number;
    user: AppUser;
  }>('/api/v1/auth/email/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });

  saveToken(response.token);
}

export async function resetPasswordWithEmail(email: string, password: string, code: string): Promise<void> {
  await apiRequest('/api/v1/auth/email/reset-password', {
    method: 'POST',
    body: JSON.stringify({ email, password, code }),
  });
}

export async function redirectToWechatLogin(): Promise<void> {
  if (MOCK_LOGIN_FLAG) {
    await mockLogin();
    return;
  }

  const redirectUri = encodeURIComponent(buildCallbackUrl());
  const response = await fetch(`${getApiBaseUrlForDebug()}/api/v1/auth/wechat/url?redirectUri=${redirectUri}`);

  if (!response.ok) {
    throw new Error(`Failed to get WeChat login url: ${response.status}`);
  }

  const json = await response.json();
  if (json?.code !== 0 || !json?.data?.authUrl) {
    throw new Error(json?.message || 'Failed to get WeChat login url');
  }

  const data = json.data as WechatLoginUrlResponse;
  window.location.href = data.authUrl;
}

export async function logoutFromServer(): Promise<void> {
  try {
    await apiRequest('/api/v1/auth/logout', { method: 'POST' });
  } finally {
    clearToken();
  }
}

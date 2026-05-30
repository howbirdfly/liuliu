import { apiRequest, clearAuthToken, readAuthToken, writeAuthToken } from './apiClient';

export interface AppUser {
  id: number;
  nickname: string;
  avatar?: string;
  bio?: string;
}

export function getStoredToken(): string | null {
  return readAuthToken();
}

export function saveToken(token: string): void {
  writeAuthToken(token);
}

export function clearToken(): void {
  clearAuthToken();
}

export async function loadCurrentUser(): Promise<AppUser> {
  return apiRequest<AppUser>('/api/v1/auth/me');
}

export async function updateUserProfile(payload: { nickname: string; avatar?: string; bio?: string }): Promise<AppUser> {
  return apiRequest<AppUser>('/api/v1/auth/profile', {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export async function mockLogin(): Promise<AppUser> {
  const response = await apiRequest<{
    token: string;
    refreshToken: string;
    expiresIn: number;
    user: AppUser;
  }>('/api/v1/auth/mock-login', {
    method: 'POST',
    body: JSON.stringify({}),
    skipAuth: true,
  });

  saveToken(response.token);
  return response.user;
}

export async function sendEmailCode(email: string, scene: 'register' | 'reset'): Promise<void> {
  await apiRequest('/api/v1/auth/email/send-code', {
    method: 'POST',
    body: JSON.stringify({ email, scene }),
    skipAuth: true,
  });
}

export async function registerWithEmail(email: string, password: string, code: string): Promise<AppUser> {
  const response = await apiRequest<{
    token: string;
    refreshToken: string;
    expiresIn: number;
    user: AppUser;
  }>('/api/v1/auth/email/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, code }),
    skipAuth: true,
  });

  saveToken(response.token);
  return response.user;
}

export async function loginWithEmail(email: string, password: string): Promise<AppUser> {
  const response = await apiRequest<{
    token: string;
    refreshToken: string;
    expiresIn: number;
    user: AppUser;
  }>('/api/v1/auth/email/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
    skipAuth: true,
  });

  saveToken(response.token);
  return response.user;
}

export async function resetPasswordWithEmail(email: string, password: string, code: string): Promise<void> {
  await apiRequest('/api/v1/auth/email/reset-password', {
    method: 'POST',
    body: JSON.stringify({ email, password, code }),
    skipAuth: true,
  });
}

export async function logoutFromServer(): Promise<void> {
  try {
    await apiRequest('/api/v1/auth/logout', { method: 'POST' });
  } finally {
    clearToken();
  }
}

export async function deleteAccountFromServer(): Promise<void> {
  try {
    await apiRequest('/api/v1/auth/account', { method: 'DELETE' });
  } finally {
    clearToken();
  }
}

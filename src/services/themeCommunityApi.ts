import { apiRequest } from './apiClient';
import type { WalkTheme } from './themeService';

interface CreateThemePayload {
  title: string;
  description: string;
  category: string;
  missions: string[];
  coverImageUrl?: string;
}

interface ThemeApiResponse extends WalkTheme {
  id?: number;
  provider?: string;
  coverImageUrl?: string;
}

export async function submitTheme(payload: CreateThemePayload): Promise<ThemeApiResponse> {
  return apiRequest<ThemeApiResponse>('/api/v1/themes', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

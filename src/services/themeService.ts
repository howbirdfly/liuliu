import { apiRequest } from './apiClient';

export interface WalkTheme {
  title: string;
  description: string;
  category: string;
  missions: string[];
  vibeColor: string;
  provider?: string;
  coverImageUrl?: string;
}

export interface MapPOI {
  title: string;
  uri: string;
  lat?: number;
  lng?: number;
}

interface ThemeApiResponse {
  title: string;
  description: string;
  category: string;
  missions: string[];
  vibeColor: string;
  provider?: string;
  coverImageUrl?: string;
}

interface LocationContextApiResponse {
  locationContext: string;
}

const LOCATION_FALLBACK = '城市街道';

export const PRESET_THEMES: WalkTheme[] = [
  {
    title: '形状漫步：圆角观察',
    description: '在城市的边角里，寻找那些柔软、重复又有节奏的形状。',
    category: '视觉',
    missions: ['找到一个圆形元素', '观察一处重复图案', '记录一个最有趣的转角'],
    vibeColor: '#3b82f6',
  },
  {
    title: '声音漫步：城市回声',
    description: '这次不急着看，先听一听城市今天想说什么。',
    category: '感官',
    missions: ['停下听 30 秒周围的声音', '找到一种最突出的背景音', '记录一个安静片刻'],
    vibeColor: '#10b981',
  },
  {
    title: '绿色漫步：缝隙生长',
    description: '去找那些从水泥和墙角里长出来的生命力。',
    category: '自然',
    missions: ['找到一处墙角植物', '观察一片最亮眼的绿色', '记录一个被忽略的小生命'],
    vibeColor: '#84cc16',
  },
  {
    title: '街区漫步：生活切片',
    description: '从日常的街景里，找到最能代表这片区域气质的细节。',
    category: '城市',
    missions: ['找到一个最有生活感的门面', '记录一处时间痕迹', '拍下一个有故事感的角落'],
    vibeColor: '#f59e0b',
  },
];

function normalizeTheme(data?: Partial<ThemeApiResponse> | null, fallback?: WalkTheme): WalkTheme {
  const base = fallback ?? PRESET_THEMES[0];
  return {
    title: data?.title || base.title,
    description: data?.description || base.description,
    category: data?.category || base.category,
    missions: Array.isArray(data?.missions) && data!.missions!.length > 0 ? data!.missions! : base.missions,
    vibeColor: data?.vibeColor || base.vibeColor,
    provider: data?.provider,
    coverImageUrl: data?.coverImageUrl,
  };
}

export async function generateAITheme(
  mood: string,
  weather: string,
  season: string,
  preference: string,
  locationName: string,
  locationContext: string,
  walkMode: string,
): Promise<WalkTheme> {
  try {
    const data = await apiRequest<ThemeApiResponse>('/api/v1/ai/themes/generate', {
      method: 'POST',
      body: JSON.stringify({
        mood,
        weather,
        season,
        preference,
        locationName,
        locationContext,
        walkMode,
      }),
    });
    return normalizeTheme(data);
  } catch (error) {
    console.error('Error generating AI theme:', error);
    return normalizeTheme(
      {
        title: '即兴城市漫步',
        description: '换一种速度，重新看见眼前这座城市。',
        category: '探索',
        missions: ['找到一个让你停下来的细节', '记录一种今天独有的氛围', '给这段路起一个名字'],
        vibeColor: '#6366f1',
      },
      PRESET_THEMES[3],
    );
  }
}

export async function generateDynamicPreset(
  category: string,
  locationName: string,
  locationContext: string,
  walkMode: string,
): Promise<WalkTheme> {
  try {
    const data = await apiRequest<ThemeApiResponse>('/api/v1/ai/themes/preset', {
      method: 'POST',
      body: JSON.stringify({
        category,
        locationName,
        locationContext,
        walkMode,
      }),
    });
    return normalizeTheme(data, PRESET_THEMES[0]);
  } catch (error) {
    console.error('Error generating dynamic preset:', error);
    return PRESET_THEMES[Math.floor(Math.random() * PRESET_THEMES.length)];
  }
}

export async function getLocationContext(lat: number, lng: number): Promise<string> {
  try {
    const data = await apiRequest<LocationContextApiResponse>(
      `/api/v1/ai/location/context?lat=${encodeURIComponent(lat)}&lng=${encodeURIComponent(lng)}`,
    );
    return data.locationContext || LOCATION_FALLBACK;
  } catch (error) {
    console.error('Error getting location context:', error);
    return LOCATION_FALLBACK;
  }
}

export async function searchLocationContext(query: string): Promise<string> {
  try {
    const data = await apiRequest<LocationContextApiResponse>(
      `/api/v1/ai/location/search-context?query=${encodeURIComponent(query)}`,
    );
    return data.locationContext || query;
  } catch (error) {
    console.error('Error searching location context:', error);
    return query;
  }
}

export async function generateCombinedTheme(
  categories: string[],
  locationName: string,
  locationContext: string,
  walkMode: string,
): Promise<WalkTheme> {
  try {
    const data = await apiRequest<ThemeApiResponse>('/api/v1/ai/themes/combine', {
      method: 'POST',
      body: JSON.stringify({
        categories,
        locationName,
        locationContext,
        walkMode,
      }),
    });
    return normalizeTheme(data, PRESET_THEMES[1]);
  } catch (error) {
    console.error('Error generating combined theme:', error);
    return normalizeTheme(
      {
        title: '混合探索',
        description: '把几个观察角度叠在一起，城市会变得更有层次。',
        category: '组合',
        missions: ['找到一个同时符合两个主题的细节', '记录一个意外发现', '总结这段路线的气质'],
        vibeColor: '#94a3b8',
      },
      PRESET_THEMES[1],
    );
  }
}

export async function fetchNearbyPOIs(lat: number, lng: number): Promise<MapPOI[]> {
  try {
    return await apiRequest<MapPOI[]>(
      `/api/v1/map/pois/nearby?lat=${encodeURIComponent(lat)}&lng=${encodeURIComponent(lng)}`,
    );
  } catch (error) {
    console.error('Error fetching nearby POIs:', error);
    return [];
  }
}

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

interface WalkRecordCardTextApiResponse {
  shortNote: string;
  story: string;
  provider?: string;
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

function getPresetThemeFallback(category: string): WalkTheme {
  const normalized = category.replace(/\s+/g, '');

  if (normalized.includes('声音')) {
    return {
      title: '声音漫步：城市回声',
      description: '先把脚步放慢一点，去听风声、人声、路口的回响和突然安静下来的片刻。',
      category: '声音漫步',
      missions: ['停下听 30 秒周围的声音', '找到一种最突出的背景音', '记录一个突然安静下来的片刻'],
      vibeColor: '#10b981',
    };
  }

  if (normalized.includes('颜色')) {
    return {
      title: '颜色漫步：春日取样',
      description: '沿着这段路慢慢走，留心今天最先撞进眼睛里的颜色和它们之间的变化。',
      category: '颜色漫步',
      missions: ['找到一种今天最醒目的颜色', '记录两种相邻但反差明显的色彩', '拍下一个让你停下来的配色角落'],
      vibeColor: '#f59e0b',
    };
  }

  if (normalized.includes('形状')) {
    return {
      title: '形状漫步：转角观察',
      description: '从线条、轮廓和重复图案里重新认识这段熟悉的路，把目光交给形状本身。',
      category: '形状漫步',
      missions: ['找到一个有趣的圆形或弧线', '观察一处重复出现的几何图案', '记录一个最特别的转角轮廓'],
      vibeColor: '#3b82f6',
    };
  }

  if (normalized.includes('自然')) {
    return {
      title: '自然漫步：缝隙生长',
      description: '去看植物、风和光线怎样偷偷长进人造空间里，找到这条路最柔软的一面。',
      category: '自然漫步',
      missions: ['找到一处被忽略的小植物', '观察一片最亮眼的绿色', '记录风吹过树叶或草丛的瞬间'],
      vibeColor: '#84cc16',
    };
  }

  if (normalized.includes('街区')) {
    return {
      title: '街区漫步：生活切片',
      description: '从门面、转角和路口的人间烟火里，慢慢拼出这片街区今天的气质。',
      category: '街区漫步',
      missions: ['找到一个最有生活感的门面', '记录一处时间痕迹', '拍下一个有故事感的街角'],
      vibeColor: '#f59e0b',
    };
  }


  if (normalized.includes('??')) {
    return {
      title: '?????????',
      description: '???????????????????????????????????',
      category: '????',
      missions: ['?????????????', '???????????????', '?????????????????'],
      vibeColor: '#ec4899',
    };
  }
  return PRESET_THEMES[0];
}

function isSoundAlignedMission(mission: string): boolean {
  return ['听', '声音', '声', '回声', '噪音', '安静', '风声', '鸟鸣', '脚步', '节奏', '人声'].some((keyword) =>
    mission.includes(keyword),
  );
}

function isAnimalAlignedMission(mission: string): boolean {
  return ['??', '??', '??', '?', '??', '??', '??', '??', '??', '??', '??'].some((keyword) =>
    mission.includes(keyword),
  );
}

function alignThemeToCategory(theme: WalkTheme, category: string): WalkTheme {
  const fallback = getPresetThemeFallback(category);
  const normalized = category.replace(/\s+/g, '');

  if (normalized.includes('声音')) {
    const soundMissionCount = theme.missions.filter(isSoundAlignedMission).length;
    if (soundMissionCount < 2) {
      return {
        ...theme,
        category: fallback.category,
        missions: fallback.missions,
      };
    }
  }


  if (normalized.includes('??')) {
    const animalMissionCount = theme.missions.filter(isAnimalAlignedMission).length;
    if (animalMissionCount < 2) {
      return {
        ...theme,
        category: fallback.category,
        missions: fallback.missions,
      };
    }
  }
  return theme;
}

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
  const fallback = getPresetThemeFallback(category);
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
    return alignThemeToCategory(normalizeTheme(data, fallback), category);
  } catch (error) {
    console.error('Error generating dynamic preset:', error);
    return fallback;
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

export async function generateWalkRecordCardText(params: {
  themeTitle: string;
  themeDescription: string;
  missionText: string;
  locationName: string;
  locationContext: string;
  noteText: string;
  hasPhoto: boolean;
}): Promise<WalkRecordCardTextApiResponse> {
  return apiRequest<WalkRecordCardTextApiResponse>('/api/v1/ai/walk-record-card', {
    method: 'POST',
    body: JSON.stringify(params),
  });
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

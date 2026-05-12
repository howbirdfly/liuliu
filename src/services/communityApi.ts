import { apiRequest } from './apiClient';
import type { WalkItem } from './walkApi';

export type CommunityFeedTab = 'latest' | 'hot' | 'recommend';

export interface CommunityWalkItem extends WalkItem {
  likeCount: number;
  favoriteCount: number;
  viewCount: number;
  tags?: string[];
}

export async function searchCommunityWalks(
  keyword: string,
  page = 1,
  pageSize = 10,
): Promise<CommunityWalkItem[]> {
  return apiRequest<CommunityWalkItem[]>(
    `/api/v1/community/search?keyword=${encodeURIComponent(keyword)}&page=${page}&pageSize=${pageSize}`,
  );
}

export async function fetchCommunityFeed(
  tab: CommunityFeedTab,
  page = 1,
  pageSize = 10,
): Promise<CommunityWalkItem[]> {
  return apiRequest<CommunityWalkItem[]>(`/api/v1/community/feed/${tab}?page=${page}&pageSize=${pageSize}`);
}

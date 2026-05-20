import { apiRequest } from './apiClient';
import type { WalkItem } from './walkApi';

export type CommunityFeedTab = 'latest' | 'hot' | 'recommend';

export interface CommunityWalkItem extends WalkItem {
  likeCount: number;
  favoriteCount: number;
  viewCount: number;
  liked?: boolean;
  favorited?: boolean;
  tags?: string[];
}

export interface CommunityEngagementState {
  walkId: number;
  likeCount: number;
  favoriteCount: number;
  liked: boolean;
  favorited: boolean;
}

export interface CommunityCommentItem {
  id: number;
  walkId: number;
  parentId?: number | null;
  authorId: number;
  authorNickname: string;
  authorAvatar?: string;
  content: string;
  deleted?: boolean;
  createdAt?: number;
  replies: CommunityCommentItem[];
}

export interface CreateCommunityCommentPayload {
  content: string;
  parentId?: number | null;
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

export async function fetchCommunityWalkDetail(walkId: number): Promise<CommunityWalkItem> {
  return apiRequest<CommunityWalkItem>(`/api/v1/community/walks/${walkId}`);
}

export async function fetchCommunityComments(walkId: number): Promise<CommunityCommentItem[]> {
  return apiRequest<CommunityCommentItem[]>(`/api/v1/community/walks/${walkId}/comments`);
}

export async function createCommunityComment(
  walkId: number,
  payload: CreateCommunityCommentPayload,
): Promise<CommunityCommentItem> {
  return apiRequest<CommunityCommentItem>(`/api/v1/community/walks/${walkId}/comments`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function deleteCommunityComment(commentId: number): Promise<boolean> {
  return apiRequest<boolean>(`/api/v1/community/comments/${commentId}`, {
    method: 'DELETE',
  });
}

export async function fetchMyLikedCommunityWalks(page = 1, pageSize = 20): Promise<CommunityWalkItem[]> {
  return apiRequest<CommunityWalkItem[]>(`/api/v1/community/me/liked?page=${page}&pageSize=${pageSize}`);
}

export async function fetchMyFavoritedCommunityWalks(page = 1, pageSize = 20): Promise<CommunityWalkItem[]> {
  return apiRequest<CommunityWalkItem[]>(`/api/v1/community/me/favorited?page=${page}&pageSize=${pageSize}`);
}

export async function likeCommunityWalk(walkId: number): Promise<CommunityEngagementState> {
  return apiRequest<CommunityEngagementState>(`/api/v1/community/walks/${walkId}/like`, {
    method: 'POST',
  });
}

export async function unlikeCommunityWalk(walkId: number): Promise<CommunityEngagementState> {
  return apiRequest<CommunityEngagementState>(`/api/v1/community/walks/${walkId}/like`, {
    method: 'DELETE',
  });
}

export async function favoriteCommunityWalk(walkId: number): Promise<CommunityEngagementState> {
  return apiRequest<CommunityEngagementState>(`/api/v1/community/walks/${walkId}/favorite`, {
    method: 'POST',
  });
}

export async function unfavoriteCommunityWalk(walkId: number): Promise<CommunityEngagementState> {
  return apiRequest<CommunityEngagementState>(`/api/v1/community/walks/${walkId}/favorite`, {
    method: 'DELETE',
  });
}

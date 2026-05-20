import { apiRequest } from './apiClient';

export interface PathPoint {
  lat: number;
  lng: number;
  timestamp: number;
}

export interface RoomMemberTrack {
  userId: number;
  nickname: string;
  avatarUrl?: string;
  trackColor: string;
  isOwner?: boolean;
  isTracking?: boolean;
  currentPosition?: PathPoint | null;
  path: PathPoint[];
  completedMissions?: string[];
}

export interface CompletedMissionPayload {
  mission: string;
  mediaUrl: string;
  mediaType: string;
}

export interface CreateWalkPayload {
  themeTitle: string;
  themeCategory?: string;
  locationName?: string;
  recordUnit: 'location' | 'event' | 'image';
  isPublic: boolean;
  noteText?: string;
  path: PathPoint[];
  completedMissions: CompletedMissionPayload[];
  roomCode?: string;
  roomMembers?: RoomMemberTrack[];
  photoUrl?: string;
  videoUrl?: string;
  audioUrl?: string;
}

export interface UpdateWalkPayload {
  themeTitle: string;
  themeCategory?: string;
  isPublic: boolean;
  noteText?: string;
  tags?: string[];
}

export interface WalkItem {
  id: number;
  themeTitle: string;
  themeCategory?: string;
  locationName?: string;
  authorId?: number;
  authorNickname?: string;
  authorAvatar?: string;
  recordUnit: string;
  isPublic: boolean;
  noteText?: string;
  photoUrl?: string;
  videoUrl?: string;
  audioUrl?: string;
  path?: PathPoint[];
  completedMissions?: CompletedMissionPayload[];
  roomCode?: string;
  roomMembers?: RoomMemberTrack[];
  tags?: string[];
  createdAt?: number;
}

export async function createWalk(payload: CreateWalkPayload): Promise<WalkItem> {
  return apiRequest<WalkItem>('/api/v1/walks', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function fetchMyWalks(page = 1, pageSize = 10): Promise<WalkItem[]> {
  return apiRequest<WalkItem[]>(`/api/v1/walks/me?page=${page}&pageSize=${pageSize}`);
}

export async function fetchPublicWalks(page = 1, pageSize = 20): Promise<WalkItem[]> {
  return apiRequest<WalkItem[]>(`/api/v1/walks/public?page=${page}&pageSize=${pageSize}`);
}

export async function fetchWalkDetail(walkId: number): Promise<WalkItem> {
  return apiRequest<WalkItem>(`/api/v1/walks/${walkId}`);
}

export async function deleteWalk(walkId: number): Promise<{ success: boolean }> {
  return apiRequest<{ success: boolean }>(`/api/v1/walks/${walkId}`, {
    method: 'DELETE',
  });
}

export async function updateWalk(walkId: number, payload: UpdateWalkPayload): Promise<WalkItem> {
  return apiRequest<WalkItem>(`/api/v1/walks/${walkId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

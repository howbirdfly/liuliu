import { apiRequest } from './apiClient';

export interface PathPoint {
  lat: number;
  lng: number;
  timestamp: number;
}

export interface RoomMemberTrack {
  userId: number;
  nickname: string;
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

export interface WalkItem {
  id: number;
  themeTitle: string;
  themeCategory?: string;
  locationName?: string;
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

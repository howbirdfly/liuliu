import { apiRequest, getApiBaseUrlForDebug, readAuthToken } from './apiClient';
import type { PathPoint } from './walkApi';

export interface CoCreateRoomTheme {
  title: string;
  description: string;
  category: string;
  missions: string[];
  vibeColor: string;
  provider?: string;
  coverImageUrl?: string;
}

export interface CoCreateRoomMember {
  userId: number;
  nickname: string;
  avatarUrl?: string;
  trackColor: string;
  isOwner: boolean;
  isTracking: boolean;
  currentPosition?: PathPoint | null;
  path: PathPoint[];
  completedMissions: string[];
  lastActiveAt?: number;
}

export interface CoCreateRoom {
  roomCode: string;
  ownerUserId: number;
  memberLimit: number;
  theme?: CoCreateRoomTheme | null;
  members: CoCreateRoomMember[];
  createdAt?: number;
}

export type CoCreateRoomSocketEventType = 'room_snapshot' | 'room_closed';

export interface CoCreateRoomSocketEvent {
  type: CoCreateRoomSocketEventType;
  roomCode: string;
  room?: CoCreateRoom | null;
}

export interface CreateCoCreateRoomPayload {
  roomCode?: string;
  theme: CoCreateRoomTheme;
}

export interface UpdateCoCreateRoomStatePayload {
  isTracking: boolean;
  currentPosition?: PathPoint | null;
  path: PathPoint[];
  completedMissions: string[];
}

export async function createCoCreateRoom(payload: CreateCoCreateRoomPayload): Promise<CoCreateRoom> {
  return apiRequest<CoCreateRoom>('/api/v1/co-create/rooms', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function joinCoCreateRoom(roomCode: string): Promise<CoCreateRoom> {
  return apiRequest<CoCreateRoom>('/api/v1/co-create/rooms/join', {
    method: 'POST',
    body: JSON.stringify({ roomCode }),
  });
}

export async function fetchCoCreateRoom(roomCode: string): Promise<CoCreateRoom> {
  return apiRequest<CoCreateRoom>(`/api/v1/co-create/rooms/${encodeURIComponent(roomCode)}`);
}

export async function updateCoCreateRoomState(
  roomCode: string,
  payload: UpdateCoCreateRoomStatePayload,
): Promise<CoCreateRoom> {
  return apiRequest<CoCreateRoom>(`/api/v1/co-create/rooms/${encodeURIComponent(roomCode)}/state`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export async function updateCoCreateRoomTheme(roomCode: string, theme: CoCreateRoomTheme): Promise<CoCreateRoom> {
  return apiRequest<CoCreateRoom>(`/api/v1/co-create/rooms/${encodeURIComponent(roomCode)}/theme`, {
    method: 'PUT',
    body: JSON.stringify({ theme }),
  });
}

export async function leaveCoCreateRoom(roomCode: string): Promise<boolean> {
  return apiRequest<boolean>(`/api/v1/co-create/rooms/${encodeURIComponent(roomCode)}`, {
    method: 'DELETE',
  });
}

export function openCoCreateRoomSocket(roomCode: string): WebSocket {
  const token = readAuthToken();
  const apiBaseUrl = getApiBaseUrlForDebug();
  const wsBaseUrl = apiBaseUrl.replace(/^http/i, 'ws');
  const params = new URLSearchParams({
    roomCode,
    ...(token ? { token } : {}),
  });
  return new WebSocket(`${wsBaseUrl}/ws/co-create?${params.toString()}`);
}

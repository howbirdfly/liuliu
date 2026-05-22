import { apiRequest, getApiBaseUrlForDebug } from './apiClient';

export type UserNotificationType =
  | 'post_commented'
  | 'comment_replied'
  | 'post_liked'
  | 'post_favorited';

export interface UserNotificationItem {
  id: number;
  type: UserNotificationType;
  actorUserId?: number;
  actorNickname: string;
  actorAvatar?: string;
  walkId?: number;
  walkTitle?: string;
  commentId?: number;
  commentContent?: string;
  read: boolean;
  createdAt?: number;
}

interface NotificationUnreadCountPayload {
  unreadCount: number;
}

export interface NotificationStreamEvent {
  type: 'snapshot' | 'notification' | 'unread_count' | 'ping';
  unreadCount: number | null;
  notification?: UserNotificationItem | null;
}

export async function fetchNotifications(page = 1, pageSize = 20): Promise<UserNotificationItem[]> {
  return apiRequest<UserNotificationItem[]>(`/api/v1/notifications?page=${page}&pageSize=${pageSize}`);
}

export async function fetchNotificationUnreadCount(): Promise<number> {
  const data = await apiRequest<NotificationUnreadCountPayload>('/api/v1/notifications/unread-count');
  return data.unreadCount || 0;
}

export async function markNotificationRead(notificationId: number): Promise<void> {
  await apiRequest('/api/v1/notifications/' + notificationId + '/read', {
    method: 'POST',
  });
}

export async function markAllNotificationsRead(): Promise<void> {
  await apiRequest('/api/v1/notifications/read-all', {
    method: 'POST',
  });
}

export function openNotificationStream(token: string): EventSource {
  const streamUrl = `${getApiBaseUrlForDebug()}/api/v1/notifications/stream?token=${encodeURIComponent(token)}`;
  return new EventSource(streamUrl);
}

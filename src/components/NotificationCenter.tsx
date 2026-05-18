import { Bell, Bookmark, Check, Heart, LoaderCircle, MessageCircle, Reply } from 'lucide-react';
import type { UserNotificationItem } from '../services/notificationApi';

type NotificationCenterProps = {
  open: boolean;
  notifications: UserNotificationItem[];
  unreadCount: number;
  isLoading: boolean;
  onClose: () => void;
  onOpenNotification: (item: UserNotificationItem) => void;
  onMarkAllRead: () => void;
  formatDate: (timestamp?: number) => string;
};

function getNotificationTitle(item: UserNotificationItem) {
  const actorName = item.actorNickname?.trim() || 'Community Walker';
  if (item.type === 'post_commented') {
    return `${actorName} 评论了你的帖子`;
  }
  if (item.type === 'comment_replied') {
    return `${actorName} 回复了你的评论`;
  }
  if (item.type === 'post_liked') {
    return `${actorName} 点赞了你的帖子`;
  }
  return `${actorName} 收藏了你的帖子`;
}

function getNotificationIcon(type: UserNotificationItem['type']) {
  if (type === 'post_commented') {
    return <MessageCircle className="h-4 w-4" />;
  }
  if (type === 'comment_replied') {
    return <Reply className="h-4 w-4" />;
  }
  if (type === 'post_liked') {
    return <Heart className="h-4 w-4" />;
  }
  return <Bookmark className="h-4 w-4" />;
}

function buildNotificationPreview(item: UserNotificationItem) {
  const walkTitle = item.walkTitle?.trim();
  const commentContent = item.commentContent?.trim();

  if (commentContent) {
    return commentContent.length > 60 ? `${commentContent.slice(0, 60)}...` : commentContent;
  }
  if (walkTitle) {
    return `《${walkTitle}》`;
  }
  return '打开对应帖子查看详情';
}

export function NotificationCenter(props: NotificationCenterProps) {
  const { open, notifications, unreadCount, isLoading, onClose, onOpenNotification, onMarkAllRead, formatDate } = props;

  if (!open) {
    return null;
  }

  return (
    <div className="fixed inset-0 z-50 bg-slate-900/40 px-4 py-6" onClick={onClose}>
      <div
        className="ml-auto flex h-full w-full max-w-md flex-col overflow-hidden rounded-[32px] border border-slate-200 bg-white shadow-2xl"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <div>
            <div className="flex items-center gap-2 text-sm font-medium text-slate-900">
              <Bell className="h-4 w-4 text-amber-500" />
              通知中心
            </div>
            <div className="mt-1 text-xs text-slate-500">未读 {unreadCount} 条</div>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onMarkAllRead}
              disabled={unreadCount <= 0}
              className="rounded-full border border-slate-200 px-3 py-1.5 text-xs text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              全部已读
            </button>
            <button
              type="button"
              onClick={onClose}
              className="rounded-full border border-slate-200 px-3 py-1.5 text-xs text-slate-600 transition hover:bg-slate-50"
            >
              关闭
            </button>
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto bg-slate-50/70 p-4">
          {isLoading ? (
            <div className="flex h-full items-center justify-center text-slate-500">
              <LoaderCircle className="h-5 w-5 animate-spin" />
            </div>
          ) : notifications.length === 0 ? (
            <div className="rounded-[24px] border border-dashed border-slate-200 bg-white px-5 py-10 text-center text-sm text-slate-500">
              暂时还没有新通知
            </div>
          ) : (
            <div className="space-y-3">
              {notifications.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => onOpenNotification(item)}
                  className={`w-full rounded-[24px] border px-4 py-4 text-left transition hover:-translate-y-[1px] hover:shadow-sm ${
                    item.read ? 'border-slate-200 bg-white' : 'border-amber-200 bg-amber-50/70'
                  }`}
                >
                  <div className="flex items-start gap-3">
                    {item.actorAvatar ? (
                      <img
                        src={item.actorAvatar}
                        alt={item.actorNickname || 'Community Walker'}
                        className="h-10 w-10 rounded-full object-cover"
                      />
                    ) : (
                      <div className="flex h-10 w-10 items-center justify-center rounded-full bg-slate-200 text-sm font-medium text-slate-700">
                        {(item.actorNickname || 'C').slice(0, 1)}
                      </div>
                    )}
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center justify-between gap-3">
                        <div className="inline-flex items-center gap-2 text-sm font-medium text-slate-900">
                          <span
                            className={`inline-flex h-7 w-7 items-center justify-center rounded-full ${
                              item.type === 'post_liked'
                                ? 'bg-rose-100 text-rose-600'
                                : item.type === 'post_favorited'
                                  ? 'bg-amber-100 text-amber-700'
                                  : 'bg-sky-100 text-sky-700'
                            }`}
                          >
                            {getNotificationIcon(item.type)}
                          </span>
                          <span>{getNotificationTitle(item)}</span>
                        </div>
                        {!item.read ? <span className="h-2.5 w-2.5 rounded-full bg-amber-500" /> : null}
                      </div>
                      <div className="mt-2 text-sm text-slate-600">
                        {item.walkTitle ? <span className="font-medium text-slate-700">《{item.walkTitle}》</span> : null}
                      </div>
                      <div className="mt-2 line-clamp-2 text-sm leading-6 text-slate-500">{buildNotificationPreview(item)}</div>
                      <div className="mt-3 flex items-center justify-between text-xs text-slate-400">
                        <span>{formatDate(item.createdAt)}</span>
                        {item.read ? (
                          <span className="inline-flex items-center gap-1">
                            <Check className="h-3.5 w-3.5" />
                            已读
                          </span>
                        ) : (
                          <span>点击查看</span>
                        )}
                      </div>
                    </div>
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

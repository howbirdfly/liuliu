import { MessageCircle } from 'lucide-react';
import type { CommunityCommentItem } from '../services/communityApi';

type CommentReplyTarget = {
  id: number;
  authorNickname: string;
};

type WalkCommentSectionProps = {
  comments: CommunityCommentItem[];
  isLoading: boolean;
  inputValue: string;
  replyTarget: CommentReplyTarget | null;
  isSubmitting: boolean;
  error: string;
  walkAuthorId?: number;
  walkAuthorAvatar?: string;
  currentUserId?: number;
  currentUserAvatar?: string;
  formatDate: (timestamp?: number) => string;
  onInputChange: (value: string) => void;
  onReplyChange: (target: CommentReplyTarget | null) => void;
  onSubmit: () => void;
  onDelete: (commentId: number) => void;
};

export function WalkCommentSection({
  comments,
  isLoading,
  inputValue,
  replyTarget,
  isSubmitting,
  error,
  walkAuthorId,
  walkAuthorAvatar,
  currentUserId,
  currentUserAvatar,
  formatDate,
  onInputChange,
  onReplyChange,
  onSubmit,
  onDelete,
}: WalkCommentSectionProps) {
  const resolveAvatar = (item: CommunityCommentItem) =>
    item.authorAvatar ||
    (item.authorId === walkAuthorId ? walkAuthorAvatar : undefined) ||
    (item.authorId === currentUserId ? currentUserAvatar : undefined) ||
    'https://placehold.co/64x64?text=U';

  return (
    <section className="rounded-[20px] border border-slate-200 bg-slate-50 px-3 py-3">
      <div className="flex items-center gap-2 text-sm font-medium text-slate-700">
        <MessageCircle className="h-4 w-4" />
        评论区
      </div>

      <div className="mt-3 space-y-2.5">
        {replyTarget ? (
          <div className="flex items-center justify-between rounded-2xl bg-white px-3 py-2 text-xs text-slate-500">
            <span>回复 @{replyTarget.authorNickname}</span>
            <button
              type="button"
              onClick={() => onReplyChange(null)}
              className="rounded-full border border-slate-200 px-2 py-1 text-slate-500 transition hover:bg-slate-50"
            >
              取消
            </button>
          </div>
        ) : null}

        <textarea
          value={inputValue}
          onChange={(event) => onInputChange(event.target.value)}
          maxLength={500}
          rows={2}
          placeholder={replyTarget ? `回复 ${replyTarget.authorNickname}...` : '写下你的想法...'}
          className="w-full resize-none rounded-2xl border border-slate-200 bg-white px-3 py-2.5 text-sm leading-6 text-slate-700 outline-none"
        />

        <div className="flex items-center justify-between gap-3">
          <div className="text-xs text-slate-400">{inputValue.trim().length}/500</div>
          <button
            type="button"
            onClick={onSubmit}
            disabled={isSubmitting || inputValue.trim().length === 0}
            className="rounded-full bg-slate-900 px-3.5 py-1.5 text-xs text-white transition hover:bg-slate-800 disabled:opacity-60"
          >
            {isSubmitting ? '发布中...' : replyTarget ? '发布回复' : '发布评论'}
          </button>
        </div>

        {error ? <div className="rounded-2xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-600">{error}</div> : null}
      </div>

      <div className="mt-4 space-y-2.5">
        {isLoading ? (
          <div className="text-sm text-slate-500">评论加载中...</div>
        ) : comments.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-3 py-3 text-sm text-slate-500">
            还没有评论，来抢个沙发吧。
          </div>
        ) : (
          comments.map((comment) => (
            <article key={comment.id} className="rounded-2xl border border-slate-200 bg-white px-3 py-3">
              <div className="flex items-center gap-2">
                <img src={resolveAvatar(comment)} alt={comment.authorNickname || '社区用户'} className="h-7 w-7 rounded-full object-cover" />
                <div className="min-w-0">
                  <div className="text-sm font-medium text-slate-800">{comment.authorNickname || '社区用户'}</div>
                  <div className="text-xs text-slate-400">{formatDate(comment.createdAt)}</div>
                </div>
              </div>

              <p className={`mt-2 whitespace-pre-wrap text-sm leading-6 ${comment.deleted ? 'italic text-slate-400' : 'text-slate-700'}`}>
                {comment.content}
              </p>

              <div className="mt-2 flex flex-wrap gap-2">
                {!comment.deleted ? (
                  <button
                    type="button"
                    onClick={() => onReplyChange({ id: comment.id, authorNickname: comment.authorNickname || '社区用户' })}
                    className="rounded-full border border-slate-200 px-2.5 py-1 text-xs text-slate-600 transition hover:bg-slate-50"
                  >
                    回复
                  </button>
                ) : null}
                {!comment.deleted && comment.authorId === currentUserId ? (
                  <button
                    type="button"
                    onClick={() => onDelete(comment.id)}
                    className="rounded-full border border-rose-200 bg-rose-50 px-2.5 py-1 text-xs text-rose-600 transition hover:bg-rose-100"
                  >
                    删除
                  </button>
                ) : null}
              </div>

              {comment.replies.length > 0 ? (
                <div className="mt-3 space-y-2 border-t border-slate-100 pt-3">
                  {comment.replies.map((reply) => (
                    <div key={reply.id} className="rounded-xl bg-slate-50 px-3 py-2.5">
                      <div className="flex items-center justify-between gap-3">
                        <div className="flex min-w-0 items-center gap-2">
                          <img src={resolveAvatar(reply)} alt={reply.authorNickname || '社区用户'} className="h-6 w-6 rounded-full object-cover" />
                          <div className="truncate text-xs font-medium text-slate-700">{reply.authorNickname || '社区用户'}</div>
                        </div>
                        <div className="shrink-0 text-xs text-slate-400">{formatDate(reply.createdAt)}</div>
                      </div>
                      <p className={`mt-2 whitespace-pre-wrap text-sm leading-6 ${reply.deleted ? 'italic text-slate-400' : 'text-slate-700'}`}>
                        {reply.content}
                      </p>
                      {!reply.deleted && reply.authorId === currentUserId ? (
                        <div className="mt-2">
                          <button
                            type="button"
                            onClick={() => onDelete(reply.id)}
                            className="rounded-full border border-rose-200 bg-white px-2.5 py-1 text-xs text-rose-600 transition hover:bg-rose-50"
                          >
                            删除
                          </button>
                        </div>
                      ) : null}
                    </div>
                  ))}
                </div>
              ) : null}
            </article>
          ))
        )}
      </div>
    </section>
  );
}

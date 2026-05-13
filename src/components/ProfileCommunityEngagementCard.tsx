import type { MouseEvent } from 'react';
import { Bookmark, Heart } from 'lucide-react';
import type { CommunityWalkItem } from '../services/communityApi';

type ProfileCommunityEngagementCardProps = {
  walk: CommunityWalkItem;
  onToggleLike: (event: MouseEvent<HTMLButtonElement>) => void;
  onToggleFavorite: (event: MouseEvent<HTMLButtonElement>) => void;
};

export function ProfileCommunityEngagementCard({
  walk,
  onToggleLike,
  onToggleFavorite,
}: ProfileCommunityEngagementCardProps) {
  return (
    <div className="rounded-[28px] border border-slate-200 bg-slate-50 px-5 py-5">
      <div className="flex flex-wrap gap-2 text-sm text-slate-500">
        <span className="rounded-full bg-white px-3 py-1.5">Likes {walk.likeCount || 0}</span>
        <span className="rounded-full bg-white px-3 py-1.5">Saves {walk.favoriteCount || 0}</span>
        <span className="rounded-full bg-white px-3 py-1.5">Views {walk.viewCount || 0}</span>
        {walk.tags?.map((tag) => (
          <span key={tag} className="rounded-full bg-amber-50 px-3 py-1.5 text-amber-700">
            #{tag}
          </span>
        ))}
      </div>
      <div className="mt-4 flex flex-wrap gap-3">
        <button
          type="button"
          onClick={onToggleLike}
          className={`inline-flex items-center gap-2 rounded-full px-4 py-2.5 text-sm transition ${
            walk.liked ? 'bg-rose-50 text-rose-600' : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-100'
          }`}
        >
          <Heart className={`h-4 w-4 ${walk.liked ? 'fill-current' : ''}`} />
          <span>{walk.liked ? 'Liked' : 'Like'}</span>
        </button>
        <button
          type="button"
          onClick={onToggleFavorite}
          className={`inline-flex items-center gap-2 rounded-full px-4 py-2.5 text-sm transition ${
            walk.favorited
              ? 'bg-amber-50 text-amber-700'
              : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-100'
          }`}
        >
          <Bookmark className={`h-4 w-4 ${walk.favorited ? 'fill-current' : ''}`} />
          <span>{walk.favorited ? 'Saved' : 'Save'}</span>
        </button>
      </div>
    </div>
  );
}

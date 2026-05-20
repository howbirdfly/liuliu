import { MapPin } from 'lucide-react';
import type { CommunityWalkItem } from '../services/communityApi';
import type { WalkItem } from '../services/walkApi';

type ProfileWalkCard = WalkItem | CommunityWalkItem;

type ProfileWalkCardListProps = {
  walks: ProfileWalkCard[];
  onOpenWalk: (walkId: number) => void;
  getCoverHeightClass: (walk: ProfileWalkCard) => string;
  getGradientClass: (walk: ProfileWalkCard) => string;
  buildSummary: (walk: ProfileWalkCard) => string;
  formatDate: (timestamp?: number) => string;
  fallbackAvatarUrl?: string;
  fallbackNickname?: string;
  showFavoriteCount?: boolean;
};

export function ProfileWalkCardList({
  walks,
  onOpenWalk,
  getCoverHeightClass,
  getGradientClass,
  buildSummary,
  formatDate,
  fallbackAvatarUrl,
  fallbackNickname,
  showFavoriteCount = false,
}: ProfileWalkCardListProps) {
  return (
    <div className="grid grid-cols-2 gap-2">
      {walks.map((walk) => (
        <button
          key={walk.id}
          onClick={() => onOpenWalk(walk.id)}
          className="group overflow-hidden rounded-[16px] border border-slate-200 bg-white text-left shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
        >
          {walk.photoUrl ? (
            <img
              src={walk.photoUrl}
              alt={walk.themeTitle}
              className={`${getCoverHeightClass(walk)} w-full rounded-t-[16px] object-cover`}
            />
          ) : (
            <div
              className={`${getGradientClass(walk)} ${getCoverHeightClass(walk)} flex items-end rounded-t-[16px] px-3 py-3 text-white`}
            >
              <div>
                <div className="text-xs uppercase tracking-[0.2em] text-white/70">{walk.themeCategory || '城市漫步'}</div>
                <div className="mt-2 text-sm font-semibold leading-tight">{walk.themeTitle}</div>
              </div>
            </div>
          )}

          <div className="space-y-2 px-2.5 py-2.5">
            <div>
              <div className="line-clamp-2 text-sm font-semibold leading-5 text-slate-900">{walk.themeTitle}</div>
              <p className="mt-1 truncate text-xs text-slate-500">{buildSummary(walk)}</p>
            </div>

            <div className="hidden flex-wrap gap-2 text-xs text-slate-500">
              <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1">
                <MapPin className="h-3.5 w-3.5" />
                {walk.locationName || '地点待补充'}
              </span>
              <span className="rounded-full bg-slate-100 px-2.5 py-1">轨迹点 {walk.path?.length || 0}</span>
              {walk.photoUrl ? <span className="rounded-full bg-slate-100 px-2.5 py-1">有照片</span> : null}
              {'likeCount' in walk ? <span className="rounded-full bg-slate-100 px-2.5 py-1">赞 {walk.likeCount || 0}</span> : null}
              {'favoriteCount' in walk && showFavoriteCount ? (
                <span className="rounded-full bg-slate-100 px-2.5 py-1">藏 {walk.favoriteCount || 0}</span>
              ) : null}
            </div>

            <div className="flex items-center justify-between pt-1">
              <div className="flex items-center gap-2">
                <img
                  src={walk.authorAvatar || fallbackAvatarUrl || 'https://placehold.co/40x40?text=U'}
                  alt="author avatar"
                  className="h-5 w-5 rounded-full object-cover"
                />
                <div className="max-w-[4.5rem] truncate text-xs text-slate-600">
                  {walk.authorNickname || fallbackNickname || '我的记录'}
                </div>
              </div>
              <div className="text-xs text-slate-400">{formatDate(walk.createdAt)}</div>
            </div>
          </div>
        </button>
      ))}
    </div>
  );
}

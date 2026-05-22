import type { ReactNode } from 'react';
import { MapPin } from 'lucide-react';
import type { WalkItem } from '../services/walkApi';

type ProfileWalkDetailBodyProps = {
  walk: WalkItem;
  authorAvatarUrl?: string;
  authorName: string;
  formattedDate: string;
  coverGradientClassName: string;
  summary: string;
  distanceKm: string;
  completedMissionLabels: string[];
  mapContent: ReactNode;
};

export function ProfileWalkDetailBody({
  walk,
  authorAvatarUrl,
  authorName,
  formattedDate,
  coverGradientClassName,
  summary,
  distanceKm,
  completedMissionLabels,
  mapContent,
}: ProfileWalkDetailBodyProps) {
  return (
    <>
      <div className="flex items-center gap-3">
        <img
          src={authorAvatarUrl || 'https://placehold.co/80x80?text=U'}
          alt="author avatar"
          className="h-9 w-9 rounded-full object-cover"
        />
        <div>
          <div className="text-sm font-medium text-slate-900">{authorName}</div>
          <div className="text-xs text-slate-500">{formattedDate}</div>
        </div>
      </div>

      <div className="overflow-hidden rounded-[20px] border border-slate-200 bg-slate-50">
        {walk.photoUrl ? (
          <img src={walk.photoUrl} alt={walk.themeTitle} className="h-[220px] w-full object-cover" />
        ) : (
          <div
            className={`${coverGradientClassName} flex h-[190px] flex-col justify-end px-4 py-4 text-white`}
          >
            <div className="text-[11px] uppercase tracking-[0.22em] text-white/70">{walk.themeCategory || '城市漫步'}</div>
            <h3 className="mt-2 text-xl font-semibold">{walk.themeTitle}</h3>
            <p className="mt-2 line-clamp-2 text-sm leading-6 text-white/90">{summary}</p>
          </div>
        )}
      </div>

      <div>
        <div className="text-[11px] uppercase tracking-[0.2em] text-slate-400">{walk.themeCategory || '城市漫步'}</div>
        <h3 className="mt-1.5 text-xl font-semibold text-slate-900">{walk.themeTitle}</h3>
        <div className="mt-3 flex flex-wrap gap-1.5 text-xs text-slate-500">
          <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1">
            <MapPin className="h-3.5 w-3.5" />
            {walk.locationName || '地点待补充'}
          </span>
          <span className="rounded-full bg-slate-100 px-2.5 py-1">{walk.isPublic ? '已公开发布' : '仅自己可见'}</span>
          <span className="rounded-full bg-slate-100 px-2.5 py-1">轨迹点 {walk.path?.length || 0}</span>
          <span className="rounded-full bg-slate-100 px-2.5 py-1">距离 {distanceKm} km</span>
        </div>
      </div>

      {walk.noteText ? (
        <div className="rounded-[20px] border border-slate-200 bg-slate-50 px-4 py-4">
          <div className="text-[11px] uppercase tracking-[0.18em] text-slate-400">我的记录</div>
          <p className="mt-3 text-sm leading-7 text-slate-700">{walk.noteText}</p>
        </div>
      ) : null}

      <div className="rounded-[20px] border border-slate-200 bg-slate-50 p-3">
        <div className="text-[11px] uppercase tracking-[0.18em] text-slate-400">轨迹地图</div>
        <div className="mt-3">{mapContent}</div>
      </div>

      <div className="grid gap-3 lg:grid-cols-[1.1fr_0.9fr]">
        <div className="rounded-[20px] border border-slate-200 bg-slate-50 px-4 py-4">
          <div className="text-[11px] uppercase tracking-[0.18em] text-slate-400">完成任务</div>
          <div className="mt-3 space-y-2">
            {completedMissionLabels.length > 0 ? (
              completedMissionLabels.map((mission, index) => (
                <div
                  key={`${mission}-${index}`}
                  className="rounded-2xl border border-emerald-200 bg-emerald-50 px-3 py-2.5 text-sm text-emerald-800"
                >
                  {mission}
                </div>
              ))
            ) : (
              <p className="text-sm text-slate-500">这条记录里还没有单独保存任务完成情况。</p>
            )}
          </div>
        </div>

        <div className="rounded-[20px] border border-slate-200 bg-slate-50 px-4 py-4">
          <div className="text-[11px] uppercase tracking-[0.18em] text-slate-400">路线概览</div>
          <div className="mt-3 space-y-2 text-sm leading-6 text-slate-700">
            <div>记录单元：{walk.recordUnit || 'event'}</div>
            <div>轨迹点数量：{walk.path?.length || 0}</div>
            <div>累计距离：{distanceKm} km</div>
          </div>
        </div>
      </div>
    </>
  );
}

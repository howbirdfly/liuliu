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
          className="h-10 w-10 rounded-full object-cover sm:h-12 sm:w-12"
        />
        <div>
          <div className="font-medium text-slate-900">{authorName}</div>
          <div className="text-sm text-slate-500">{formattedDate}</div>
        </div>
      </div>

      <div className="overflow-hidden rounded-[24px] border border-slate-200 bg-slate-50 sm:rounded-[32px]">
        {walk.photoUrl ? (
          <img src={walk.photoUrl} alt={walk.themeTitle} className="h-[260px] w-full object-cover sm:h-[460px]" />
        ) : (
          <div
            className={`${coverGradientClassName} flex h-[220px] flex-col justify-end px-5 py-5 text-white sm:h-[320px] sm:px-8 sm:py-8`}
          >
            <div className="text-xs uppercase tracking-[0.24em] text-white/70">{walk.themeCategory || '城市漫步'}</div>
            <h3 className="mt-3 text-2xl font-semibold sm:text-4xl">{walk.themeTitle}</h3>
            <p className="mt-4 max-w-2xl text-sm leading-7 text-white/90">{summary}</p>
          </div>
        )}
      </div>

      <div>
        <div className="text-xs uppercase tracking-[0.22em] text-slate-400">{walk.themeCategory || '城市漫步'}</div>
        <h3 className="mt-2 text-2xl font-semibold text-slate-900 sm:text-3xl">{walk.themeTitle}</h3>
        <div className="mt-4 flex flex-wrap gap-2 text-xs text-slate-500 sm:text-sm">
          <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1 sm:px-3 sm:py-1.5">
            <MapPin className="h-4 w-4" />
            {walk.locationName || '地点待补充'}
          </span>
          <span className="rounded-full bg-slate-100 px-2.5 py-1 sm:px-3 sm:py-1.5">{walk.isPublic ? '已公开发布' : '仅自己可见'}</span>
          <span className="rounded-full bg-slate-100 px-2.5 py-1 sm:px-3 sm:py-1.5">轨迹点 {walk.path?.length || 0}</span>
          <span className="rounded-full bg-slate-100 px-2.5 py-1 sm:px-3 sm:py-1.5">距离 {distanceKm} km</span>
        </div>
      </div>

      {walk.noteText ? (
        <div className="rounded-[24px] border border-slate-200 bg-slate-50 px-4 py-4 sm:rounded-[28px] sm:px-5 sm:py-5">
          <div className="text-xs uppercase tracking-[0.18em] text-slate-400">我的记录</div>
          <p className="mt-4 text-sm leading-7 text-slate-700 sm:text-base sm:leading-8">{walk.noteText}</p>
        </div>
      ) : null}

      <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-4 sm:rounded-[28px]">
        <div className="text-xs uppercase tracking-[0.18em] text-slate-400">轨迹地图</div>
        <div className="mt-3">{mapContent}</div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[1.1fr_0.9fr]">
        <div className="rounded-[24px] border border-slate-200 bg-slate-50 px-4 py-4 sm:rounded-[28px] sm:px-5 sm:py-5">
          <div className="text-xs uppercase tracking-[0.18em] text-slate-400">完成任务</div>
          <div className="mt-4 space-y-3">
            {completedMissionLabels.length > 0 ? (
              completedMissionLabels.map((mission, index) => (
                <div
                  key={`${mission}-${index}`}
                  className="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800"
                >
                  {mission}
                </div>
              ))
            ) : (
              <p className="text-sm text-slate-500">这条记录里还没有单独保存任务完成情况。</p>
            )}
          </div>
        </div>

        <div className="rounded-[24px] border border-slate-200 bg-slate-50 px-4 py-4 sm:rounded-[28px] sm:px-5 sm:py-5">
          <div className="text-xs uppercase tracking-[0.18em] text-slate-400">路线概览</div>
          <div className="mt-4 space-y-3 text-sm leading-7 text-slate-700">
            <div>记录单元：{walk.recordUnit || 'event'}</div>
            <div>轨迹点数量：{walk.path?.length || 0}</div>
            <div>累计距离：{distanceKm} km</div>
          </div>
        </div>
      </div>
    </>
  );
}

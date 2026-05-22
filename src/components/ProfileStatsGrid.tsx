type ProfileStatItem = {
  label: string;
  value: number;
};

type ProfileStatsGridProps = {
  items: ProfileStatItem[];
};

export function ProfileStatsGrid({ items }: ProfileStatsGridProps) {
  return (
    <div className="mt-4 grid grid-cols-3 gap-2">
      {items.map((item) => (
        <div key={item.label} className="rounded-[16px] bg-slate-50 px-3 py-3">
          <div className="truncate text-[11px] text-slate-400">{item.label}</div>
          <div className="mt-1 text-lg font-semibold leading-none text-slate-900">{item.value}</div>
        </div>
      ))}
    </div>
  );
}

type ProfileStatItem = {
  label: string;
  value: number;
};

type ProfileStatsGridProps = {
  items: ProfileStatItem[];
};

export function ProfileStatsGrid({ items }: ProfileStatsGridProps) {
  return (
    <div className="mt-6 grid gap-3 sm:grid-cols-3">
      {items.map((item) => (
        <div key={item.label} className="rounded-2xl bg-slate-50 px-4 py-4">
          <div className="text-xs uppercase tracking-[0.18em] text-slate-400">{item.label}</div>
          <div className="mt-2 text-2xl font-semibold text-slate-900">{item.value}</div>
        </div>
      ))}
    </div>
  );
}

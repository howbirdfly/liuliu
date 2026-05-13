type ProfileCollectionTab = 'mine' | 'favorited' | 'liked';

type ProfileCollectionTabsProps = {
  activeTab: ProfileCollectionTab;
  onChange: (tab: ProfileCollectionTab) => void;
};

const PROFILE_COLLECTION_TABS: Array<{ id: ProfileCollectionTab; label: string }> = [
  { id: 'mine', label: '我的记录' },
  { id: 'favorited', label: '我的收藏' },
  { id: 'liked', label: '我赞过的' },
];

export function ProfileCollectionTabs({ activeTab, onChange }: ProfileCollectionTabsProps) {
  return (
    <div className="mb-6 flex flex-wrap gap-2">
      {PROFILE_COLLECTION_TABS.map((tab) => (
        <button
          key={tab.id}
          type="button"
          onClick={() => onChange(tab.id)}
          className={`rounded-full px-4 py-2 text-sm transition ${
            activeTab === tab.id ? 'bg-slate-900 text-white' : 'border border-slate-200 text-slate-600 hover:bg-slate-50'
          }`}
        >
          {tab.label}
        </button>
      ))}
    </div>
  );
}

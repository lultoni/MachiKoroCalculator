/** Purchase area — manual buy + assistant recommendation, wired to dice selection. */

import { useLocale } from '../i18n/useLocale';
import type { RankedOption, MetricRange, ProjectDef } from '../api/types';
import { AssistantPanel } from './AssistantPanel';

interface Props {
  options: RankedOption[];
  metricRanges: Record<string, MetricRange> | undefined;
  evaluating: boolean;
  projects: { projects: ProjectDef[]; byId: (id: string) => ProjectDef | undefined };
  language: 'de' | 'en';
  coinsAfterRoll: number | null;
  ownedIds: string[];
  onHover: (card: { projectId: string; cost: number } | null) => void;
  onBuy: (projectId: string | null) => void;
  engineId?: string;
  iterationsUsed?: number;
  computeTimeMs?: number;
}

export function PurchaseArea({
  options, metricRanges, evaluating,
  projects, language, coinsAfterRoll, ownedIds,
  onHover, onBuy, engineId, iterationsUsed, computeTimeMs,
}: Props) {
  const { t } = useLocale();

  // Manual buy: list affordable cards (exclude already-owned purples and landmarks)
  const affordable = coinsAfterRoll != null
    ? projects.projects.filter(p => {
        if (p.cost > coinsAfterRoll) return false;
        // Purple cards: max 1 per player
        if (p.color === 'lila' && ownedIds.includes(p.id)) return false;
        // Landmarks: only if not already owned
        if (p.is_grossprojekt && ownedIds.includes(p.id)) return false;
        return true;
      })
    : [];

  return (
    <div className="space-y-4">
      {/* Assistant recommendation */}
      <AssistantPanel
        options={options}
        metricRanges={metricRanges}
        loading={evaluating}
        projects={projects}
        language={language}
        onHover={onHover}
        onBuy={onBuy}
        engineId={engineId}
        iterationsUsed={iterationsUsed}
        computeTimeMs={computeTimeMs}
        coinsAfterRoll={coinsAfterRoll}
      />

      {/* Manual buy fallback */}
      <div className="bg-machi-surface rounded-xl border border-machi-border p-4 space-y-2">
        <div className="text-xs text-machi-text-dim uppercase tracking-wider">{t('purchase.manual')}</div>
        <div className="flex flex-wrap gap-1.5">
          {affordable.map(p => {
            const name = p[`name_${language}` as 'name_de' | 'name_en'] ?? p.name_de;
            return (
              <button
                key={p.id}
                className="px-2 py-1 rounded-lg text-xs border border-machi-border bg-machi-bg hover:border-machi-accent transition-colors"
                onClick={() => onBuy(p.id)}
                onMouseEnter={() => onHover({ projectId: p.id, cost: p.cost })}
                onMouseLeave={() => onHover(null)}
              >
                <span className={cardTextClass(p.color)}>{name}</span>
                <span className="ml-1 text-machi-text-dim">{p.cost}c</span>
              </button>
            );
          })}
          <button
            className="px-2 py-1 rounded-lg text-xs border border-machi-border bg-machi-bg hover:border-machi-text-dim transition-colors text-machi-text-dim"
            onClick={() => onBuy(null)}
          >
            {t('btn.skip')}
          </button>
        </div>
      </div>
    </div>
  );
}

function cardTextClass(color?: string): string {
  switch (color) {
    case 'blau': return 'text-machi-blue';
    case 'rot': return 'text-machi-red';
    case 'grün': return 'text-machi-green';
    case 'lila': return 'text-machi-purple';
    case 'gelb': return 'text-machi-yellow';
    default: return 'text-machi-text';
  }
}

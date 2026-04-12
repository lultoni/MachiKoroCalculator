/** Purchase area — manual buy + assistant recommendation, wired to dice selection. */

import { useLocale } from '../i18n/useLocale';
import type { RankedOption, MetricRange, ProjectDef, RollLuckResponse } from '../api/types';
import { cardTextClass, categoryIconPath } from '../utils/cardDisplay';
import { CardTooltip } from './CardTooltip';
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
  rollLuck?: RollLuckResponse | null;
  luckLoading?: boolean;
}

export function PurchaseArea({
  options, metricRanges, evaluating,
  projects, language, coinsAfterRoll, ownedIds,
  onHover, onBuy, engineId, iterationsUsed, computeTimeMs,
  rollLuck, luckLoading,
}: Props) {
  const { t } = useLocale();

  // Manual buy: list affordable cards (exclude already-owned purples and landmarks)
  // Sorted by engine ranking when available (B14 fix)
  const affordable = coinsAfterRoll != null
    ? projects.projects.filter(p => {
        if (p.cost > coinsAfterRoll) return false;
        // Purple cards: max 1 per player
        if (p.color === 'lila' && ownedIds.includes(p.id)) return false;
        // Landmarks: only if not already owned
        if (p.is_grossprojekt && ownedIds.includes(p.id)) return false;
        return true;
      }).sort((a, b) => {
        const idxA = options.findIndex(o => o.projectId === a.id);
        const idxB = options.findIndex(o => o.projectId === b.id);
        // Cards not in engine results sort to end
        return (idxA === -1 ? 999 : idxA) - (idxB === -1 ? 999 : idxB);
      })
    : [];

  return (
    <div className="space-y-4">
      {/* Roll luck chip — shown once a roll is active, placeholders while loading */}
      {(rollLuck != null || luckLoading) && (
        <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-machi-surface border border-machi-border/50 text-xs">
          <span className="text-machi-text-dim">Roll luck:</span>
          {luckLoading || rollLuck == null ? (
            <span className="font-mono font-medium text-machi-text-dim">–</span>
          ) : (
            <>
              <span className={`font-mono font-medium ${
                rollLuck.luck > 0.02 ? 'text-machi-green' :
                rollLuck.luck < -0.02 ? 'text-red-400' : 'text-machi-text-dim'
              }`}>
                {rollLuck.luck >= 0 ? '+' : ''}{(rollLuck.luck * 100).toFixed(1)}%
              </span>
              <span className="text-machi-text-dim/50">
                (WR {(rollLuck.wrAfterActual * 100).toFixed(1)}% vs avg {(rollLuck.expectedWr * 100).toFixed(1)}%)
              </span>
            </>
          )}
        </div>
      )}

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
                <CardTooltip project={p} language={language}>
                  <span className={`inline-flex items-center ${cardTextClass(p.color)}`}>
                    {p.category && categoryIconPath(p.category) && (
                      <img src={categoryIconPath(p.category)} alt={p.category} className="w-3.5 h-3.5 mr-0.5 inline-block" />
                    )}
                    {name}
                  </span>
                </CardTooltip>
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

/** Assistant panel — shows top recommendation + expandable explanation. */

import { useState } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { RankedOption, MetricRange, ProjectDef } from '../api/types';
import { RankedList } from './RankedList';
import { ExplanationFactors } from './ExplanationFactors';

interface Props {
  options: RankedOption[];
  metricRanges: Record<string, MetricRange> | undefined;
  loading: boolean;
  projects: { byId: (id: string) => ProjectDef | undefined };
  language: 'de' | 'en';
  onHover: (card: { projectId: string; cost: number } | null) => void;
  onBuy: (projectId: string | null) => void;
  engineId?: string;
  iterationsUsed?: number;
  computeTimeMs?: number;
  coinsAfterRoll?: number | null;
}

export function AssistantPanel({ options, metricRanges, loading, projects, language, onHover, onBuy, engineId, iterationsUsed, computeTimeMs, coinsAfterRoll }: Props) {
  const { t } = useLocale();
  const [showAll, setShowAll] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  if (loading) {
    return (
      <div className="bg-machi-surface rounded-xl border border-machi-border p-6 text-center">
        <p className="text-machi-text-dim animate-pulse">{t('insights.analyzing')}</p>
      </div>
    );
  }

  if (options.length === 0) {
    return (
      <div className="bg-machi-surface rounded-xl border border-machi-border p-4 text-center text-machi-text-dim text-sm">
        {t('purchase.recommendation')}: —
      </div>
    );
  }

  // Deduplicate _wait_ entries
  const dedupedOptions = (() => {
    let seenWait = false;
    return options.filter(o => {
      if (o.projectId === '_wait_') {
        if (seenWait) return false;
        seenWait = true;
      }
      return true;
    });
  })();

  // Override affordable flag with post-roll coins when available
  const liveOptions = coinsAfterRoll != null
    ? dedupedOptions.map(o => {
        if (o.projectId === '_wait_') return o;
        const proj = projects.byId(o.projectId);
        const cost = proj?.cost ?? 0;
        return { ...o, affordable: coinsAfterRoll >= cost };
      })
    : dedupedOptions;

  // Show only affordable options — list updates dynamically per dice selection
  const visibleOptions = liveOptions.filter(o => o.affordable);

  const top = visibleOptions[0] ?? liveOptions[0];
  const isWait = top.projectId === '_wait_';
  const topProj = projects.byId(top.projectId);
  const topName = isWait
    ? (language === 'de' ? 'Sparen' : 'Save')
    : (topProj?.[`name_${language}` as 'name_de' | 'name_en'] ?? top.projectId);
  const winPct = (top.score * 100).toFixed(1);

  return (
    <div className="space-y-3">
      {/* Top recommendation */}
      <div
        className="bg-machi-surface rounded-xl border border-machi-border p-4 space-y-3"
        onMouseEnter={() => !isWait && topProj && onHover({ projectId: top.projectId, cost: topProj.cost })}
        onMouseLeave={() => onHover(null)}
      >
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="text-xs text-machi-text-dim uppercase tracking-wider mb-1">
              {t('purchase.recommendation')}
            </div>
            <div className={`text-lg font-bold ${isWait ? 'text-machi-text-dim' : cardTextClass(topProj?.color)}`}>
              {topName}
            </div>
            {top.summarySentence ? (
              <p className="text-sm text-machi-text-dim mt-1">{top.summarySentence}</p>
            ) : top.explanationFactors.length > 0 ? (
              <p className="text-sm text-machi-text-dim mt-1">{top.explanationFactors[0]}</p>
            ) : null}
          </div>
          <div className="text-right shrink-0">
            <div className="text-2xl font-bold text-machi-green">{winPct}%</div>
            <div className="text-xs text-machi-text-dim">{t('purchase.winRate')}</div>
            {engineId && (
              <div className="text-[10px] text-machi-text-dim/60 mt-1">
                {engineId} · {iterationsUsed ?? 0} iter · {computeTimeMs ?? 0}ms
              </div>
            )}
          </div>
        </div>

        {/* Explanation factors */}
        <ExplanationFactors
          factors={top.structuredFactors ?? []}
          fallback={top.explanationFactors.slice(1)}
        />

        {/* Action buttons */}
        <div className="flex gap-2">
          {isWait ? (
            <button
              className="flex-1 py-2 rounded-lg font-semibold bg-machi-accent text-machi-bg hover:brightness-110 active:scale-[0.98] transition-all"
              onClick={() => onBuy(null)}
            >
              {t('btn.skip')}
            </button>
          ) : (
            <>
              <button
                className="flex-1 py-2 rounded-lg font-semibold bg-machi-accent text-machi-bg hover:brightness-110 active:scale-[0.98] transition-all disabled:opacity-50"
                onClick={() => onBuy(top.projectId)}
                disabled={!top.affordable}
              >
                {t('coins.buy')} {topName}
                {topProj && <span className="ml-1 text-sm opacity-75">({topProj.cost}c)</span>}
              </button>
              <button
                className="px-3 py-2 rounded-lg text-sm text-machi-text-dim hover:text-machi-text border border-machi-border hover:border-machi-text-dim transition-all"
                onClick={() => onBuy(null)}
              >
                {t('btn.skip')}
              </button>
            </>
          )}
        </div>
      </div>

      {/* See all options toggle */}
      <button
        className="w-full text-sm text-machi-text-dim hover:text-machi-accent transition-colors py-1"
        onClick={() => setShowAll(!showAll)}
      >
        {t('purchase.seeAll')} {showAll ? '▾' : '▸'}
      </button>

      {/* Full ranked list */}
      {showAll && (
        <div className="bg-machi-surface rounded-xl border border-machi-border p-3">
          <RankedList
            options={liveOptions}
            metricRanges={metricRanges}
            projects={projects}
            language={language}
            onHover={onHover}
            onSelect={setSelectedId}
            selectedId={selectedId}
          />
        </div>
      )}
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

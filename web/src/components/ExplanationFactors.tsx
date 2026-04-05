/** Expandable structured explanation factors for a purchase option. */

import { useState } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { ExplanationFactor } from '../api/types';

interface Props {
  factors: ExplanationFactor[];
  /** Fall back to flat string list when structured data is absent. */
  fallback?: string[];
}

const CATEGORY_COLORS: Record<string, string> = {
  winRate:  'bg-emerald-500/20 text-emerald-400',
  income:   'bg-amber-500/20 text-amber-400',
  synergy:  'bg-cyan-500/20 text-cyan-400',
  risk:     'bg-red-500/20 text-red-400',
  tempo:    'bg-violet-500/20 text-violet-400',
  landmark: 'bg-yellow-500/20 text-yellow-400',
  cost:     'bg-slate-500/20 text-slate-400',
  coverage: 'bg-blue-500/20 text-blue-400',
  scarcity: 'bg-orange-500/20 text-orange-400',
};

export function ExplanationFactors({ factors, fallback }: Props) {
  const { t } = useLocale();
  const [expanded, setExpanded] = useState<number | null>(null);

  // Fall back to flat string list if no structured factors
  if (!factors || factors.length === 0) {
    if (!fallback || fallback.length === 0) return null;
    return (
      <ul className="text-xs text-machi-text-dim space-y-0.5 pl-3 list-disc">
        {fallback.map((f, i) => <li key={i}>{f}</li>)}
      </ul>
    );
  }

  return (
    <div className="space-y-1">
      {factors.map((f, i) => (
        <div key={i}>
          <button
            className="w-full flex items-center gap-2 text-left text-xs hover:bg-machi-border/30 rounded px-1.5 py-1 transition-colors"
            onClick={() => setExpanded(expanded === i ? null : i)}
          >
            {/* Category badge */}
            <span className={`shrink-0 min-w-16 px-1.5 py-0.5 rounded text-[10px] font-medium uppercase tracking-wider text-center ${CATEGORY_COLORS[f.category] ?? 'bg-slate-500/20 text-slate-400'}`}>
              {t(`factor.${f.category}`) || f.category}
            </span>

            {/* Weight bar + percentage */}
            <div className="shrink-0 flex items-center gap-1">
              <div className="w-10 h-1.5 rounded-full bg-machi-border/50 overflow-hidden">
                <div
                  className="h-full rounded-full bg-machi-accent/70"
                  style={{ width: `${Math.round(f.weight * 100)}%` }}
                />
              </div>
              <span className="text-[9px] text-machi-text-dim/50 w-6 text-right font-mono">
                {Math.round(f.weight * 100)}
              </span>
            </div>

            {/* Summary */}
            <span className="flex-1 text-machi-text-dim truncate">{f.summary}</span>

            {/* Chevron */}
            <span className="shrink-0 text-machi-text-dim/50 text-[10px]">
              {expanded === i ? '▾' : '▸'}
            </span>
          </button>

          {/* Expandable detail */}
          {expanded === i && f.detail && (
            <div className="ml-6 mt-0.5 mb-1 text-[11px] text-machi-text-dim/70 leading-relaxed px-1.5">
              {f.detail}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

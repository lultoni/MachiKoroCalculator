/** Expandable structured explanation factors for a purchase option. */

import React, { useState } from 'react';
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
    <div className="space-y-1" style={{ display: 'grid', gridTemplateColumns: 'auto auto 1fr auto', gap: '4px 8px', alignItems: 'center' }}>
      {factors.map((f, i) => (
        <React.Fragment key={i}>
          {/* Category badge */}
          <span
            className={`px-1.5 py-0.5 rounded text-[10px] font-medium uppercase tracking-wider text-center cursor-pointer whitespace-nowrap ${CATEGORY_COLORS[f.category] ?? 'bg-slate-500/20 text-slate-400'}`}
            onClick={() => setExpanded(expanded === i ? null : i)}
          >
            {t(`factor.${f.category}`) || f.category}
          </span>

          {/* Weight bar + percentage */}
          <div className="flex items-center gap-1">
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
          <span
            className="text-xs text-machi-text-dim truncate cursor-pointer"
            onClick={() => setExpanded(expanded === i ? null : i)}
          >
            {f.summary}
          </span>

          {/* Chevron */}
          <span
            className="text-machi-text-dim/50 text-[10px] cursor-pointer"
            onClick={() => setExpanded(expanded === i ? null : i)}
          >
            {expanded === i ? '▾' : '▸'}
          </span>

          {/* Expandable detail — spans all columns */}
          {expanded === i && f.detail && (
            <div className="text-[11px] text-machi-text-dim/70 leading-relaxed px-1.5 col-span-4 ml-2 mb-1">
              {f.detail}
            </div>
          )}
        </React.Fragment>
      ))}
    </div>
  );
}

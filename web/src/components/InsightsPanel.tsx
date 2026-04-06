/** Passive-turn insights panel — shows position, ETW bars, supply warnings, narratives. */

import { useLocale } from '../i18n/useLocale';
import type { InsightsResponse, ProjectDef } from '../api/types';
import { cardTextClass, categoryIconPath } from '../utils/cardDisplay';
import { CardTooltip } from './CardTooltip';

interface Props {
  insights: InsightsResponse | null;
  loading: boolean;
  projects: { byId: (id: string) => ProjectDef | undefined };
  language: 'de' | 'en';
}

export function InsightsPanel({ insights, loading, projects, language }: Props) {
  const { t } = useLocale();

  if (loading) {
    return (
      <div className="bg-machi-surface rounded-xl border border-machi-border p-4 text-center">
        <p className="text-machi-text-dim animate-pulse text-sm">{t('insights.analyzing')}</p>
      </div>
    );
  }

  if (!insights) return null;

  const sortedInsights = [...insights.playerInsights].sort((a, b) => {
    const ae = isFinite(a.etw) ? a.etw : 999;
    const be = isFinite(b.etw) ? b.etw : 999;
    return ae - be; // closest to winning first
  });
  const maxEtw = Math.max(...sortedInsights.map(p => isFinite(p.etw) ? p.etw : 0), 1);

  return (
    <div className="bg-machi-surface rounded-xl border border-machi-border p-4 space-y-4">
      {/* ETW bars */}
      <div className="space-y-2">
        <h4
          className="text-xs text-machi-text-dim uppercase tracking-wider cursor-help"
          title="Estimated Turns to Win — how many turns until a player can buy all remaining landmarks at their current income rate"
        >
          {t('insights.etw')}
        </h4>
        {sortedInsights.map((pi, i) => {
          const barWidth = isFinite(pi.etw) && maxEtw > 0 ? Math.min(100, (pi.etw / maxEtw) * 100) : 0;
          return (
            <div key={i} className="flex items-center gap-2 text-xs">
              <span className="w-16 truncate text-machi-text-dim">{pi.name}</span>
              <div className="flex-1 h-2 rounded-full bg-machi-border/50 overflow-hidden">
                <div
                  className="h-full rounded-full bg-machi-accent/60"
                  style={{ width: `${barWidth}%` }}
                />
              </div>
              <span className="w-12 text-right font-mono text-machi-text-dim">
                {isFinite(pi.etw) ? pi.etw.toFixed(1) : '∞'}
              </span>
            </div>
          );
        })}
      </div>

      {/* Tempo + Portfolio EV */}
      <div className="flex gap-4 text-xs">
        <div title="Turns ahead (+) or behind (-) compared to nearest opponent's ETW">
          <span className="text-machi-text-dim">{t('insights.tempo')}: </span>
          <span className={`font-mono font-medium ${insights.tempoAdvantage >= 0 ? 'text-machi-green' : 'text-machi-red'}`}>
            {insights.tempoAdvantage >= 0 ? '+' : ''}{insights.tempoAdvantage.toFixed(1)}
          </span>
        </div>
        <div title="Expected coins per full round from your current card portfolio">
          <span className="text-machi-text-dim">{t('insights.portfolio')}: </span>
          <span className="font-mono font-medium text-machi-text">
            {insights.portfolioEV.toFixed(2)}/round
          </span>
        </div>
      </div>

      {/* Supply warnings */}
      {insights.supplyWarnings.length > 0 && (
        <div className="space-y-1">
          <h4 className="text-xs text-machi-text-dim uppercase tracking-wider">{t('insights.supply')}</h4>
          {insights.supplyWarnings.map(w => {
            const proj = projects.byId(w.cardId);
            const name = proj?.[`name_${language}` as 'name_de' | 'name_en'] ?? w.cardId;
            return (
              <div key={w.cardId} className="text-xs flex justify-between">
                <CardTooltip project={proj} language={language}>
                  <span className={`inline-flex items-center ${cardTextClass(proj?.color)}`}>
                    {categoryIconPath(proj?.category) && (
                      <img src={categoryIconPath(proj?.category)} alt="" className="w-3 h-3 mr-0.5" />
                    )}
                    {name}
                  </span>
                </CardTooltip>
                <span className="text-machi-text-dim font-mono">{w.remaining} left</span>
              </div>
            );
          })}
        </div>
      )}

      {/* Narrative insights */}
      {insights.narrative && insights.narrative.length > 0 && (
        <div className="space-y-1.5">
          {insights.narrative.map((n, i) => (
            <div
              key={i}
              className={`text-xs px-2 py-1.5 rounded ${narrativeStyle(n.type)}`}
            >
              {n.text}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function narrativeStyle(type: string): string {
  switch (type) {
    case 'position': return 'bg-violet-500/10 text-violet-400';
    case 'supply':   return 'bg-amber-500/10 text-amber-400';
    case 'strategy': return 'bg-cyan-500/10 text-cyan-400';
    case 'landmark': return 'bg-yellow-500/10 text-yellow-400';
    default:         return 'bg-machi-border/20 text-machi-text-dim';
  }
}

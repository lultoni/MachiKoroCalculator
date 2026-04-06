/** Bürohaus modal — card swap interface with engine-ranked recommendations. */

import { useState } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { ProjectDef, BürohausRequest, PlayerState } from '../api/types';
import { cardTextClass, categoryIconPath } from '../utils/cardDisplay';
import { CardTooltip } from './CardTooltip';

interface SwapRanking {
  ownCardId: string | null;
  oppPlayerIndex: number;
  oppCardId: string | null;
  score: number;
  isDecline?: boolean;
}

interface Props {
  activePlayer: PlayerState;
  opponents: { index: number; player: PlayerState }[];
  projects: { byId: (id: string) => ProjectDef | undefined };
  language: 'de' | 'en';
  swapRankings: SwapRanking[] | null;
  onSwap: (req: BürohausRequest) => void;
  onClose: () => void;
}

const INELIGIBLE_COLORS = new Set(['lila', 'gelb']);
const LANDMARK_IDS = new Set(['bahnhof', 'einkaufszentrum', 'freizeitpark', 'funkturm']);

function isEligible(id: string, proj: ProjectDef | undefined): boolean {
  if (!proj) return false;
  if (LANDMARK_IDS.has(id)) return false;
  if (INELIGIBLE_COLORS.has(proj.color)) return false;
  return true;
}

export function BürohausModal({ activePlayer, opponents, projects, language, swapRankings, onSwap, onClose }: Props) {
  const { t } = useLocale();

  // Pre-select engine recommendation
  const topRec = swapRankings?.find(r => !r.isDecline);
  const [ownCard, setOwnCard] = useState<string | null>(topRec?.ownCardId ?? null);
  const [oppIndex, setOppIndex] = useState<number>(topRec?.oppPlayerIndex ?? opponents[0]?.index ?? 0);
  const [oppCard, setOppCard] = useState<string | null>(topRec?.oppCardId ?? null);

  const getName = (id: string) => {
    const p = projects.byId(id);
    return p?.[`name_${language}` as 'name_de' | 'name_en'] ?? p?.name_de ?? id;
  };

  // Deduplicate cards
  const ownEligible = uniqueCardIds(activePlayer.ownedIds.filter(id => isEligible(id, projects.byId(id))));
  const selectedOpp = opponents.find(o => o.index === oppIndex);
  const oppEligible = selectedOpp
    ? uniqueCardIds(selectedOpp.player.ownedIds.filter(id => isEligible(id, projects.byId(id))))
    : [];

  const handleSwap = () => {
    if (!ownCard || !oppCard) return;
    onSwap({ ownCardId: ownCard, oppPlayerIndex: oppIndex, oppCardId: oppCard });
  };

  const handleDecline = () => {
    onSwap({ decline: true });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-machi-surface rounded-2xl border border-machi-border p-6 max-w-lg w-full mx-4 space-y-5 shadow-2xl"
        onClick={e => e.stopPropagation()}>
        <h2 className="text-lg font-bold text-machi-text">{t('bürohaus.title')}</h2>

        {/* Recommendation badge */}
        {topRec && (
          <div className="bg-machi-accent/10 border border-machi-accent/30 rounded-lg px-3 py-2 text-sm">
            <span className="text-machi-accent font-medium">{t('bürohaus.recommended')}: </span>
            <span className="text-machi-text">
              <span className={`inline-flex items-center ${cardTextClass(projects.byId(topRec.ownCardId ?? '')?.color)}`}>
                {topRec.ownCardId ? getName(topRec.ownCardId) : '—'}
              </span>
              {' ↔ '}
              <span className={`inline-flex items-center ${cardTextClass(projects.byId(topRec.oppCardId ?? '')?.color)}`}>
                {topRec.oppCardId ? getName(topRec.oppCardId) : '—'}
              </span>
            </span>
            <span className="ml-2 text-machi-text-dim text-xs">({(topRec.score * 100).toFixed(1)}%)</span>
          </div>
        )}

        {/* Your cards */}
        <div>
          <h3 className="text-sm text-machi-text-dim mb-2">{t('bürohaus.yourCards')}</h3>
          <div className="flex flex-wrap gap-1.5">
            {ownEligible.map(id => {
              const proj = projects.byId(id);
              const selected = ownCard === id;
              return (
                <CardTooltip key={id} project={proj} language={language}>
                  <button
                    className={`px-2 py-1 rounded-lg text-xs border transition-all ${
                      selected
                        ? 'border-machi-accent bg-machi-accent/10 ring-1 ring-machi-accent/50'
                        : 'border-machi-border hover:border-machi-text-dim'
                    } ${cardColorClass(proj?.color)}`}
                    onClick={() => setOwnCard(id)}
                  >
                    <span className="inline-flex items-center">
                      {categoryIconPath(proj?.category) && (
                        <img src={categoryIconPath(proj?.category)} alt="" className="w-3 h-3 mr-0.5" />
                      )}
                      {getName(id)}
                    </span>
                  </button>
                </CardTooltip>
              );
            })}
            {ownEligible.length === 0 && (
              <span className="text-xs text-machi-text-dim">{t('bürohaus.notEligible')}</span>
            )}
          </div>
        </div>

        {/* Opponent selection (if multi-opponent) */}
        {opponents.length > 1 && (
          <div className="flex gap-2">
            {opponents.map(o => (
              <button
                key={o.index}
                className={`px-3 py-1 rounded-lg text-sm transition-all ${
                  oppIndex === o.index
                    ? 'bg-machi-accent text-machi-bg'
                    : 'text-machi-text-dim border border-machi-border hover:border-machi-text-dim'
                }`}
                onClick={() => { setOppIndex(o.index); setOppCard(null); }}
              >
                {o.player.name}
              </button>
            ))}
          </div>
        )}

        {/* Opponent cards */}
        {selectedOpp && (
          <div>
            <h3 className="text-sm text-machi-text-dim mb-2">
              {t('bürohaus.opponentCards', { name: selectedOpp.player.name })}
            </h3>
            <div className="flex flex-wrap gap-1.5">
              {oppEligible.map(id => {
                const proj = projects.byId(id);
                const selected = oppCard === id;
                return (
                  <CardTooltip key={id} project={proj} language={language}>
                    <button
                      className={`px-2 py-1 rounded-lg text-xs border transition-all ${
                        selected
                          ? 'border-machi-accent bg-machi-accent/10 ring-1 ring-machi-accent/50'
                          : 'border-machi-border hover:border-machi-text-dim'
                      } ${cardColorClass(proj?.color)}`}
                      onClick={() => setOppCard(id)}
                    >
                      <span className="inline-flex items-center">
                        {categoryIconPath(proj?.category) && (
                          <img src={categoryIconPath(proj?.category)} alt="" className="w-3 h-3 mr-0.5" />
                        )}
                        {getName(id)}
                      </span>
                    </button>
                  </CardTooltip>
                );
              })}
              {oppEligible.length === 0 && (
                <span className="text-xs text-machi-text-dim">{t('bürohaus.notEligible')}</span>
              )}
            </div>
          </div>
        )}

        {/* Selection label */}
        {ownCard && oppCard && (
          <div className="text-sm text-center text-machi-text">
            {t('bürohaus.swapLabel', { own: getName(ownCard), opp: selectedOpp?.player.name ?? '', card: getName(oppCard) })}
          </div>
        )}

        {/* Action buttons */}
        <div className="flex gap-3">
          <button
            className="flex-1 py-2 rounded-lg font-semibold bg-machi-accent text-machi-bg hover:brightness-110 transition-all disabled:opacity-50"
            onClick={handleSwap}
            disabled={!ownCard || !oppCard}
          >
            {t('btn.swap')}
          </button>
          <button
            className="flex-1 py-2 rounded-lg font-semibold border border-machi-border text-machi-text-dim hover:text-machi-text hover:border-machi-text-dim transition-all"
            onClick={handleDecline}
          >
            {t('btn.decline')}
          </button>
        </div>
      </div>
    </div>
  );
}

function uniqueCardIds(ids: string[]): string[] {
  return [...new Set(ids)];
}

function cardColorClass(color?: string): string {
  switch (color) {
    case 'blau':  return 'bg-machi-blue/10 text-machi-blue';
    case 'rot':   return 'bg-machi-red/10 text-machi-red';
    case 'grün':  return 'bg-machi-green/10 text-machi-green';
    case 'lila':  return 'bg-machi-purple/10 text-machi-purple';
    default:      return 'bg-gray-500/10 text-gray-400';
  }
}

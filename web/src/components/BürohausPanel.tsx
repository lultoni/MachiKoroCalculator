/** Bürohaus inline panel — card swap selector, renders between CoinFlow and PurchaseArea. */

import { useState, useEffect } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { ProjectDef, BürohausRequest, PlayerState } from '../api/types';
import { categoryIconPath } from '../utils/cardDisplay';
import { CardTooltip } from './CardTooltip';

interface Props {
  activePlayer: PlayerState;
  opponents: { index: number; player: PlayerState }[];
  projects: { byId: (id: string) => ProjectDef | undefined };
  language: 'de' | 'en';
  pendingSwap: BürohausRequest | null;
  onSwapChange: (req: BürohausRequest | null) => void;
}

const INELIGIBLE_COLORS = new Set(['lila', 'gelb']);
const LANDMARK_IDS = new Set(['bahnhof', 'einkaufszentrum', 'freizeitpark', 'funkturm']);

function isEligible(id: string, proj: ProjectDef | undefined): boolean {
  if (!proj) return false;
  if (LANDMARK_IDS.has(id)) return false;
  if (INELIGIBLE_COLORS.has(proj.color)) return false;
  return true;
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

export function BürohausPanel({ activePlayer, opponents, projects, language, pendingSwap, onSwapChange }: Props) {
  const { t } = useLocale();

  // If already declined, show compact "declined" state
  const isDeclined = pendingSwap?.decline === true;

  const [ownCard, setOwnCard] = useState<string | null>(pendingSwap?.ownCardId ?? null);
  const [oppIndex, setOppIndex] = useState<number>(pendingSwap?.oppPlayerIndex ?? opponents[0]?.index ?? 0);
  const [oppCard, setOppCard] = useState<string | null>(pendingSwap?.oppCardId ?? null);

  // Sync internal state when pendingSwap is reset externally (e.g. dice change)
  useEffect(() => {
    if (!pendingSwap) {
      setOwnCard(null);
      setOppCard(null);
    }
  }, [pendingSwap]);

  const getName = (id: string) => {
    const p = projects.byId(id);
    return p?.[`name_${language}` as 'name_de' | 'name_en'] ?? p?.name_de ?? id;
  };

  const ownEligible = uniqueCardIds(activePlayer.ownedIds.filter(id => isEligible(id, projects.byId(id))));
  const selectedOpp = opponents.find(o => o.index === oppIndex);
  const oppEligible = selectedOpp
    ? uniqueCardIds(selectedOpp.player.ownedIds.filter(id => isEligible(id, projects.byId(id))))
    : [];

  const handleConfirmSwap = () => {
    if (!ownCard || !oppCard) return;
    onSwapChange({ ownCardId: ownCard, oppPlayerIndex: oppIndex, oppCardId: oppCard });
  };

  const handleDecline = () => {
    onSwapChange({ decline: true });
  };

  const handleReopen = () => {
    onSwapChange(null);
    setOwnCard(null);
    setOppCard(null);
  };

  // Compact declined view
  if (isDeclined) {
    return (
      <div className="bg-machi-surface rounded-xl border border-machi-border p-3 flex items-center justify-between">
        <span className="text-sm text-machi-text-dim">{t('bürohaus.declined')}</span>
        <button
          className="text-xs text-machi-accent hover:underline"
          onClick={handleReopen}
        >
          {t('bürohaus.clickToChange')}
        </button>
      </div>
    );
  }

  // Check if swap is already confirmed (non-null, non-decline, has cards)
  const isConfirmed = pendingSwap && !pendingSwap.decline && pendingSwap.ownCardId && pendingSwap.oppCardId;

  return (
    <div className="bg-machi-surface rounded-xl border border-machi-purple/30 p-4 space-y-3">
      <div className="flex items-center justify-between">
        <h4 className="text-sm font-semibold text-machi-purple">{t('bürohaus.title')}</h4>
        {isConfirmed && (
          <span className="text-xs text-machi-green font-medium">
            {getName(pendingSwap!.ownCardId!)} ↔ {getName(pendingSwap!.oppCardId!)}
          </span>
        )}
      </div>

      {/* Your cards */}
      <div>
        <div className="text-xs text-machi-text-dim mb-1.5">{t('bürohaus.yourCards')}</div>
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

      {/* Opponent selection */}
      {opponents.length > 1 && (
        <div className="flex gap-2">
          {opponents.map(o => (
            <button
              key={o.index}
              className={`px-3 py-1 rounded-lg text-xs transition-all ${
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
          <div className="text-xs text-machi-text-dim mb-1.5">
            {t('bürohaus.opponentCards', { name: selectedOpp.player.name })}
          </div>
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

      {/* Swap summary */}
      {ownCard && oppCard && (
        <div className="text-xs text-center text-machi-text">
          {t('bürohaus.swapLabel', { own: getName(ownCard), opp: selectedOpp?.player.name ?? '', card: getName(oppCard) })}
        </div>
      )}

      {/* Action buttons */}
      <div className="flex gap-2">
        <button
          className="flex-1 py-1.5 rounded-lg text-sm font-semibold bg-machi-purple/20 text-machi-purple hover:bg-machi-purple/30 transition-all disabled:opacity-40"
          onClick={handleConfirmSwap}
          disabled={!ownCard || !oppCard}
        >
          {t('btn.swap')}
        </button>
        <button
          className="px-3 py-1.5 rounded-lg text-sm text-machi-text-dim border border-machi-border hover:text-machi-text hover:border-machi-text-dim transition-all"
          onClick={handleDecline}
        >
          {t('btn.decline')}
        </button>
      </div>
    </div>
  );
}

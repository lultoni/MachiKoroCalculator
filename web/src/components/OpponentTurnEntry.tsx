/** Opponent turn entry — simplified dice + purchase selector for tracking opponent moves. */

import { useState, useCallback, useEffect } from 'react';
import { useLocale } from '../i18n/useLocale';
import { DiceInterface } from './DiceInterface';
import type { ProjectDef, ApplyTurnRequest, BürohausRequest, GameStateJson, PlayerState } from '../api/types';
import { cardTextClass, categoryIconPath } from '../utils/cardDisplay';
import { CardTooltip } from './CardTooltip';
import { BürohausPanel } from './BürohausPanel';
import { BürohausModal } from './BürohausModal';
import * as api from '../api/client';

interface Props {
  opponentName: string;
  canUse2d6: boolean;
  projects: { projects: ProjectDef[]; byId: (id: string) => ProjectDef | undefined };
  language: 'de' | 'en';
  coinsAvailable: number;
  onConfirm: (req: ApplyTurnRequest, bürohausSwap?: BürohausRequest) => void;
  loading: boolean;
  state: GameStateJson;
  activePlayerIndex: number;
  players: PlayerState[];
  ownedIds: string[];
  showBürohausPopupSetting: boolean;
}

export function OpponentTurnEntry({ opponentName, canUse2d6, projects, language, coinsAvailable, onConfirm, loading, state, activePlayerIndex, players, ownedIds, showBürohausPopupSetting }: Props) {
  const { t } = useLocale();
  const [die1, setDie1] = useState<number | null>(null);
  const [die2, setDie2] = useState<number | null>(null);
  const [diceCount, setDiceCount] = useState<1 | 2>(1);
  const [boughtId, setBoughtId] = useState<string | null>(null);
  const [coinDeltas, setCoinDeltas] = useState<number[] | null>(null);
  const [pendingBürohausSwap, setPendingBürohausSwap] = useState<BürohausRequest | null>(null);
  const [showBürohausPopup, setShowBürohausPopup] = useState(false);

  const rollTotal = die1 != null ? (diceCount === 2 && die2 != null ? die1 + die2 : die1) : 0;
  const isDoubles = diceCount === 2 && die1 != null && die2 != null && die1 === die2;
  const ownsBürohaus = ownedIds.includes('bürohaus');
  const showBürohausPanelInline = rollTotal === 6 && ownsBürohaus;

  // Fetch coin deltas when roll is selected
  useEffect(() => {
    if (rollTotal <= 0) {
      setCoinDeltas(null);
      return;
    }
    api.previewRoll(state, activePlayerIndex, rollTotal)
      .then(res => setCoinDeltas(res.coinDeltas))
      .catch(() => setCoinDeltas(null));
  }, [rollTotal, state, activePlayerIndex]);

  const handleRollSelect = useCallback((count: 1 | 2, d1: number, d2: number | null) => {
    setDiceCount(count);
    setDie1(d1 > 0 ? d1 : null);
    setDie2(d2 != null && d2 > 0 ? d2 : null);
    // Reset pending swap when roll changes away from 6
    const total = d1 > 0 ? (count === 2 && d2 != null && d2 > 0 ? d1 + d2 : d1) : 0;
    if (total !== 6) {
      setPendingBürohausSwap(null);
      setShowBürohausPopup(false);
    } else if (total === 6 && ownsBürohaus && showBürohausPopupSetting) {
      setShowBürohausPopup(true);
    }
  }, [ownsBürohaus, showBürohausPopupSetting]);

  const handleConfirm = () => {
    if (rollTotal === 0) return;
    onConfirm({
      roll: rollTotal,
      boughtId,
      isDoubles,
      diceCount,
    }, pendingBürohausSwap ?? undefined);
    // Reset
    setDie1(null);
    setDie2(null);
    setBoughtId(null);
    setCoinDeltas(null);
    setPendingBürohausSwap(null);
    setShowBürohausPopup(false);
  };

  // Use post-roll coins for affordability when available
  const opponentCoinsAfterRoll = coinDeltas != null
    ? coinsAvailable + (coinDeltas[activePlayerIndex] ?? 0)
    : coinsAvailable;
  const affordable = projects.projects.filter(p => {
    if (p.is_grossprojekt) return false;
    if (p.cost > opponentCoinsAfterRoll) return false;
    // Purple cards: max 1 per player
    if (p.color === 'lila' && ownedIds.includes(p.id)) return false;
    return true;
  });

  return (
    <div className="space-y-4">
      <div className="text-center text-sm font-medium text-machi-text-dim">
        {t('turn.opponent', { name: opponentName })}
      </div>

      {/* Dice */}
      <DiceInterface
        canUse2d6={canUse2d6}
        onRollSelect={handleRollSelect}
        selectedDie1={die1}
        selectedDie2={die2}
        selectedDiceCount={diceCount}
      />

      {/* Coin flow from this roll */}
      {rollTotal > 0 && coinDeltas && (
        <div className="grid grid-cols-2 gap-2 text-sm">
          {players.map((p, i) => {
            const delta = coinDeltas[i] ?? 0;
            return (
              <div key={i} className="flex items-center justify-between px-2 py-1 rounded bg-machi-bg/50">
                <span className="text-machi-text-dim text-xs">{p.name}</span>
                <span className={`font-mono text-xs font-medium ${
                  delta > 0 ? 'text-machi-green' : delta < 0 ? 'text-machi-red' : 'text-machi-text-dim'
                }`}>
                  {delta > 0 ? `+${delta}` : delta < 0 ? String(delta) : '±0'}
                </span>
              </div>
            );
          })}
        </div>
      )}

      {/* Bürohaus inline panel — between coin flow and purchase */}
      {showBürohausPanelInline && (
        <BürohausPanel
          activePlayer={players[activePlayerIndex]}
          opponents={players
            .map((p, i) => ({ index: i, player: p }))
            .filter(o => o.index !== activePlayerIndex)}
          projects={projects}
          language={language}
          pendingSwap={pendingBürohausSwap}
          onSwapChange={setPendingBürohausSwap}
        />
      )}

      {/* Purchase selector — card buttons instead of dropdown for better UX */}
      {rollTotal > 0 && (
        <div className="space-y-2">
          <div className="text-xs text-machi-text-dim uppercase tracking-wider">{t('purchase.manual')}</div>
          <div className="flex flex-wrap gap-1.5">
            <button
              className={`px-2 py-1 rounded-lg text-xs border transition-colors ${
                boughtId === null
                  ? 'border-machi-accent bg-machi-accent/10 text-machi-accent'
                  : 'border-machi-border bg-machi-bg text-machi-text-dim hover:border-machi-text-dim'
              }`}
              onClick={() => setBoughtId(null)}
            >
              {t('btn.skip')}
            </button>
            {affordable.map(p => {
              const name = p[`name_${language}` as 'name_de' | 'name_en'] ?? p.name_de;
              const isSelected = boughtId === p.id;
              return (
                <button
                  key={p.id}
                  className={`px-2 py-1 rounded-lg text-xs border transition-colors ${
                    isSelected
                      ? 'border-machi-accent bg-machi-accent/10'
                      : 'border-machi-border bg-machi-bg hover:border-machi-text-dim'
                  }`}
                  onClick={() => setBoughtId(p.id)}
                >
                  <CardTooltip project={p} language={language}>
                    <span className={`inline-flex items-center ${cardTextClass(p.color)}`}>
                      {categoryIconPath(p.category) && (
                        <img src={categoryIconPath(p.category)} alt="" className="w-3.5 h-3.5 mr-0.5" />
                      )}
                      {name}
                    </span>
                  </CardTooltip>
                  <span className="ml-1 text-machi-text-dim">{p.cost}c</span>
                </button>
              );
            })}
          </div>

          {/* Opponent coins after roll + purchase summary */}
          {coinDeltas && (
            <div className="text-xs text-machi-text-dim">
              {(() => {
                const coinsAfter = coinsAvailable + (coinDeltas[activePlayerIndex] ?? 0);
                const purchaseCost = boughtId ? (projects.byId(boughtId)?.cost ?? 0) : 0;
                const final_ = coinsAfter - purchaseCost;
                return (
                  <span>
                    {players[activePlayerIndex].name}: {coinsAvailable}c → {coinsAfter}c
                    {boughtId && <span className="text-machi-red"> − {purchaseCost}c</span>}
                    {boughtId && <span> = {final_}c</span>}
                  </span>
                );
              })()}
            </div>
          )}

          <button
            className="w-full py-2 rounded-lg font-semibold bg-machi-accent text-machi-bg hover:brightness-110 active:scale-[0.98] transition-all disabled:opacity-50"
            onClick={handleConfirm}
            disabled={loading}
          >
            {t('btn.confirmTurn')}
          </button>
        </div>
      )}

      {/* Bürohaus popup modal (optional, settings-controlled) */}
      {showBürohausPopup && (
        <BürohausModal
          activePlayer={players[activePlayerIndex]}
          opponents={players
            .map((p, i) => ({ index: i, player: p }))
            .filter(o => o.index !== activePlayerIndex)}
          projects={projects}
          language={language}
          swapRankings={null}
          onSwap={(req) => { setPendingBürohausSwap(req); setShowBürohausPopup(false); }}
          onClose={() => setShowBürohausPopup(false)}
        />
      )}
    </div>
  );
}

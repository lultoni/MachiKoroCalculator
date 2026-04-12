/** Opponent turn entry — simplified dice + purchase selector for tracking opponent moves. */

import { useState, useCallback, useEffect, useRef } from 'react';
import { useLocale } from '../i18n/useLocale';
import { DiceInterface } from './DiceInterface';
import type { ProjectDef, ApplyTurnRequest, BürohausRequest, GameStateJson, PlayerState, RollLuckResponse } from '../api/types';
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
  onCoinDeltasChange?: (deltas: number[] | null) => void;
  luckUseMc?: boolean;
}

export function OpponentTurnEntry({ opponentName, canUse2d6, projects, language, coinsAvailable, onConfirm, loading, state, activePlayerIndex, players, ownedIds, showBürohausPopupSetting, onCoinDeltasChange, luckUseMc }: Props) {
  const { t } = useLocale();
  const [dice, setDice] = useState<{ die1: number | null; die2: number | null; count: 1 | 2 }>({ die1: null, die2: null, count: 1 });
  const [boughtId, setBoughtId] = useState<string | null>(null);
  const [coinDeltas, setCoinDeltas] = useState<number[] | null>(null);
  const [rollLuck, setRollLuck] = useState<RollLuckResponse | null>(null);
  const [luckLoading, setLuckLoading] = useState(false);
  const [pendingBürohausSwap, setPendingBürohausSwap] = useState<BürohausRequest | null>(null);
  const [showBürohausPopup, setShowBürohausPopup] = useState(false);

  const { die1, die2, count: diceCount } = dice;
  const rollTotal = die1 != null ? (diceCount === 2 && die2 != null ? die1 + die2 : die1) : 0;
  const isDoubles = diceCount === 2 && die1 != null && die2 != null && die1 === die2;

  const stateRef = useRef(state);
  stateRef.current = state;
  const onCoinDeltasChangeRef = useRef(onCoinDeltasChange);
  onCoinDeltasChangeRef.current = onCoinDeltasChange;
  const ownsBürohaus = ownedIds.includes('bürohaus');
  const showBürohausPanelInline = rollTotal === 6 && ownsBürohaus;

  // Fetch coin deltas and roll luck when roll is selected
  useEffect(() => {
    if (rollTotal <= 0) {
      setCoinDeltas(null);
      setRollLuck(null);
      setLuckLoading(false);
      onCoinDeltasChangeRef.current?.(null);
      return;
    }
    let cancelled = false;
    api.previewRoll(stateRef.current, activePlayerIndex, rollTotal)
      .then(res => { if (!cancelled) { setCoinDeltas(res.coinDeltas); onCoinDeltasChangeRef.current?.(res.coinDeltas); } })
      .catch(() => { if (!cancelled) { setCoinDeltas(null); onCoinDeltasChangeRef.current?.(null); } });
    // Fetch roll luck only when roll is complete (1d6 always, 2d6 only when both dice set)
    const rollComplete = diceCount === 1 || die2 != null;
    if (rollComplete) {
      setLuckLoading(true);
      api.getRollLuck(rollTotal, diceCount as 1 | 2, activePlayerIndex, luckUseMc ?? false)
        .then(res => { if (!cancelled) { setRollLuck(res); setLuckLoading(false); } })
        .catch(() => { if (!cancelled) setLuckLoading(false); });
    }
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rollTotal, activePlayerIndex, diceCount, die2, luckUseMc]);

  const handleRollSelect = useCallback((count: 1 | 2, d1: number, d2: number | null) => {
    const newDie1 = d1 > 0 ? d1 : null;
    const newDie2 = d2 != null && d2 > 0 ? d2 : null;
    setDice({ die1: newDie1, die2: newDie2, count });
    setLuckLoading(true);
    // Reset pending swap when roll changes away from 6
    const total = newDie1 != null ? (count === 2 && newDie2 != null ? newDie1 + newDie2 : newDie1) : 0;
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
    setDice({ die1: null, die2: null, count: 1 });
    setBoughtId(null);
    setCoinDeltas(null);
    setRollLuck(null);
    setLuckLoading(false);
    onCoinDeltasChange?.(null);
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

      {/* Roll luck chip — shown once a roll is active, placeholders while loading */}
      {(rollLuck != null || luckLoading) && rollTotal > 0 && (
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

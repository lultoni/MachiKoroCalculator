/** Opponent turn entry — simplified dice + purchase selector for tracking opponent moves. */

import { useState, useCallback, useEffect } from 'react';
import { useLocale } from '../i18n/useLocale';
import { DiceInterface } from './DiceInterface';
import type { ProjectDef, ApplyTurnRequest, GameStateJson, PlayerState } from '../api/types';
import * as api from '../api/client';

interface Props {
  opponentName: string;
  canUse2d6: boolean;
  projects: { projects: ProjectDef[]; byId: (id: string) => ProjectDef | undefined };
  language: 'de' | 'en';
  coinsAvailable: number;
  onConfirm: (req: ApplyTurnRequest) => void;
  loading: boolean;
  state: GameStateJson;
  activePlayerIndex: number;
  players: PlayerState[];
}

export function OpponentTurnEntry({ opponentName, canUse2d6, projects, language, coinsAvailable, onConfirm, loading, state, activePlayerIndex, players }: Props) {
  const { t } = useLocale();
  const [die1, setDie1] = useState<number | null>(null);
  const [die2, setDie2] = useState<number | null>(null);
  const [diceCount, setDiceCount] = useState<1 | 2>(1);
  const [boughtId, setBoughtId] = useState<string | null>(null);
  const [coinDeltas, setCoinDeltas] = useState<number[] | null>(null);

  const rollTotal = die1 != null ? (diceCount === 2 && die2 != null ? die1 + die2 : die1) : 0;
  const isDoubles = diceCount === 2 && die1 != null && die2 != null && die1 === die2;

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
  }, []);

  const handleConfirm = () => {
    if (rollTotal === 0) return;
    onConfirm({
      roll: rollTotal,
      boughtId,
      isDoubles,
      diceCount,
    });
    // Reset
    setDie1(null);
    setDie2(null);
    setBoughtId(null);
    setCoinDeltas(null);
  };

  const affordable = projects.projects.filter(p => !p.is_grossprojekt && p.cost <= coinsAvailable);

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

      {/* Purchase selector */}
      {rollTotal > 0 && (
        <div className="space-y-2">
          <select
            className="w-full bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-sm text-machi-text focus:outline-none focus:border-machi-accent"
            value={boughtId ?? ''}
            onChange={e => setBoughtId(e.target.value || null)}
          >
            <option value="">{t('btn.skip')}</option>
            {affordable.map(p => {
              const name = p[`name_${language}` as 'name_de' | 'name_en'] ?? p.name_de;
              return (
                <option key={p.id} value={p.id}>
                  {name} ({p.cost}c)
                </option>
              );
            })}
          </select>

          <button
            className="w-full py-2 rounded-lg font-semibold bg-machi-accent text-machi-bg hover:brightness-110 active:scale-[0.98] transition-all disabled:opacity-50"
            onClick={handleConfirm}
            disabled={loading}
          >
            {t('btn.confirmTurn')}
          </button>
        </div>
      )}
    </div>
  );
}

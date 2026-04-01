import { useState } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { H2hGameLog, H2hTurnLog } from '../api/types';

interface Props {
  game: H2hGameLog;
  engines: string[];
  onBack: () => void;
}

export function H2hGameReplay({ game, engines, onBack }: Props) {
  const { t } = useLocale();
  const [turnIdx, setTurnIdx] = useState(0);

  const turn = game.turns[turnIdx] as H2hTurnLog | undefined;
  const totalTurns = game.turns.length;

  // Compute running coin totals per player
  const playerCoins: number[][] = [];
  {
    const n = engines.length;
    const coins = Array(n).fill(3); // starting coins
    for (const tn of game.turns) {
      for (let i = 0; i < n; i++) {
        coins[i] = Math.max(0, coins[i] + (tn.coinDeltas?.[i] ?? 0));
      }
      if (tn.purchasedCardId) {
        // purchase cost already reflected in coinsAfterPurchase for active player
        coins[tn.playerIndex] = tn.coinsAfterPurchase;
      }
      playerCoins.push([...coins]);
    }
  }

  return (
    <div className="min-h-screen bg-machi-bg text-machi-text p-6">
      <div className="max-w-4xl mx-auto">
        {/* Header */}
        <div className="flex items-center gap-4 mb-6">
          <button
            onClick={onBack}
            className="text-machi-text-dim hover:text-machi-text transition"
          >
            ← {t('btn.back')}
          </button>
          <h1 className="text-xl font-bold">
            {t('h2h.game')} #{game.gameIndex + 1}
            {game.timeoutWin && <span className="ml-2 text-sm text-machi-text-dim">({t('h2h.timeout')})</span>}
          </h1>
          <span className="ml-auto text-sm text-machi-text-dim">
            P{game.winnerIndex + 1} {t('h2h.won')} · {game.totalTurns} {t('h2h.turns')}
          </span>
        </div>

        {/* Turn Navigation */}
        <div className="flex items-center gap-3 mb-4">
          <button
            onClick={() => setTurnIdx(0)}
            disabled={turnIdx === 0}
            className="px-3 py-1.5 bg-machi-surface border border-machi-border rounded-lg text-sm
                       disabled:opacity-30 hover:bg-machi-bg transition"
          >
            ⏮
          </button>
          <button
            onClick={() => setTurnIdx(i => Math.max(0, i - 1))}
            disabled={turnIdx === 0}
            className="px-3 py-1.5 bg-machi-surface border border-machi-border rounded-lg text-sm
                       disabled:opacity-30 hover:bg-machi-bg transition"
          >
            ◀
          </button>
          <span className="text-sm font-mono flex-1 text-center">
            {t('h2h.turnN', { n: String(turnIdx + 1) })} / {totalTurns}
          </span>
          <button
            onClick={() => setTurnIdx(i => Math.min(totalTurns - 1, i + 1))}
            disabled={turnIdx >= totalTurns - 1}
            className="px-3 py-1.5 bg-machi-surface border border-machi-border rounded-lg text-sm
                       disabled:opacity-30 hover:bg-machi-bg transition"
          >
            ▶
          </button>
          <button
            onClick={() => setTurnIdx(totalTurns - 1)}
            disabled={turnIdx >= totalTurns - 1}
            className="px-3 py-1.5 bg-machi-surface border border-machi-border rounded-lg text-sm
                       disabled:opacity-30 hover:bg-machi-bg transition"
          >
            ⏭
          </button>
        </div>

        {/* Turn Detail */}
        {turn && (
          <div className="bg-machi-surface rounded-xl p-5 border border-machi-border mb-4">
            <div className="flex items-center gap-3 mb-3">
              <span className={`inline-block w-3 h-3 rounded-full ${turn.playerIndex === 0 ? 'bg-machi-accent' : 'bg-machi-purple'}`} />
              <span className="font-semibold">
                P{turn.playerIndex + 1} ({engines[turn.playerIndex]})
              </span>
              <span className="text-machi-text-dim text-sm ml-auto">
                {turn.evaluateTimeMs}ms
              </span>
            </div>

            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
              {/* Dice */}
              <div className="bg-machi-bg rounded-lg p-3">
                <div className="text-machi-text-dim text-xs mb-1">{t('h2h.dice')}</div>
                <div className="font-mono text-lg">
                  {turn.diceCount}d6 → {turn.roll}
                  {turn.isDoubles && <span className="ml-1 text-machi-yellow text-xs">D</span>}
                </div>
                {turn.funkturmRerolled && (
                  <div className="text-xs text-machi-accent mt-0.5">Funkturm ↻</div>
                )}
              </div>

              {/* Income */}
              <div className="bg-machi-bg rounded-lg p-3">
                <div className="text-machi-text-dim text-xs mb-1">{t('h2h.income')}</div>
                <div className="font-mono">
                  {engines.map((_, i) => (
                    <span key={i} className={`mr-2 ${
                      (turn.coinDeltas?.[i] ?? 0) > 0 ? 'text-green-400' :
                      (turn.coinDeltas?.[i] ?? 0) < 0 ? 'text-red-400' : 'text-machi-text-dim'
                    }`}>
                      P{i + 1}: {(turn.coinDeltas?.[i] ?? 0) >= 0 ? '+' : ''}{turn.coinDeltas?.[i] ?? 0}
                    </span>
                  ))}
                </div>
              </div>

              {/* Purchase */}
              <div className="bg-machi-bg rounded-lg p-3">
                <div className="text-machi-text-dim text-xs mb-1">{t('h2h.purchase')}</div>
                <div className="font-mono">
                  {turn.purchasedCardId ?? t('h2h.save')}
                </div>
                <div className="text-xs text-machi-text-dim mt-0.5">
                  WR: {(turn.purchaseWinRate * 100).toFixed(1)}%
                </div>
              </div>

              {/* Coins After */}
              <div className="bg-machi-bg rounded-lg p-3">
                <div className="text-machi-text-dim text-xs mb-1">{t('h2h.coins')}</div>
                <div className="font-mono">
                  {playerCoins[turnIdx]?.map((c, i) => (
                    <span key={i} className="mr-2">P{i + 1}: {c}</span>
                  ))}
                </div>
              </div>
            </div>

            {turn.bürohausSwap && (
              <div className="mt-2 text-xs text-machi-purple">
                Bürohaus: {turn.bürohausSwap}
              </div>
            )}
          </div>
        )}

        {/* Final State */}
        <div className="bg-machi-surface rounded-xl p-4 border border-machi-border">
          <h3 className="text-sm font-semibold mb-2">{t('h2h.finalState')}</h3>
          <div className="grid grid-cols-2 gap-4 text-sm">
            {engines.map((eng, i) => (
              <div key={i} className={`rounded-lg p-3 ${i === game.winnerIndex ? 'bg-machi-accent/10 border border-machi-accent/30' : 'bg-machi-bg'}`}>
                <div className="font-semibold mb-1">
                  P{i + 1}: {eng}
                  {i === game.winnerIndex && <span className="ml-2 text-machi-accent text-xs">★</span>}
                </div>
                <div className="text-machi-text-dim">
                  {game.finalCoins?.[i]} {t('h2h.coins')} · {game.landmarkCounts?.[i]}/4 {t('h2h.landmarks')}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

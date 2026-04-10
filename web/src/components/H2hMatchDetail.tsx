import { useMemo, useState } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { H2hMatchResult, H2hGameLog } from '../api/types';

interface Props {
  result: H2hMatchResult;
  onBack: () => void;
  onSelectGame: (gameIndex: number) => void;
}

/** Compute per-engine eval times from game logs, accounting for seat swap. */
function computePerEngineEval(result: H2hMatchResult): [number, number] {
  const sum = [0, 0];
  const count = [0, 0];
  const seatSwap = result.config.seatSwap !== false;
  const swapPoint = Math.floor(result.config.gameCount / 2);

  for (const g of result.gameLogs) {
    const swapped = seatSwap && g.gameIndex >= swapPoint;
    for (const t of g.turns) {
      if (t.playerIndex === 0 || t.playerIndex === 1) {
        const engineIdx = swapped ? (1 - t.playerIndex) : t.playerIndex;
        sum[engineIdx] += t.evaluateTimeMs;
        count[engineIdx]++;
      }
    }
  }
  return [
    count[0] > 0 ? sum[0] / count[0] : 0,
    count[1] > 0 ? sum[1] / count[1] : 0,
  ];
}

interface MatchHighlight {
  label: string;
  gameIndex: number;
  value: string;
  detail?: string;
  rank?: number;       // 1-based rank within category
  category: string;    // category key for grouping
}

function computeHighlights(result: H2hMatchResult, t: (k: string) => string, topN = 5): MatchHighlight[] {
  const games = result.gameLogs;
  if (!games || games.length === 0) return [];

  const highlights: MatchHighlight[] = [];
  const makeDetail = (g: H2hGameLog) =>
    `P${g.winnerIndex + 1} ${t('h2h.won')}${g.timeoutWin ? ` (${t('h2h.timeout')})` : ''}`;
  const makeDetailWithTurns = (g: H2hGameLog) =>
    `${makeDetail(g)} · ${g.totalTurns} ${t('h2h.turns')}`;

  // Sort by turns ascending → shortest first
  const byTurns = [...games].sort((a, b) => a.totalTurns - b.totalTurns);
  for (let i = 0; i < Math.min(topN, byTurns.length); i++) {
    const g = byTurns[i];
    highlights.push({
      label: t('h2h.shortestGame'),
      category: 'shortest',
      rank: i + 1,
      gameIndex: g.gameIndex,
      value: `${g.totalTurns} ${t('h2h.turns')}`,
      detail: makeDetail(g),
    });
  }

  // Longest: sort descending
  const byTurnsDesc = [...games].sort((a, b) => b.totalTurns - a.totalTurns);
  for (let i = 0; i < Math.min(topN, byTurnsDesc.length); i++) {
    const g = byTurnsDesc[i];
    highlights.push({
      label: t('h2h.longestGame'),
      category: 'longest',
      rank: i + 1,
      gameIndex: g.gameIndex,
      value: `${g.totalTurns} ${t('h2h.turns')}`,
      detail: makeDetail(g),
    });
  }

  // Biggest blowout (landmark difference descending)
  const seatSwap = result.config.seatSwap !== false;
  const swapPoint = Math.floor(result.config.gameCount / 2);
  const gamesWithLmDiff = games
    .filter(g => g.landmarkCounts && g.landmarkCounts.length >= 2)
    .map(g => ({ g, diff: Math.abs(g.landmarkCounts[0] - g.landmarkCounts[1]) }))
    .sort((a, b) => b.diff - a.diff || a.g.totalTurns - b.g.totalTurns);
  for (let i = 0; i < Math.min(topN, gamesWithLmDiff.length); i++) {
    const { g, diff } = gamesWithLmDiff[i];
    if (diff === 0 && i > 0) break; // no point showing 0-diff blowouts
    const swapped = seatSwap && g.gameIndex >= swapPoint;
    const lm = swapped ? [g.landmarkCounts[1], g.landmarkCounts[0]] : g.landmarkCounts;
    highlights.push({
      label: t('h2h.biggestBlowout'),
      category: 'blowout',
      rank: i + 1,
      gameIndex: g.gameIndex,
      value: `${lm[0]} : ${lm[1]} ${t('h2h.landmarks').toLowerCase()}`,
      detail: makeDetailWithTurns(g),
    });
  }

  // Richest finish (highest single-player coins)
  const gamesWithCoins = games
    .filter(g => g.finalCoins)
    .map(g => ({ g, max: Math.max(...g.finalCoins) }))
    .sort((a, b) => b.max - a.max);
  for (let i = 0; i < Math.min(topN, gamesWithCoins.length); i++) {
    const { g, max } = gamesWithCoins[i];
    highlights.push({
      label: t('h2h.richestFinish'),
      category: 'richest',
      rank: i + 1,
      gameIndex: g.gameIndex,
      value: `${max} ${t('h2h.coins').toLowerCase()}`,
      detail: makeDetailWithTurns(g),
    });
  }

  return highlights;
}

/** Compute per-game luck advantage for the winner, accounting for seat swap.
 * Returns null when no luck data, or a number where positive = lucky win, negative = skilled win. */
function computeWinnerLuckAdvantage(
  game: H2hGameLog, seatSwap: boolean, swapPoint: number
): number | null {
  const luck = [0, 0];
  let hasLuck = false;
  for (const t of game.turns) {
    if (t.rollLuck != null && (t.playerIndex === 0 || t.playerIndex === 1)) {
      luck[t.playerIndex] += t.rollLuck;
      hasLuck = true;
    }
  }
  if (!hasLuck) return null;
  // Map seat indices to engine indices (accounting for swap)
  const swapped = seatSwap && game.gameIndex >= swapPoint;
  const winnerSeat = swapped ? (1 - game.winnerIndex) : game.winnerIndex;
  const loserSeat = 1 - winnerSeat;
  return luck[winnerSeat] - luck[loserSeat];
}

export function H2hMatchDetail({ result, onBack, onSelectGame }: Props) {
  const { t } = useLocale();
  const engines = result.config.engineIds;
  const [evalA, evalB] = useMemo(() => computePerEngineEval(result), [result]);
  const highlights = useMemo(() => computeHighlights(result, t), [result, t]);
  const [expandedCats, setExpandedCats] = useState<Set<string>>(new Set());

  // Pre-compute per-game winner luck advantage (accounts for seat swap)
  const seatSwap = result.config.seatSwap !== false;
  const swapPoint = Math.floor(result.config.gameCount / 2);
  const gameLuckMap = useMemo(() => {
    const m = new Map<number, number | null>();
    for (const g of result.gameLogs) {
      m.set(g.gameIndex, computeWinnerLuckAdvantage(g, seatSwap, swapPoint));
    }
    return m;
  }, [result.gameLogs, seatSwap, swapPoint]);
  const hasAnyLuck = useMemo(() =>
    [...gameLuckMap.values()].some(v => v != null), [gameLuckMap]);
  const highlightGroups = useMemo(() => {
    const groups: Record<string, MatchHighlight[]> = {};
    for (const h of highlights) {
      (groups[h.category] ??= []).push(h);
    }
    return groups;
  }, [highlights]);

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
          <div>
            <h1 className="text-2xl font-bold">
              {engines[0]} <span className="text-machi-text-dim">vs</span> {engines[1]}
            </h1>
            <div className="text-xs text-machi-text-dim font-mono">{result.id}</div>
          </div>
        </div>

        {/* Symmetric Stats Row */}
        <div className="grid grid-cols-5 gap-3 mb-6">
          {/* Engine A eval */}
          <div className="bg-machi-surface rounded-xl p-3 border border-machi-border text-center">
            <div className="text-machi-text-dim text-[10px] mb-1">{t('h2h.avgEval')}</div>
            <div className="text-lg font-bold">{evalA.toFixed(0)}<span className="text-xs text-machi-text-dim">ms</span></div>
          </div>
          {/* Engine A win rate */}
          <div className="bg-machi-surface rounded-xl p-3 border border-machi-border text-center">
            <div className="text-machi-text-dim text-[10px] mb-1">{engines[0]}</div>
            <div className="text-3xl font-bold">{(result.winRates[0] * 100).toFixed(1)}%</div>
            <div className="text-xs text-machi-text-dim">{result.wins[0]} {t('h2h.winsOf')} {result.gameCount}</div>
          </div>
          {/* Avg turns (center) */}
          <div className="bg-machi-surface rounded-xl p-3 border border-machi-border text-center">
            <div className="text-machi-text-dim text-[10px] mb-1">{t('h2h.avgTurns')}</div>
            <div className="text-3xl font-bold">{result.avgGameLength.toFixed(0)}</div>
          </div>
          {/* Engine B win rate */}
          <div className="bg-machi-surface rounded-xl p-3 border border-machi-border text-center">
            <div className="text-machi-text-dim text-[10px] mb-1">{engines[1]}</div>
            <div className="text-3xl font-bold">{(result.winRates[1] * 100).toFixed(1)}%</div>
            <div className="text-xs text-machi-text-dim">{result.wins[1]} {t('h2h.winsOf')} {result.gameCount}</div>
          </div>
          {/* Engine B eval */}
          <div className="bg-machi-surface rounded-xl p-3 border border-machi-border text-center">
            <div className="text-machi-text-dim text-[10px] mb-1">{t('h2h.avgEval')}</div>
            <div className="text-lg font-bold">{evalB.toFixed(0)}<span className="text-xs text-machi-text-dim">ms</span></div>
          </div>
        </div>

        {/* Win Rate Bar */}
        <div className="bg-machi-surface rounded-xl p-4 mb-6 border border-machi-border">
          <div className="flex h-6 rounded-full overflow-hidden">
            <div
              className="bg-machi-accent transition-all"
              style={{ width: `${result.winRates[0] * 100}%` }}
            />
            <div
              className="bg-machi-purple transition-all"
              style={{ width: `${result.winRates[1] * 100}%` }}
            />
          </div>
          <div className="flex justify-between text-xs text-machi-text-dim mt-1">
            <span>{engines[0]}: {(result.winRates[0] * 100).toFixed(1)}%</span>
            <span>{engines[1]}: {(result.winRates[1] * 100).toFixed(1)}%</span>
          </div>
          {result.luckAdjustedWinRates && (
            <div className="mt-2 pt-2 border-t border-machi-border/50">
              <div className="flex h-4 rounded-full overflow-hidden opacity-80">
                <div
                  className="bg-machi-accent/70 transition-all"
                  style={{ width: `${result.luckAdjustedWinRates[0] * 100}%` }}
                />
                <div
                  className="bg-machi-purple/70 transition-all"
                  style={{ width: `${result.luckAdjustedWinRates[1] * 100}%` }}
                />
              </div>
              <div className="flex justify-between text-[10px] text-machi-text-dim mt-0.5">
                <span>{t('h2h.luckAdjustedWr')}: {(result.luckAdjustedWinRates[0] * 100).toFixed(1)}%</span>
                <span>{(result.luckAdjustedWinRates[1] * 100).toFixed(1)}%</span>
              </div>
              {result.totalLuck && (
                <div className="flex justify-between text-[10px] text-machi-text-dim mt-0.5">
                  <span>{t('h2h.totalLuck')}: {result.totalLuck[0] >= 0 ? '+' : ''}{result.totalLuck[0].toFixed(2)}</span>
                  <span>{result.totalLuck[1] >= 0 ? '+' : ''}{result.totalLuck[1].toFixed(2)}</span>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Match Highlights */}
        {Object.keys(highlightGroups).length > 0 && (
          <div className="bg-machi-surface rounded-xl p-5 mb-6 border border-machi-border">
            <h2 className="text-sm font-semibold text-machi-text-dim mb-3">{t('h2h.highlights')}</h2>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 items-start">
              {Object.entries(highlightGroups).map(([cat, items]) => {
                const top = items[0];
                const isExpanded = expandedCats.has(cat);
                const hasMore = items.length > 1;
                return (
                  <div key={cat} className="bg-machi-bg rounded-lg overflow-hidden">
                    {/* Top item — always visible, clickable to game */}
                    <button
                      onClick={() => onSelectGame(top.gameIndex)}
                      className="text-left w-full p-3 hover:bg-machi-bg/80 transition group"
                    >
                      <div className="text-[10px] text-machi-text-dim mb-1">{top.label}</div>
                      <div className="text-sm font-bold">{top.value}</div>
                      {top.detail && <div className="text-[10px] text-machi-text-dim">{top.detail}</div>}
                      <div className="text-[10px] text-machi-accent opacity-0 group-hover:opacity-100 transition mt-1">
                        #{top.gameIndex + 1} ▶
                      </div>
                    </button>
                    {/* Expand/collapse for remaining items */}
                    {hasMore && (
                      <>
                        <button
                          onClick={() => setExpandedCats(prev => {
                            const next = new Set(prev);
                            if (next.has(cat)) next.delete(cat); else next.add(cat);
                            return next;
                          })}
                          className="w-full text-[10px] text-machi-text-dim hover:text-machi-text py-1 border-t border-machi-border/30 transition"
                        >
                          {isExpanded ? '▲' : `▼ +${items.length - 1} more`}
                        </button>
                        {isExpanded && items.slice(1).map((h) => (
                          <button
                            key={h.gameIndex}
                            onClick={() => onSelectGame(h.gameIndex)}
                            className="text-left w-full px-3 py-1.5 hover:bg-machi-bg/80 transition group border-t border-machi-border/20"
                          >
                            <div className="flex items-center gap-1">
                              <span className="text-[10px] text-machi-text-dim">#{h.rank}</span>
                              <span className="text-xs font-medium">{h.value}</span>
                              <span className="text-[10px] text-machi-accent opacity-0 group-hover:opacity-100 transition ml-auto">
                                #{h.gameIndex + 1} ▶
                              </span>
                            </div>
                            {h.detail && <div className="text-[10px] text-machi-text-dim">{h.detail}</div>}
                          </button>
                        ))}
                      </>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* Game List */}
        <div className="bg-machi-surface rounded-xl p-6 border border-machi-border">
          <h2 className="text-lg font-semibold mb-4">{t('h2h.gameList')}</h2>
          <div className="overflow-x-auto max-h-96 overflow-y-auto">
            <table className="w-full text-sm">
              <thead className="sticky top-0 bg-machi-surface">
                <tr className="text-machi-text-dim border-b border-machi-border">
                  <th className="text-left py-2 px-2">#</th>
                  <th className="text-center py-2 px-2">{t('h2h.winner')}</th>
                  <th className="text-center py-2 px-2">{t('h2h.turns')}</th>
                  <th className="text-center py-2 px-2">{t('h2h.landmarks')}</th>
                  <th className="text-center py-2 px-2">{t('h2h.coins')}</th>
                  {hasAnyLuck && <th className="text-center py-2 px-2">{t('h2h.diceFortune')}</th>}
                  <th className="text-center py-2 px-2"></th>
                </tr>
              </thead>
              <tbody>
                {result.gameLogs.map(game => {
                  // Map seat indices to engine indices (seat swap reverses in second half)
                  const swapped = seatSwap && game.gameIndex >= swapPoint;
                  const engineLandmarks = swapped && game.landmarkCounts
                    ? [game.landmarkCounts[1], game.landmarkCounts[0]]
                    : game.landmarkCounts;
                  const engineCoins = swapped && game.finalCoins
                    ? [game.finalCoins[1], game.finalCoins[0]]
                    : game.finalCoins;
                  return (
                  <tr
                    key={game.gameIndex}
                    className="border-b border-machi-border/50 hover:bg-machi-bg/50 cursor-pointer transition"
                    onClick={() => onSelectGame(game.gameIndex)}
                  >
                    <td className="py-2 px-2">{game.gameIndex + 1}</td>
                    <td className="text-center py-2 px-2 font-semibold">
                      {engines[game.winnerIndex]}
                      {game.timeoutWin && (
                        <span className="ml-1 text-xs text-machi-text-dim">(T)</span>
                      )}
                    </td>
                    <td className="text-center py-2 px-2">{game.totalTurns}</td>
                    <td className="text-center py-2 px-2">
                      {engineLandmarks?.map((lm, i) => (
                        <span key={i} className="mx-0.5">{lm}</span>
                      )).reduce<React.ReactNode[]>((acc, el, i) => {
                        if (i > 0) acc.push(<span key={`sep-${i}`} className="text-machi-text-dim">:</span>);
                        acc.push(el);
                        return acc;
                      }, [])}
                    </td>
                    <td className="text-center py-2 px-2 text-machi-text-dim">
                      {engineCoins?.join(' : ')}
                    </td>
                    {hasAnyLuck && (
                      <td className="text-center py-2 px-2 text-[11px] font-mono">
                        {(() => {
                          const adv = gameLuckMap.get(game.gameIndex);
                          if (adv == null) return <span className="text-machi-text-dim">—</span>;
                          return (
                            <span className={adv > 0.05 ? 'text-green-400' : adv < -0.05 ? 'text-red-400' : 'text-machi-text-dim'}>
                              {adv >= 0 ? '+' : ''}{adv.toFixed(2)}
                            </span>
                          );
                        })()}
                      </td>
                    )}
                    <td className="text-center py-2 px-2">
                      <span className="text-machi-accent text-xs">▶</span>
                    </td>
                  </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>

        {/* Config Info */}
        <div className="mt-4 text-xs text-machi-text-dim">
          {t('h2h.config')}: {result.config.iterationsPerEval} iter, {result.config.maxTurnsPerGame} max turns
          &nbsp;·&nbsp; {t('h2h.time')}: {(result.totalTimeMs / 1000).toFixed(1)}s
          &nbsp;·&nbsp; {result.date.split('T')[0]}
        </div>
      </div>
    </div>
  );
}

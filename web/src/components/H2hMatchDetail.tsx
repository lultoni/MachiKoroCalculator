import { useMemo } from 'react';
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
}

function computeHighlights(result: H2hMatchResult, t: (k: string) => string): MatchHighlight[] {
  const games = result.gameLogs;
  if (!games || games.length === 0) return [];

  const highlights: MatchHighlight[] = [];

  // Shortest game
  let shortest = games[0];
  let longest = games[0];
  for (const g of games) {
    if (g.totalTurns < shortest.totalTurns) shortest = g;
    if (g.totalTurns > longest.totalTurns) longest = g;
  }
  highlights.push({
    label: t('h2h.shortestGame'),
    gameIndex: shortest.gameIndex,
    value: `${shortest.totalTurns} ${t('h2h.turns')}`,
    detail: `P${shortest.winnerIndex + 1} ${t('h2h.won')}`,
  });
  highlights.push({
    label: t('h2h.longestGame'),
    gameIndex: longest.gameIndex,
    value: `${longest.totalTurns} ${t('h2h.turns')}`,
    detail: `P${longest.winnerIndex + 1} ${t('h2h.won')}${longest.timeoutWin ? ` (${t('h2h.timeout')})` : ''}`,
  });

  // Biggest blowout (landmark difference)
  let bestBlowout: H2hGameLog | null = null;
  let bestBlowoutDiff = 0;
  for (const g of games) {
    if (g.landmarkCounts && g.landmarkCounts.length >= 2) {
      const diff = Math.abs(g.landmarkCounts[0] - g.landmarkCounts[1]);
      if (diff > bestBlowoutDiff) {
        bestBlowoutDiff = diff;
        bestBlowout = g;
      }
    }
  }
  if (bestBlowout) {
    highlights.push({
      label: t('h2h.biggestBlowout'),
      gameIndex: bestBlowout.gameIndex,
      value: `${bestBlowout.landmarkCounts[0]} : ${bestBlowout.landmarkCounts[1]} ${t('h2h.landmarks').toLowerCase()}`,
      detail: `P${bestBlowout.winnerIndex + 1} ${t('h2h.won')} · ${bestBlowout.totalTurns} ${t('h2h.turns')}`,
    });
  }

  // Most coins at end
  let richestGame: H2hGameLog | null = null;
  let richestCoins = 0;
  for (const g of games) {
    if (g.finalCoins) {
      const max = Math.max(...g.finalCoins);
      if (max > richestCoins) {
        richestCoins = max;
        richestGame = g;
      }
    }
  }
  if (richestGame) {
    highlights.push({
      label: t('h2h.richestFinish'),
      gameIndex: richestGame.gameIndex,
      value: `${richestCoins} ${t('h2h.coins').toLowerCase()}`,
      detail: `P${richestGame.winnerIndex + 1} ${t('h2h.won')} · ${richestGame.totalTurns} ${t('h2h.turns')}`,
    });
  }

  return highlights;
}

export function H2hMatchDetail({ result, onBack, onSelectGame }: Props) {
  const { t } = useLocale();
  const engines = result.config.engineIds;
  const [evalA, evalB] = useMemo(() => computePerEngineEval(result), [result]);
  const highlights = useMemo(() => computeHighlights(result, t), [result, t]);

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
          <h1 className="text-2xl font-bold">
            {engines[0]} <span className="text-machi-text-dim">vs</span> {engines[1]}
          </h1>
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
        </div>

        {/* Match Highlights */}
        {highlights.length > 0 && (
          <div className="bg-machi-surface rounded-xl p-5 mb-6 border border-machi-border">
            <h2 className="text-sm font-semibold text-machi-text-dim mb-3">{t('h2h.highlights')}</h2>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              {highlights.map((h, i) => (
                <button
                  key={i}
                  onClick={() => onSelectGame(h.gameIndex)}
                  className="text-left bg-machi-bg rounded-lg p-3 hover:bg-machi-bg/80 transition group"
                >
                  <div className="text-[10px] text-machi-text-dim mb-1">{h.label}</div>
                  <div className="text-sm font-bold">{h.value}</div>
                  {h.detail && <div className="text-[10px] text-machi-text-dim">{h.detail}</div>}
                  <div className="text-[10px] text-machi-accent opacity-0 group-hover:opacity-100 transition mt-1">
                    #{h.gameIndex + 1} ▶
                  </div>
                </button>
              ))}
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
                  <th className="text-center py-2 px-2"></th>
                </tr>
              </thead>
              <tbody>
                {result.gameLogs.map(game => (
                  <tr
                    key={game.gameIndex}
                    className="border-b border-machi-border/50 hover:bg-machi-bg/50 cursor-pointer transition"
                    onClick={() => onSelectGame(game.gameIndex)}
                  >
                    <td className="py-2 px-2">{game.gameIndex + 1}</td>
                    <td className="text-center py-2 px-2 font-semibold">
                      P{game.winnerIndex + 1}
                      {game.timeoutWin && (
                        <span className="ml-1 text-xs text-machi-text-dim">(T)</span>
                      )}
                    </td>
                    <td className="text-center py-2 px-2">{game.totalTurns}</td>
                    <td className="text-center py-2 px-2">
                      {game.landmarkCounts?.map((lm, i) => (
                        <span key={i} className="mx-0.5">{lm}</span>
                      )).reduce<React.ReactNode[]>((acc, el, i) => {
                        if (i > 0) acc.push(<span key={`sep-${i}`} className="text-machi-text-dim">:</span>);
                        acc.push(el);
                        return acc;
                      }, [])}
                    </td>
                    <td className="text-center py-2 px-2 text-machi-text-dim">
                      {game.finalCoins?.join(' : ')}
                    </td>
                    <td className="text-center py-2 px-2">
                      <span className="text-machi-accent text-xs">▶</span>
                    </td>
                  </tr>
                ))}
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

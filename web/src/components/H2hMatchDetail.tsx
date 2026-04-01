import { useLocale } from '../i18n/useLocale';
import type { H2hMatchResult } from '../api/types';

interface Props {
  result: H2hMatchResult;
  onBack: () => void;
  onSelectGame: (gameIndex: number) => void;
}

export function H2hMatchDetail({ result, onBack, onSelectGame }: Props) {
  const { t } = useLocale();
  const engines = result.config.engineIds;

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

        {/* Aggregate Stats */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
          {engines.map((eng, i) => (
            <div key={i} className="bg-machi-surface rounded-xl p-4 border border-machi-border text-center">
              <div className="text-machi-text-dim text-xs mb-1">P{i + 1}: {eng}</div>
              <div className="text-3xl font-bold">{(result.winRates[i] * 100).toFixed(1)}%</div>
              <div className="text-sm text-machi-text-dim">{result.wins[i]} {t('h2h.winsOf')} {result.gameCount}</div>
            </div>
          ))}
          <div className="bg-machi-surface rounded-xl p-4 border border-machi-border text-center">
            <div className="text-machi-text-dim text-xs mb-1">{t('h2h.avgTurns')}</div>
            <div className="text-3xl font-bold">{result.avgGameLength.toFixed(0)}</div>
          </div>
          <div className="bg-machi-surface rounded-xl p-4 border border-machi-border text-center">
            <div className="text-machi-text-dim text-xs mb-1">{t('h2h.avgEval')}</div>
            <div className="text-3xl font-bold">{result.avgEvalTimeMs.toFixed(0)}<span className="text-sm">ms</span></div>
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

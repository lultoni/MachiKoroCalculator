/** Post-game decision review — compares player choices vs engine recommendations. */

import { useState, useMemo } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { SessionJson, EngineSnapshotJson, ProjectDef } from '../api/types';
import { cardTextClass, categoryIcon } from '../utils/cardDisplay';

interface Props {
  session: SessionJson;
  userPlayerIndex: number;
  projects: { byId: (id: string) => ProjectDef | undefined };
  language: 'de' | 'en';
  onBack: () => void;
}

interface ReviewTurn {
  turnIndex: number;
  roll: number;
  diceCount: number;
  isDoubles: boolean;
  boughtId: string | null;
  snapshot: EngineSnapshotJson;
}

export function DecisionReview({ session, userPlayerIndex, projects, language, onBack }: Props) {
  const { t } = useLocale();
  const [turnIdx, setTurnIdx] = useState(0);

  // Filter to user's turns that have engine snapshots
  const reviewTurns = useMemo(() => {
    const turns: ReviewTurn[] = [];
    const snapshots = session.engineSnapshots;
    if (!snapshots) return turns;

    for (let i = 0; i < session.history.length; i++) {
      const turn = session.history[i];
      const snap = snapshots[i];
      if (turn.playerIndex === userPlayerIndex && snap) {
        turns.push({
          turnIndex: i,
          roll: turn.roll,
          diceCount: turn.diceCount,
          isDoubles: turn.isDoubles,
          boughtId: turn.boughtId,
          snapshot: snap,
        });
      }
    }
    return turns;
  }, [session, userPlayerIndex]);

  // Compute summary statistics
  const summary = useMemo(() => {
    let agreed = 0;
    let totalRank = 0;
    for (const rt of reviewTurns) {
      const affordableOptions = rt.snapshot.options.filter(o => o.affordable);
      const choiceId = rt.boughtId ?? '_wait_';
      const rank = affordableOptions.findIndex(o => o.projectId === choiceId) + 1;
      if (rank === 1) agreed++;
      if (rank > 0) totalRank += rank;
      else totalRank += affordableOptions.length; // chose something off-list
    }
    return {
      agreed,
      total: reviewTurns.length,
      avgRank: reviewTurns.length > 0 ? totalRank / reviewTurns.length : 0,
    };
  }, [reviewTurns]);

  if (reviewTurns.length === 0) {
    return (
      <div className="space-y-4">
        <p className="text-sm text-machi-text-dim">{t('review.noData')}</p>
        <button
          onClick={onBack}
          className="text-sm text-machi-accent hover:underline"
        >
          {t('review.backToResults')}
        </button>
      </div>
    );
  }

  const current = reviewTurns[turnIdx];
  const affordableOptions = current.snapshot.options.filter(o => o.affordable);
  const choiceId = current.boughtId ?? '_wait_';
  const choiceRank = affordableOptions.findIndex(o => o.projectId === choiceId) + 1;
  const engineTop = affordableOptions[0];

  const nameKey = `name_${language}` as 'name_de' | 'name_en';
  const cardName = (id: string) => {
    if (id === '_wait_') return t('review.saved');
    return projects.byId(id)?.[nameKey] ?? id;
  };

  const cardClass = (id: string) => {
    if (id === '_wait_') return 'text-machi-text-dim';
    return cardTextClass(projects.byId(id)?.color);
  };

  const cardIcon = (id: string) => {
    return categoryIcon(projects.byId(id)?.category);
  };

  return (
    <div className="space-y-4">
      {/* Back button */}
      <button
        onClick={onBack}
        className="text-sm text-machi-text-dim hover:text-machi-text transition"
      >
        ← {t('review.backToResults')}
      </button>

      <h3 className="text-lg font-bold">{t('review.title')}</h3>

      {/* Summary bar */}
      <div className="flex gap-4 text-sm">
        <div className="bg-machi-bg rounded-lg px-3 py-2">
          <span className="text-machi-text-dim">{t('review.agreed')}: </span>
          <span className="font-mono font-medium text-machi-green">
            {summary.agreed}
          </span>
          <span className="text-machi-text-dim">
            {' '}{t('review.ofTurns', { n: summary.total })}
          </span>
          <span className="text-machi-text-dim ml-1">
            ({summary.total > 0 ? Math.round((summary.agreed / summary.total) * 100) : 0}%)
          </span>
        </div>
        <div className="bg-machi-bg rounded-lg px-3 py-2">
          <span className="text-machi-text-dim">{t('review.avgRank')}: </span>
          <span className="font-mono font-medium text-machi-text">
            {summary.avgRank.toFixed(1)}
          </span>
        </div>
      </div>

      {/* Turn navigation */}
      <div className="flex items-center gap-3">
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
          {t('h2h.turnN', { n: String(turnIdx + 1) })} / {reviewTurns.length}
        </span>
        <button
          onClick={() => setTurnIdx(i => Math.min(reviewTurns.length - 1, i + 1))}
          disabled={turnIdx >= reviewTurns.length - 1}
          className="px-3 py-1.5 bg-machi-surface border border-machi-border rounded-lg text-sm
                     disabled:opacity-30 hover:bg-machi-bg transition"
        >
          ▶
        </button>
        <button
          onClick={() => setTurnIdx(reviewTurns.length - 1)}
          disabled={turnIdx >= reviewTurns.length - 1}
          className="px-3 py-1.5 bg-machi-surface border border-machi-border rounded-lg text-sm
                     disabled:opacity-30 hover:bg-machi-bg transition"
        >
          ⏭
        </button>
      </div>

      {/* Turn detail card */}
      <div className="bg-machi-surface rounded-xl p-5 border border-machi-border space-y-4">
        {/* Roll info */}
        <div className="flex items-center gap-3 text-sm">
          <span className="font-mono text-lg">
            {current.diceCount}d6 → {current.roll}
          </span>
          {current.isDoubles && (
            <span className="text-xs px-1.5 py-0.5 rounded bg-machi-yellow/20 text-machi-yellow">
              {t('dice.doubles')}
            </span>
          )}
          <span className="ml-auto text-machi-text-dim text-xs">
            {current.snapshot.engineId} · {current.snapshot.iterationsUsed} iter · {current.snapshot.computeTimeMs}ms
          </span>
        </div>

        {/* Your choice vs engine's #1 */}
        <div className="grid grid-cols-2 gap-3">
          {/* Your choice */}
          <div className="bg-machi-bg rounded-lg p-3">
            <div className="text-machi-text-dim text-xs mb-1">{t('review.youBought')}</div>
            <div className={`font-semibold ${cardClass(choiceId)}`}>
              {cardIcon(choiceId) && <span className="mr-1 text-[12px]">{cardIcon(choiceId)}</span>}
              {cardName(choiceId)}
            </div>
            <div className="text-xs mt-1">
              {choiceRank === 1 ? (
                <span className="text-machi-green font-medium">{t('review.match')}</span>
              ) : choiceRank > 0 ? (
                <span className="text-machi-yellow font-medium">{t('review.rank', { n: choiceRank })}</span>
              ) : (
                <span className="text-machi-red font-medium">?</span>
              )}
            </div>
          </div>

          {/* Engine #1 */}
          <div className="bg-machi-bg rounded-lg p-3">
            <div className="text-machi-text-dim text-xs mb-1">{t('review.enginePick')}</div>
            {engineTop ? (
              <>
                <div className={`font-semibold ${cardClass(engineTop.projectId)}`}>
                  {cardIcon(engineTop.projectId) && <span className="mr-1 text-[12px]">{cardIcon(engineTop.projectId)}</span>}
                  {cardName(engineTop.projectId)}
                </div>
                <div className="text-xs text-machi-text-dim mt-1">
                  {(engineTop.score * 100).toFixed(1)}%
                </div>
              </>
            ) : (
              <div className="text-machi-text-dim">{t('review.noEval')}</div>
            )}
          </div>
        </div>

        {/* Top engine options */}
        <div className="space-y-1">
          {affordableOptions.slice(0, 5).map((opt, i) => {
            const isChoice = opt.projectId === choiceId;
            return (
              <div
                key={opt.projectId}
                className={`flex items-center gap-2 text-xs px-2 py-1 rounded ${
                  isChoice ? 'bg-machi-accent/10 border border-machi-accent/30' : 'bg-machi-bg/50'
                }`}
              >
                <span className="w-5 text-right font-mono text-machi-text-dim">#{i + 1}</span>
                <span className={`flex-1 ${cardClass(opt.projectId)}`}>
                  {cardIcon(opt.projectId) && <span className="mr-0.5 text-[11px]">{cardIcon(opt.projectId)}</span>}
                  {cardName(opt.projectId)}
                </span>
                <span className="font-mono text-machi-text-dim w-14 text-right">
                  {(opt.score * 100).toFixed(1)}%
                </span>
                {isChoice && (
                  <span className="text-machi-accent text-[10px]">← you</span>
                )}
              </div>
            );
          })}
        </div>

        {/* Summary sentence for engine top pick */}
        {engineTop?.summarySentence && (
          <div className="text-xs text-machi-text-dim italic px-2">
            {engineTop.summarySentence}
          </div>
        )}
      </div>
    </div>
  );
}

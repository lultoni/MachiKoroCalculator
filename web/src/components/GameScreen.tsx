/** Main game screen — 3-column layout with all game UI. */

import { useState, useCallback, useEffect } from 'react';
import { useLocale } from '../i18n/useLocale';
import { useEngine } from '../hooks/useEngine';
import { useRollPreview } from '../hooks/useRollPreview';
import { useInsights } from '../hooks/useInsights';
import type { UseSessionReturn } from '../hooks/useSession';
import type { Settings } from '../hooks/useSettings';
import type { UseHoverReturn } from '../hooks/useHover';
import type { ProjectDef, ApplyTurnRequest } from '../api/types';
import { cardTextClass, categoryIconPath } from '../utils/cardDisplay';
import { TurnIndicator } from './TurnIndicator';
import { DiceInterface } from './DiceInterface';
import { CoinFlowDisplay } from './CoinFlowDisplay';
import { PurchaseArea } from './PurchaseArea';
import { OpponentTurnEntry } from './OpponentTurnEntry';
import { InsightsPanel } from './InsightsPanel';
import { CardTooltip } from './CardTooltip';
import { BürohausModal } from './BürohausModal';
import { SettingsScreen } from './SettingsScreen';
import { SaveLoadMenu } from './SaveLoadMenu';
import { DecisionReview } from './DecisionReview';

interface Props {
  session: UseSessionReturn;
  settings: Settings;
  updateSettings: (partial: Partial<Settings>) => void;
  projects: { projects: ProjectDef[]; byId: (id: string) => ProjectDef | undefined };
  hover: UseHoverReturn;
}

export function GameScreen({ session, settings, updateSettings, projects, hover }: Props) {
  const { t } = useLocale();
  const s = session.session!;
  const engine = useEngine();

  // Dice selection state
  const [die1, setDie1] = useState<number | null>(null);
  const [die2, setDie2] = useState<number | null>(null);
  const [diceCount, setDiceCount] = useState<1 | 2>(1);

  // Modal states
  const [showSettings, setShowSettings] = useState(false);
  const [showSaveLoad, setShowSaveLoad] = useState(false);
  const [gameOverView, setGameOverView] = useState<'results' | 'review'>('results');

  const activePlayer = s.state.players[s.nextPlayerIndex];
  const isUserTurn = s.nextPlayerIndex === settings.userPlayerIndex;
  const canUse2d6 = activePlayer.ownedIds.includes('bahnhof');

  // Insights for opponent turns
  const insights = useInsights(settings.userPlayerIndex, isUserTurn, s.effectiveTurnCount);
  const ownsBürohaus = activePlayer.ownedIds.includes('bürohaus');

  // Bürohaus modal state
  const [showBürohaus, setShowBürohaus] = useState(false);

  // Compute roll total
  const rollTotal = die1 != null
    ? (diceCount === 2 && die2 != null ? die1 + die2 : die1)
    : 0;

  // Roll preview from cached evaluate response
  const preview = useRollPreview(engine.result, rollTotal, diceCount);
  const playerCoinDelta = preview.coinDeltas?.[s.nextPlayerIndex] ?? null;
  const coinsAfterRoll = playerCoinDelta != null ? activePlayer.coins + playerCoinDelta : null;

  // Hovered card name for coin flow
  const hoveredProj = hover.hovered ? projects.byId(hover.hovered.projectId) : null;
  const hoveredName = hoveredProj
    ? hoveredProj[`name_${settings.language}` as 'name_de' | 'name_en'] ?? hoveredProj.name_de
    : undefined;

  // Top recommended card for coin flow fallback
  const topOption = engine.result?.rankedOptions?.find(o => o.projectId !== '_wait_' && o.affordable);
  const topProj = topOption ? projects.byId(topOption.projectId) : null;
  const recommendedName = topProj
    ? topProj[`name_${settings.language}` as 'name_de' | 'name_en'] ?? topProj.name_de
    : undefined;
  const recommendedCost = topProj?.cost;

  // Trigger engine evaluation when turn starts or engine changes
  useEffect(() => {
    if (s.finished) return;
    engine.evaluate(s.state, s.nextPlayerIndex, settings.engineId, s.state);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s.nextPlayerIndex, s.effectiveTurnCount, settings.engineId]);

  // Reset dice and hover state only on actual turn changes (not engine switches)
  useEffect(() => {
    setDie1(null);
    setDie2(null);
    setDiceCount(canUse2d6 ? 1 : 1);
    hover.onHover(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s.nextPlayerIndex, s.effectiveTurnCount]);

  // Autosave after each turn if enabled (fire-and-forget, errors silently ignored)
  useEffect(() => {
    if (!settings.autosave || s.effectiveTurnCount === 0) return;
    session.save().catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s.effectiveTurnCount]);

  const handleRollSelect = useCallback((count: 1 | 2, d1: number, d2: number | null) => {
    setDiceCount(count);
    setDie1(d1 > 0 ? d1 : null);
    setDie2(d2 != null && d2 > 0 ? d2 : null);
    // Show Bürohaus modal when roll total = 6 and player owns Bürohaus
    const total = d1 > 0 ? (count === 2 && d2 != null && d2 > 0 ? d1 + d2 : d1) : 0;
    if (total === 6 && ownsBürohaus) {
      setShowBürohaus(true);
    } else {
      setShowBürohaus(false);
    }
  }, [ownsBürohaus]);

  const handleBuy = useCallback((projectId: string | null) => {
    if (rollTotal === 0) return;
    const isDoubles = diceCount === 2 && die1 === die2;
    // Build compact engine snapshot for post-game decision review
    const engineSnapshot = engine.result ? {
      engineId: engine.result.engineId,
      iterationsUsed: engine.result.iterationsUsed,
      computeTimeMs: engine.result.computeTimeMs,
      options: engine.result.rankedOptions.map(o => ({
        projectId: o.projectId,
        score: o.score,
        affordable: o.affordable,
        summarySentence: o.summarySentence,
      })),
    } : undefined;
    session.applyTurn({
      roll: rollTotal,
      boughtId: projectId === '_wait_' ? null : projectId,
      isDoubles,
      diceCount,
      engineSnapshot,
    });
  }, [rollTotal, diceCount, die1, die2, session, engine.result]);

  const handleOpponentConfirm = useCallback((req: ApplyTurnRequest) => {
    session.applyTurn(req);
    // Pre-compute evaluation for user's upcoming turn
    // (fires after state update — the actual precompute runs against the new state)
    setTimeout(() => {
      const nextSession = session.session;
      if (nextSession && !nextSession.finished) {
        engine.precompute(nextSession.state, settings.userPlayerIndex, settings.engineId);
      }
    }, 100);
  }, [session, engine, settings]);

  const handleBürohausSwap = useCallback((req: import('../api/types').BürohausRequest) => {
    session.applyBürohaus(req);
    setShowBürohaus(false);
  }, [session]);

  // Game over check
  if (s.finished) {
    const winner = s.state.players[s.winnerIndex];
    const hasSnapshots = s.engineSnapshots && s.engineSnapshots.some(snap => snap != null);

    return (
      <div className="min-h-screen bg-machi-bg flex items-center justify-center">
        <div className="bg-machi-surface rounded-xl border border-machi-border p-8 space-y-4 max-w-lg w-full">
          {gameOverView === 'results' ? (
            <div className="text-center space-y-4">
              <h2 className="text-2xl font-bold text-machi-yellow">
                {t('gameOver.title', { name: winner?.name ?? '?' })}
              </h2>
              <div className="space-y-2">
                <h3 className="text-sm text-machi-text-dim">{t('gameOver.standings')}</h3>
                {s.state.players.map((p, i) => (
                  <div key={i} className={`flex justify-between px-3 py-1 rounded ${i === s.winnerIndex ? 'bg-machi-yellow/10' : ''}`}>
                    <span className="text-machi-text">{p.name}</span>
                    <span className="text-machi-text-dim">{p.coins}c</span>
                  </div>
                ))}
              </div>
              <div className="flex gap-2">
                {hasSnapshots && (
                  <button
                    className="flex-1 py-2 rounded-lg font-semibold bg-machi-surface border border-machi-accent text-machi-accent hover:bg-machi-accent/10 transition-all"
                    onClick={() => setGameOverView('review')}
                  >
                    {t('btn.review')}
                  </button>
                )}
                <button
                  className="flex-1 py-2 rounded-lg font-semibold bg-machi-accent text-machi-bg hover:brightness-110 transition-all"
                  onClick={() => session.clearSession()}
                >
                  {t('btn.newGame')}
                </button>
              </div>
            </div>
          ) : (
            <DecisionReview
              session={s}
              userPlayerIndex={settings.userPlayerIndex}
              projects={projects}
              language={settings.language}
              onBack={() => setGameOverView('results')}
            />
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-machi-bg text-machi-text flex flex-col">
      {/* Top bar */}
      <header className="bg-machi-surface border-b border-machi-border px-4 py-3 flex items-center justify-between">
        <h1 className="text-lg font-bold">{t('app.title')}</h1>
        <div className="flex items-center gap-3">
          <button
            className="text-sm text-machi-text-dim hover:text-machi-accent transition-colors disabled:opacity-30"
            onClick={() => session.undo()}
            disabled={session.loading || s.history.length === 0}
            title={s.history.length > 0 ? (() => {
              const last = s.history[s.history.length - 1];
              const name = s.state.players[last.playerIndex]?.name ?? '?';
              const bought = last.boughtId
                ? (projects.byId(last.boughtId)?.[`name_${settings.language}` as 'name_de' | 'name_en'] ?? last.boughtId)
                : (settings.language === 'de' ? 'Gespart' : 'Saved');
              return `${name}: ${last.roll} → ${bought}`;
            })() : undefined}
          >
            {t('btn.undo')}
          </button>
          <button
            className="text-sm text-machi-text-dim hover:text-machi-accent transition-colors"
            onClick={() => setShowSaveLoad(true)}
          >
            {t('btn.save')}
          </button>
          <button
            className="text-sm text-machi-text-dim hover:text-machi-accent transition-colors"
            onClick={() => setShowSettings(true)}
          >
            {t('btn.settings')}
          </button>
          <span className="w-px h-4 bg-machi-border" />
          <button
            className="text-sm text-machi-text-dim hover:text-machi-accent transition-colors"
            onClick={() => session.clearSession()}
          >
            {t('btn.newGame')}
          </button>
        </div>
      </header>

      {/* Turn indicator */}
      <TurnIndicator session={s} userPlayerIndex={settings.userPlayerIndex} />

      {/* 3-column layout */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left — Player cards */}
        <aside className="w-64 border-r border-machi-border p-4 overflow-y-auto space-y-4">
          {s.state.players.map((p, i) => (
            <div
              key={i}
              className={`rounded-lg p-3 border transition-colors ${
                i === s.nextPlayerIndex
                  ? 'border-machi-accent bg-machi-accent/5'
                  : 'border-machi-border bg-machi-surface'
              }`}
            >
              <div className="flex justify-between items-center mb-2">
                <span className="font-medium text-sm">{p.name}</span>
                <span className="text-machi-yellow text-sm font-mono">{p.coins}c</span>
              </div>
              {/* Landmarks */}
              <div className="flex gap-1 mb-2">
                {['bahnhof', 'einkaufszentrum', 'freizeitpark', 'funkturm'].map(lid => {
                  const owned = p.ownedIds.includes(lid);
                  const proj = projects.byId(lid);
                  const name = proj?.[`name_${settings.language}` as 'name_de' | 'name_en'] ?? lid;
                  return (
                    <CardTooltip key={lid} project={proj} language={settings.language}>
                      <span
                        className={`text-[10px] px-1 py-0.5 rounded ${
                          owned ? 'bg-machi-yellow/20 text-machi-yellow' : 'bg-machi-border/30 text-machi-text-dim/40'
                        }`}
                      >
                        {name.charAt(0).toUpperCase()}
                      </span>
                    </CardTooltip>
                  );
                })}
              </div>
              {/* Owned cards */}
              <div className="flex flex-wrap gap-1">
                {Object.entries(countCards(p.ownedIds.filter(id => !['bahnhof', 'einkaufszentrum', 'freizeitpark', 'funkturm'].includes(id)))).map(([id, count]) => {
                  const proj = projects.byId(id);
                  if (!proj) return null;
                  return (
                    <CardTooltip key={id} project={proj} language={settings.language}>
                      <span
                        className={`text-xs px-1.5 py-0.5 rounded cursor-default inline-flex items-center ${cardColorClass(proj.color)}`}
                        onMouseEnter={() => hover.onHover({ projectId: id, cost: proj.cost })}
                        onMouseLeave={() => hover.onHover(null)}
                      >
                        {categoryIconPath(proj.category) && (
                          <img src={categoryIconPath(proj.category)} alt="" className="w-3 h-3 mr-0.5" />
                        )}
                        {proj[`name_${settings.language}` as 'name_de' | 'name_en'] ?? proj.name_de}
                        {count > 1 ? ` ×${count}` : ''}
                      </span>
                    </CardTooltip>
                  );
                })}
              </div>
            </div>
          ))}
        </aside>

        {/* Center — Dice, coins, purchase */}
        <main className="flex-1 p-6 overflow-y-auto space-y-6">
          {isUserTurn ? (
            <>
              {/* Dice */}
              <div className="bg-machi-surface rounded-xl border border-machi-border p-4">
                <DiceInterface
                  canUse2d6={canUse2d6}
                  onRollSelect={handleRollSelect}
                  selectedDie1={die1}
                  selectedDie2={die2}
                  selectedDiceCount={diceCount}
                />
              </div>

              {/* Coin flow */}
              <div className="bg-machi-surface rounded-xl border border-machi-border p-4">
                <CoinFlowDisplay
                  coinsNow={activePlayer.coins}
                  coinDelta={playerCoinDelta}
                  hovered={hover.hovered}
                  language={settings.language}
                  projectName={hoveredName}
                  projectColor={hoveredProj?.color}
                  projectCategory={hoveredProj?.category}
                  recommendedName={recommendedName}
                  recommendedCost={recommendedCost}
                  recommendedColor={topProj?.color}
                  recommendedCategory={topProj?.category}
                />
                {/* Opponent coin deltas from this roll (B07 fix) */}
                {preview.coinDeltas && rollTotal > 0 && (
                  <div className="mt-2 grid grid-cols-2 gap-1">
                    {s.state.players.map((p, i) => {
                      if (i === s.nextPlayerIndex) return null;
                      const delta = preview.coinDeltas![i] ?? 0;
                      return (
                        <div key={i} className="flex items-center justify-between px-2 py-0.5 rounded bg-machi-bg/50 text-xs">
                          <span className="text-machi-text-dim">{p.name}</span>
                          <span className={`font-mono font-medium ${
                            delta > 0 ? 'text-machi-green' : delta < 0 ? 'text-machi-red' : 'text-machi-text-dim'
                          }`}>
                            {delta > 0 ? `+${delta}` : delta < 0 ? String(delta) : '±0'}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

              {/* Purchase area */}
              {rollTotal > 0 && (
                <PurchaseArea
                  options={engine.result?.rankedOptions ?? []}
                  metricRanges={engine.result?.metricRanges}
                  evaluating={engine.loading}
                  projects={projects}
                  language={settings.language}
                  coinsAfterRoll={coinsAfterRoll}
                  ownedIds={activePlayer.ownedIds}
                  onHover={hover.onHover}
                  onBuy={handleBuy}
                  engineId={engine.result?.engineId}
                  iterationsUsed={engine.result?.iterationsUsed}
                  computeTimeMs={engine.result?.computeTimeMs}
                />
              )}
            </>
          ) : (
            /* Opponent turn tracking + insights */
            <div className="space-y-4">
              <div className="bg-machi-surface rounded-xl border border-machi-border p-4">
                <OpponentTurnEntry
                  opponentName={activePlayer.name}
                  canUse2d6={canUse2d6}
                  projects={projects}
                  language={settings.language}
                  coinsAvailable={activePlayer.coins}
                  onConfirm={handleOpponentConfirm}
                  loading={session.loading}
                  state={s.state}
                  activePlayerIndex={s.nextPlayerIndex}
                  players={s.state.players}
                  ownedIds={activePlayer.ownedIds}
                />
              </div>
              <InsightsPanel
                insights={insights.data}
                loading={insights.loading}
                projects={projects}
                language={settings.language}
              />
            </div>
          )}

          {/* Error display */}
          {(session.error || engine.error) && (
            <div className="bg-red-900/30 border border-red-500/50 rounded-lg p-3 text-red-300 text-sm">
              {session.error || engine.error}
            </div>
          )}
        </main>

        {/* Right — Card market */}
        <aside className="w-72 border-l border-machi-border p-4 overflow-y-auto space-y-4">
          {/* Market supply — regular cards (blau, rot, grün) */}
          <div className="space-y-2">
            <h3 className="text-sm font-semibold text-machi-text-dim">{t('insights.supply')}</h3>
            {projects.projects
              .filter(p => !p.is_grossprojekt && p.color !== 'lila')
              .map(p => {
                const totalOwned = s.state.players.reduce(
                  (sum, pl) => sum + pl.ownedIds.filter(id => id === p.id).length, 0
                );
                const starterCopies = (p.id === 'weizenfeld' || p.id === 'bäckerei')
                  ? s.state.players.length : 0;
                const remaining = 6 - (totalOwned - starterCopies);
                return (
                  <div
                    key={p.id}
                    className={`flex items-center justify-between px-2 py-1 rounded text-sm transition-colors cursor-default ${
                      remaining === 0 ? 'opacity-30 line-through' : 'hover:bg-machi-surface'
                    }`}
                    onMouseEnter={() => hover.onHover({ projectId: p.id, cost: p.cost })}
                    onMouseLeave={() => hover.onHover(null)}
                  >
                    <CardTooltip project={p} language={settings.language}>
                      <span className={`inline-flex items-center ${cardTextClass(p.color)}`}>
                        {categoryIconPath(p.category) && (
                          <img src={categoryIconPath(p.category)} alt="" className="w-3.5 h-3.5 mr-1" />
                        )}
                        {p[`name_${settings.language}` as 'name_de' | 'name_en'] ?? p.name_de}
                      </span>
                    </CardTooltip>
                    <span className={`font-mono text-xs ${remaining <= 2 && remaining > 0 ? 'text-machi-yellow' : 'text-machi-text-dim'}`}>
                      {remaining} / {p.cost}c
                    </span>
                  </div>
                );
              })}
          </div>

          {/* Purple cards — per-player ownership */}
          <div className="space-y-1">
            <h3 className="text-sm font-semibold text-machi-text-dim">
              {settings.language === 'de' ? 'Sondergebäude' : 'Special Buildings'}
            </h3>
            {projects.projects
              .filter(p => p.color === 'lila' && !p.is_grossprojekt)
              .map(p => {
                const name = p[`name_${settings.language}` as 'name_de' | 'name_en'] ?? p.name_de;
                return (
                  <div
                    key={p.id}
                    className="flex items-center justify-between px-2 py-1 rounded text-sm cursor-default hover:bg-machi-surface transition-colors"
                    onMouseEnter={() => hover.onHover({ projectId: p.id, cost: p.cost })}
                    onMouseLeave={() => hover.onHover(null)}
                  >
                    <CardTooltip project={p} language={settings.language}>
                      <span className={`inline-flex items-center ${cardTextClass(p.color)}`}>
                        {categoryIconPath(p.category) && (
                          <img src={categoryIconPath(p.category)} alt="" className="w-3.5 h-3.5 mr-1" />
                        )}
                        {name}
                      </span>
                    </CardTooltip>
                    <div className="flex items-center gap-1.5">
                      <div className="flex gap-0.5">
                        {s.state.players.map((pl, i) => {
                          const owns = pl.ownedIds.includes(p.id);
                          return (
                            <span
                              key={i}
                              className={`text-[10px] w-4 h-4 flex items-center justify-center rounded ${
                                owns
                                  ? 'bg-machi-accent/20 text-machi-accent'
                                  : 'bg-machi-border/20 text-machi-text-dim/30'
                              }`}
                              title={pl.name}
                            >
                              {pl.name.charAt(0)}
                            </span>
                          );
                        })}
                      </div>
                      <span className="font-mono text-xs text-machi-text-dim">{p.cost}c</span>
                    </div>
                  </div>
                );
              })}
          </div>

          {/* Landmarks — per-player ownership */}
          <div className="space-y-1">
            <h3 className="text-sm font-semibold text-machi-text-dim">
              {settings.language === 'de' ? 'Großprojekte' : 'Landmarks'}
            </h3>
            {projects.projects
              .filter(p => p.is_grossprojekt)
              .map(p => {
                const name = p[`name_${settings.language}` as 'name_de' | 'name_en'] ?? p.name_de;
                return (
                  <div
                    key={p.id}
                    className="flex items-center justify-between px-2 py-1 rounded text-sm cursor-default hover:bg-machi-surface transition-colors"
                    onMouseEnter={() => hover.onHover({ projectId: p.id, cost: p.cost })}
                    onMouseLeave={() => hover.onHover(null)}
                  >
                    <CardTooltip project={p} language={settings.language}>
                      <span className={`inline-flex items-center ${cardTextClass(p.color)}`}>
                        {categoryIconPath(p.category) && (
                          <img src={categoryIconPath(p.category)} alt="" className="w-3.5 h-3.5 mr-1" />
                        )}
                        {name}
                      </span>
                    </CardTooltip>
                    <div className="flex items-center gap-1.5">
                      <div className="flex gap-0.5">
                        {s.state.players.map((pl, i) => {
                          const owns = pl.ownedIds.includes(p.id);
                          return (
                            <span
                              key={i}
                              className={`text-[10px] w-4 h-4 flex items-center justify-center rounded ${
                                owns
                                  ? 'bg-machi-accent/20 text-machi-accent'
                                  : 'bg-machi-border/20 text-machi-text-dim/30'
                              }`}
                              title={pl.name}
                            >
                              {pl.name.charAt(0)}
                            </span>
                          );
                        })}
                      </div>
                      <span className="font-mono text-xs text-machi-text-dim">{p.cost}c</span>
                    </div>
                  </div>
                );
              })}
          </div>
        </aside>
      </div>

      {/* Bürohaus modal */}
      {showBürohaus && (
        <BürohausModal
          activePlayer={activePlayer}
          opponents={s.state.players
            .map((p, i) => ({ index: i, player: p }))
            .filter(o => o.index !== s.nextPlayerIndex)}
          projects={projects}
          language={settings.language}
          swapRankings={null}
          onSwap={handleBürohausSwap}
          onClose={() => setShowBürohaus(false)}
        />
      )}

      {/* Settings modal */}
      {showSettings && (
        <SettingsScreen
          settings={settings}
          update={updateSettings}
          players={s.state.players}
          onClose={() => setShowSettings(false)}
        />
      )}

      {/* Save/Load modal */}
      {showSaveLoad && (
        <SaveLoadMenu
          onSave={session.save}
          onLoad={session.load}
          loading={session.loading}
          onClose={() => setShowSaveLoad(false)}
        />
      )}
    </div>
  );
}

function countCards(ids: string[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const id of ids) counts[id] = (counts[id] ?? 0) + 1;
  return counts;
}

function cardColorClass(color: string): string {
  switch (color) {
    case 'blau':  return 'bg-machi-blue/20 text-machi-blue';
    case 'rot':   return 'bg-machi-red/20 text-machi-red';
    case 'grün':  return 'bg-machi-green/20 text-machi-green';
    case 'lila':  return 'bg-machi-purple/20 text-machi-purple';
    case 'gelb':  return 'bg-machi-yellow/20 text-machi-yellow';
    default:      return 'bg-gray-500/20 text-gray-400';
  }
}

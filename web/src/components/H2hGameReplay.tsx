import React, { useState, useMemo } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { H2hGameLog, H2hTurnLog, ProjectDef } from '../api/types';
import { cardTextClass, categoryIconPath } from '../utils/cardDisplay';
import { CardTooltip } from './CardTooltip';
import {
  BarChart, Bar, LineChart, Line, ReferenceLine,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';

interface Props {
  game: H2hGameLog;
  engines: string[];
  matchId?: string;
  projects: { byId: (id: string) => ProjectDef | undefined; projects: ProjectDef[] };
  language: 'de' | 'en';
  onBack: () => void;
}

const LANDMARK_IDS = ['bahnhof', 'einkaufszentrum', 'freizeitpark', 'funkturm'];
const LANDMARK_ABBR_DE = ['B', 'E', 'F', 'F'];
const LANDMARK_ABBR_EN = ['T', 'S', 'A', 'R'];
const STARTER_CARDS = ['weizenfeld', 'bäckerei'];

/** Reconstruct per-player card inventories and coin totals up to each turn. */
function buildInventoryTimeline(game: H2hGameLog, playerCount: number) {
  // inventories[turnIdx][playerIdx] = cardId[]
  const inventories: string[][][] = [];
  // coins[turnIdx][playerIdx] = number
  const coinHistory: number[][] = [];

  const current: string[][] = Array.from({ length: playerCount }, () => [...STARTER_CARDS]);
  const coins = Array(playerCount).fill(3);

  for (const tn of game.turns) {
    // Apply income
    for (let i = 0; i < playerCount; i++) {
      coins[i] = Math.max(0, coins[i] + (tn.coinDeltas?.[i] ?? 0));
    }

    // Bürohaus swap
    if (tn.bürohausSwap) {
      const parts = tn.bürohausSwap.split('→');
      if (parts.length === 2) {
        const ownCardId = parts[0].trim();
        const oppCardId = parts[1].trim();
        const pi = tn.playerIndex;
        const oi = 1 - pi;
        // Remove ownCard from active player, add oppCard
        const ownIdx = current[pi].indexOf(ownCardId);
        if (ownIdx >= 0) current[pi].splice(ownIdx, 1);
        current[pi].push(oppCardId);
        // Remove oppCard from opponent, add ownCard
        const oppIdx = current[oi].indexOf(oppCardId);
        if (oppIdx >= 0) current[oi].splice(oppIdx, 1);
        current[oi].push(ownCardId);
      }
    }

    // Purchase
    if (tn.purchasedCardId) {
      current[tn.playerIndex].push(tn.purchasedCardId);
      coins[tn.playerIndex] = tn.coinsAfterPurchase;
    }

    inventories.push(current.map(arr => [...arr]));
    coinHistory.push([...coins]);
  }

  return { inventories, coinHistory };
}

/** Count occurrences of each card ID. */
function countCards(ids: string[]): [string, number][] {
  const map = new Map<string, number>();
  for (const id of ids) {
    map.set(id, (map.get(id) ?? 0) + 1);
  }
  return Array.from(map.entries());
}

/** Compute game-level insights. */
function computeInsights(game: H2hGameLog, playerCount: number) {
  const totalIncome = Array(playerCount).fill(0);
  const totalLost = Array(playerCount).fill(0);
  const totalPurchases = Array(playerCount).fill(0);
  const saveTurns = Array(playerCount).fill(0);
  const turnCounts = Array(playerCount).fill(0);
  const biggestIncome = Array(playerCount).fill(0);
  const diceChoices1d6 = Array(playerCount).fill(0);
  const diceChoices2d6 = Array(playerCount).fill(0);
  const landmarkTurns: number[][] = Array.from({ length: playerCount }, () => []);
  let doublesCount = 0;
  let funkturmCount = 0;
  let bürohausCount = 0;

  for (const tn of game.turns) {
    const pi = tn.playerIndex;
    turnCounts[pi]++;

    // Income & losses
    for (let i = 0; i < playerCount; i++) {
      const d = tn.coinDeltas?.[i] ?? 0;
      if (d > 0) totalIncome[i] += d;
      if (d < 0) totalLost[i] += Math.abs(d);
      if (d > biggestIncome[i]) biggestIncome[i] = d;
    }

    // Dice choices
    if (tn.diceCount === 1) diceChoices1d6[pi]++;
    else diceChoices2d6[pi]++;

    // Purchases & spending
    if (tn.purchasedCardId) {
      totalPurchases[pi]++;
      // Track landmark purchase turns
      if (LANDMARK_IDS.includes(tn.purchasedCardId)) {
        landmarkTurns[pi].push(turnCounts[pi]);
      }
    } else {
      saveTurns[pi]++;
    }
    if (tn.isDoubles) doublesCount++;
    if (tn.funkturmRerolled) funkturmCount++;
    if (tn.bürohausSwap) bürohausCount++;
  }

  // Average income per turn
  const avgIncome = turnCounts.map((tc, i) => tc > 0 ? totalIncome[i] / tc : 0);

  return {
    totalIncome, totalLost, totalPurchases, saveTurns, turnCounts,
    biggestIncome, diceChoices1d6, diceChoices2d6, landmarkTurns, avgIncome,
    doublesCount, funkturmCount, bürohausCount,
  };
}

interface DiceFortune {
  /** Per-player array of own-turn income values (one per own turn, in order). */
  ownIncome: number[][];
  /** Per-player array of income received on OPPONENT turns (from red cards etc.). */
  oppIncome: number[][];
  /** Per-player coin-frequency histogram: how many turns yielded 0 coins, 1 coin, etc. */
  ownIncomeFreq: Map<number, number>[];
  oppIncomeFreq: Map<number, number>[];
}

/** Compute dice fortune data for sparklines and frequency tables. */
function computeDiceFortune(game: H2hGameLog, playerCount: number): DiceFortune {
  const ownIncome: number[][] = Array.from({ length: playerCount }, () => []);
  const oppIncome: number[][] = Array.from({ length: playerCount }, () => []);
  const ownIncomeFreq: Map<number, number>[] = Array.from({ length: playerCount }, () => new Map());
  const oppIncomeFreq: Map<number, number>[] = Array.from({ length: playerCount }, () => new Map());

  for (const tn of game.turns) {
    const roller = tn.playerIndex;
    for (let p = 0; p < playerCount; p++) {
      const delta = tn.coinDeltas?.[p] ?? 0;
      if (p === roller) {
        // Own turn income
        ownIncome[p].push(delta);
        ownIncomeFreq[p].set(delta, (ownIncomeFreq[p].get(delta) ?? 0) + 1);
      } else {
        // Opponent's turn → income from red cards (or losses)
        oppIncome[p].push(delta);
        oppIncomeFreq[p].set(delta, (oppIncomeFreq[p].get(delta) ?? 0) + 1);
      }
    }
  }

  return { ownIncome, oppIncome, ownIncomeFreq, oppIncomeFreq };
}

interface GameEvent {
  turnIndex: number;
  playerIndex: number;
  type: 'landmark' | 'burohaus' | 'funkturm' | 'close-decision';
  label: string;
  detail?: string;
}

/** Extract notable game events for the timeline. */
function extractEvents(
  game: H2hGameLog,
  byId: (id: string) => ProjectDef | undefined,
  language: 'de' | 'en'
): GameEvent[] {
  const events: GameEvent[] = [];
  const playerTurnCount = [0, 0];

  const cardName = (id: string): string => {
    if (id === '_wait_') return language === 'en' ? 'Save' : 'Sparen';
    const card = byId(id);
    if (!card) return id;
    return language === 'en' ? card.name_en : card.name_de;
  };

  for (let ti = 0; ti < game.turns.length; ti++) {
    const tn = game.turns[ti];
    playerTurnCount[tn.playerIndex]++;
    const turnLabel = `T${playerTurnCount[tn.playerIndex]}`;

    // Landmark purchases
    if (tn.purchasedCardId && LANDMARK_IDS.includes(tn.purchasedCardId)) {
      events.push({
        turnIndex: ti,
        playerIndex: tn.playerIndex,
        type: 'landmark',
        label: `${turnLabel}: ${cardName(tn.purchasedCardId)}`,
        detail: tn.scoreIsWinRate !== false
          ? `${(tn.purchaseWinRate * 100).toFixed(0)}%`
          : tn.purchaseWinRate.toFixed(1),
      });
    }

    // Bürohaus swaps
    if (tn.bürohausSwap) {
      const parts = tn.bürohausSwap.split('→');
      if (parts.length === 2) {
        events.push({
          turnIndex: ti,
          playerIndex: tn.playerIndex,
          type: 'burohaus',
          label: `${turnLabel}: ${cardName(parts[0].trim())} → ${cardName(parts[1].trim())}`,
        });
      }
    } else if (tn.bürohausActivated) {
      events.push({
        turnIndex: ti,
        playerIndex: tn.playerIndex,
        type: 'burohaus',
        label: `${turnLabel}: ${language === 'en' ? 'declined' : 'abgelehnt'}`,
      });
    }

    // Funkturm rerolls
    if (tn.funkturmRerolled) {
      events.push({
        turnIndex: ti,
        playerIndex: tn.playerIndex,
        type: 'funkturm',
        label: `${turnLabel}: ${language === 'en' ? 'rerolled' : 'neu gewürfelt'} (${tn.roll})`,
      });
    }

    // Close decisions (lowest 5 confidence values in the game)
    // Collected separately and trimmed after the loop.
    if (tn.decisionDetail && tn.decisionDetail.confidence >= 0 && tn.decisionDetail.options.length >= 2) {
      const opts = tn.decisionDetail.options;
      const isWR = tn.decisionDetail.scoresAreWinRates !== false;
      const top2 = opts.slice(0, 2).map(o =>
        `${cardName(o.cardId)} ${isWR ? `${(o.score * 100).toFixed(0)}%` : o.score.toFixed(1)}`
      );
      events.push({
        turnIndex: ti,
        playerIndex: tn.playerIndex,
        type: 'close-decision',
        label: `${turnLabel}: ${top2.join(' vs ')}`,
        detail: `Δ${(tn.decisionDetail.confidence * 100).toFixed(1)}%`,
      });
    }
  }

  // Keep only the 5 closest decisions (lowest confidence) to avoid flooding
  const closeEvents = events.filter(e => e.type === 'close-decision');
  const otherEvents = events.filter(e => e.type !== 'close-decision');
  closeEvents.sort((a, b) => {
    const confA = parseFloat(a.detail?.replace('Δ', '').replace('%', '') ?? '0');
    const confB = parseFloat(b.detail?.replace('Δ', '').replace('%', '') ?? '0');
    return confA - confB;
  });
  const topClose = closeEvents.slice(0, 5);

  // Merge back and sort by turn index
  const merged = [...otherEvents, ...topClose];
  merged.sort((a, b) => a.turnIndex - b.turnIndex);
  return merged;
}

/** Background color class for card color. */
function cardBgClass(color?: string): string {
  switch (color) {
    case 'blau': return 'bg-machi-blue/15';
    case 'rot': return 'bg-machi-red/15';
    case 'grün': return 'bg-machi-green/15';
    case 'lila': return 'bg-machi-purple/15';
    case 'gelb': return 'bg-machi-yellow/15';
    default: return 'bg-machi-bg';
  }
}

export function H2hGameReplay({ game, engines, matchId, projects, language, onBack }: Props) {
  const { t } = useLocale();
  const [turnIdx, setTurnIdx] = useState(0);
  const [showDetail, setShowDetail] = useState(false);
  const nameKey = `name_${language}` as 'name_de' | 'name_en';
  const landmarkAbbr = language === 'en' ? LANDMARK_ABBR_EN : LANDMARK_ABBR_DE;

  const turn = game.turns[turnIdx] as H2hTurnLog | undefined;
  const totalTurns = game.turns.length;
  const playerCount = engines.length;

  const { inventories, coinHistory } = useMemo(
    () => buildInventoryTimeline(game, playerCount),
    [game, playerCount],
  );

  const insights = useMemo(
    () => computeInsights(game, playerCount),
    [game, playerCount],
  );

  const fortune = useMemo(
    () => computeDiceFortune(game, playerCount),
    [game, playerCount],
  );

  const events = useMemo(
    () => extractEvents(game, projects.byId, language),
    [game, projects, language],
  );

  const currentInv = inventories[turnIdx];

  /** Render a compact card inventory for one player. */
  const renderPlayerHand = (playerIdx: number) => {
    if (!currentInv) return null;
    const cards = currentInv[playerIdx];
    const landmarks = cards.filter(id => LANDMARK_IDS.includes(id));
    const nonLandmarks = cards.filter(id => !LANDMARK_IDS.includes(id));
    const counted = countCards(nonLandmarks);

    return (
      <div>
        {/* Landmarks */}
        <div className="flex gap-1 mb-1.5">
          {LANDMARK_IDS.map((lmId, i) => {
            const owned = landmarks.includes(lmId);
            const proj = projects.byId(lmId);
            return (
              <CardTooltip key={lmId} project={proj} language={language}>
                <span className={`inline-block w-6 h-6 rounded text-center text-xs font-bold leading-6 ${
                  owned ? 'bg-machi-yellow/30 text-machi-yellow' : 'bg-machi-bg text-machi-text-dim/30'
                }`}>
                  {landmarkAbbr[i]}
                </span>
              </CardTooltip>
            );
          })}
        </div>
        {/* Cards */}
        <div className="flex flex-wrap gap-1">
          {counted.map(([id, count]) => {
            const proj = projects.byId(id);
            const name = proj?.[nameKey] ?? proj?.name_de ?? id;
            return (
              <CardTooltip key={id} project={proj} language={language}>
                <span className={`inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] ${cardBgClass(proj?.color)} ${cardTextClass(proj?.color)}`}>
                  {categoryIconPath(proj?.category) && (
                    <img src={categoryIconPath(proj?.category)} alt="" className="w-3 h-3" />
                  )}
                  {name}{count > 1 && <span className="opacity-70">×{count}</span>}
                </span>
              </CardTooltip>
            );
          })}
        </div>
      </div>
    );
  };

  return (
    <div className="min-h-screen bg-machi-bg text-machi-text p-6">
      <div className="max-w-6xl mx-auto">
        {/* Header */}
        <div className="flex items-center gap-4 mb-4">
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
            {matchId && <span className="ml-2 font-mono text-xs text-machi-text-dim/50">{matchId}</span>}
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

        {/* Main 3-column layout: P1 Hand | Turn Detail | P2 Hand */}
        <div className="grid grid-cols-1 lg:grid-cols-[1fr_2fr_1fr] gap-4 mb-4">
          {/* P1 Hand */}
          <div className="bg-machi-surface rounded-xl p-4 border border-machi-border">
            <div className="flex items-center gap-2 mb-2">
              <span className="inline-block w-2.5 h-2.5 rounded-full bg-machi-accent" />
              <span className="text-xs font-semibold">P1: {engines[0]}</span>
              <span className="ml-auto text-xs font-mono text-machi-yellow">{coinHistory[turnIdx]?.[0] ?? 3}$</span>
            </div>
            {renderPlayerHand(0)}
          </div>

          {/* Turn Detail (center) */}
          {turn && (() => {
            const isP1 = turn.playerIndex === 0;
            const gradientBg = isP1
              ? 'linear-gradient(to right, #38bdf8, #334155 33%, #334155)'
              : 'linear-gradient(to left, #E879F9, #334155 33%, #334155)';
            return (
            <div key={`turn-border-${turnIdx}`} className="rounded-xl p-[5px]" style={{ background: gradientBg }}>
            <div className="bg-machi-surface rounded-[7px] p-5">
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
                    <div className="text-xs text-machi-accent mt-0.5">
                      {projects.byId('funkturm')?.[nameKey] ?? (language === 'en' ? 'Radio Tower' : 'Funkturm')} ↻
                    </div>
                  )}
                  {turn.rollLuck != null && (
                    <div className={`text-xs mt-0.5 font-mono ${
                      turn.rollLuck > 0.02 ? 'text-green-400' :
                      turn.rollLuck < -0.02 ? 'text-red-400' : 'text-machi-text-dim/60'
                    }`}>
                      Luck: {turn.rollLuck >= 0 ? '+' : ''}{(turn.rollLuck * 100).toFixed(1)}%
                    </div>
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
                    {turn.purchasedCardId ? (() => {
                      const proj = projects.byId(turn.purchasedCardId);
                      const name = proj?.[nameKey] ?? proj?.name_de ?? turn.purchasedCardId;
                      return (
                        <CardTooltip project={proj} language={language}>
                          <span className={`inline-flex items-center ${cardTextClass(proj?.color)}`}>
                            {categoryIconPath(proj?.category) && (
                              <img src={categoryIconPath(proj?.category)} alt="" className="w-3.5 h-3.5 mr-0.5" />
                            )}
                            {name}
                          </span>
                        </CardTooltip>
                      );
                    })() : t('h2h.save')}
                  </div>
                  <div className="text-xs text-machi-text-dim mt-0.5">
                    {turn.scoreIsWinRate !== false
                      ? `WR: ${(turn.purchaseWinRate * 100).toFixed(1)}%`
                      : `Score: ${turn.purchaseWinRate.toFixed(2)}`}
                  </div>
                </div>

                {/* Coins After */}
                <div className="bg-machi-bg rounded-lg p-3">
                  <div className="text-machi-text-dim text-xs mb-1">{t('h2h.coins')}</div>
                  <div className="font-mono">
                    {coinHistory[turnIdx]?.map((c: number, i: number) => (
                      <span key={i} className="mr-2">P{i + 1}: {c}</span>
                    ))}
                  </div>
                </div>
              </div>

              {(turn.bürohausSwap || turn.bürohausActivated) && (() => {
                const bürohausName = projects.byId('bürohaus')?.[nameKey] ?? (language === 'en' ? 'Business Center' : 'Bürohaus');
                if (turn.bürohausSwap) {
                  const parts = turn.bürohausSwap.split('→');
                  const ownId = parts[0]?.trim();
                  const oppId = parts[1]?.trim();
                  const ownProj = ownId ? projects.byId(ownId) : undefined;
                  const oppProj = oppId ? projects.byId(oppId) : undefined;
                  const ownName = ownProj?.[nameKey] ?? ownProj?.name_de ?? ownId;
                  const oppName = oppProj?.[nameKey] ?? oppProj?.name_de ?? oppId;
                  return (
                    <div className="mt-2 flex items-center gap-1.5 text-xs text-machi-purple">
                      <span className="font-semibold">{bürohausName}:</span>
                      <span className="inline-flex items-center gap-0.5">
                        {categoryIconPath(ownProj?.category) && (
                          <img src={categoryIconPath(ownProj?.category)} alt="" className="w-3 h-3" />
                        )}
                        <span className={cardTextClass(ownProj?.color)}>{ownName}</span>
                      </span>
                      <span className="text-machi-text-dim">→</span>
                      <span className="inline-flex items-center gap-0.5">
                        {categoryIconPath(oppProj?.category) && (
                          <img src={categoryIconPath(oppProj?.category)} alt="" className="w-3 h-3" />
                        )}
                        <span className={cardTextClass(oppProj?.color)}>{oppName}</span>
                      </span>
                    </div>
                  );
                }
                return (
                  <div className="mt-2 text-xs text-machi-purple/60">
                    {bürohausName}: {language === 'en' ? 'declined' : 'abgelehnt'}
                  </div>
                );
              })()}

              {/* Decision Detail (engine "why") */}
              {turn.decisionDetail && turn.decisionDetail.options.length > 0 && (
                <div className="mt-3 border-t border-machi-border/30 pt-2">
                  <button
                    onClick={() => setShowDetail(d => !d)}
                    className="text-xs text-machi-text-dim hover:text-machi-text transition flex items-center gap-1"
                  >
                    <span className={`inline-block transform transition-transform ${showDetail ? 'rotate-90' : ''}`}>▶</span>
                    {language === 'en' ? 'Decision detail' : 'Entscheidungsdetails'}
                    <span className="font-mono text-machi-text-dim/50 ml-1">
                      ({turn.decisionDetail.iterations} iter{turn.decisionDetail.confidence >= 0
                        ? `, ${(turn.decisionDetail.confidence * 100).toFixed(1)}% conf`
                        : ''})
                    </span>
                  </button>
                  {showDetail && (
                    <div className="mt-2 space-y-0.5">
                      {turn.decisionDetail.options.map((opt, i) => {
                        const isSave = opt.cardId === '_wait_';
                        const proj = isSave ? undefined : projects.byId(opt.cardId);
                        const name = isSave
                          ? (language === 'en' ? 'Save' : 'Sparen')
                          : (proj?.[nameKey] ?? proj?.name_de ?? opt.cardId);
                        const isWR = turn.decisionDetail!.scoresAreWinRates !== false;
                        const topScore = turn.decisionDetail!.options[0]?.score || 1;
                        const barWidth = Math.max(0, Math.min(100, isWR
                          ? opt.score * 100
                          : (opt.score / topScore) * 100
                        ));
                        return (
                          <div key={opt.cardId + '-' + i}
                            className="grid text-xs rounded px-2 py-1"
                            style={{ gridTemplateColumns: '16px 120px 1fr 52px 16px', gap: '6px', alignItems: 'center',
                              background: opt.chosen ? 'rgba(56,189,248,0.08)' : undefined }}
                          >
                            <span className="text-center text-machi-text-dim font-mono">{i + 1}</span>
                            <span className="flex items-center gap-1 truncate">
                              {!isSave && categoryIconPath(proj?.category) && (
                                <img src={categoryIconPath(proj?.category)} alt="" className="w-3 h-3 flex-shrink-0" />
                              )}
                              <span className={`truncate ${isSave ? 'text-machi-text-dim italic' : cardTextClass(proj?.color)}`}>
                                {name}
                              </span>
                            </span>
                            <div className="h-3 bg-machi-bg rounded-full overflow-hidden">
                              <div
                                className={`h-full rounded-full ${opt.chosen ? 'bg-machi-accent/60' : 'bg-machi-text-dim/20'}`}
                                style={{ width: `${barWidth}%` }}
                              />
                            </div>
                            <span className="font-mono text-right">
                              {isWR ? `${(opt.score * 100).toFixed(1)}%` : opt.score.toFixed(1)}
                            </span>
                            <span className="text-center">{opt.chosen ? <span className="text-machi-accent">←</span> : ''}</span>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}
            </div>
            </div>
          )})()}

          {/* P2 Hand */}
          <div className="bg-machi-surface rounded-xl p-4 border border-machi-border">
            <div className="flex items-center gap-2 mb-2">
              <span className="inline-block w-2.5 h-2.5 rounded-full bg-machi-purple" />
              <span className="text-xs font-semibold">P2: {engines[1]}</span>
              <span className="ml-auto text-xs font-mono text-machi-yellow">{coinHistory[turnIdx]?.[1] ?? 3}$</span>
            </div>
            {renderPlayerHand(1)}
          </div>
        </div>

        {/* Game Insights */}
        <div className="bg-machi-surface rounded-xl p-4 border border-machi-border mb-4">
          <h3 className="text-sm font-semibold mb-3">{t('h2h.gameInsights')}</h3>
          {/* Per-player stats side by side */}
          <div className="grid grid-cols-2 gap-3 text-xs mb-3">
            {engines.map((eng, i) => (
              <div key={i} className="bg-machi-bg rounded-lg p-3">
                <div className="flex items-center gap-1.5 mb-2">
                  <span className={`inline-block w-2 h-2 rounded-full ${i === 0 ? 'bg-machi-accent' : 'bg-machi-purple'}`} />
                  <span className="text-machi-text-dim font-semibold">P{i + 1}: {eng}</span>
                  <span className="ml-auto text-[10px] text-machi-text-dim/60">{insights.turnCounts[i]} {language === 'en' ? 'turns' : 'Züge'}</span>
                </div>
                <div className="grid grid-cols-2 gap-x-4 gap-y-0.5">
                  <div>{t('h2h.totalIncome')}: <span className="text-green-400 font-mono">{insights.totalIncome[i]}</span></div>
                  <div>{language === 'en' ? 'Lost to red' : 'Rot verloren'}: <span className="text-red-400 font-mono">{insights.totalLost[i]}</span></div>
                  <div>{language === 'en' ? 'Avg/turn' : 'Ø/Zug'}: <span className="text-green-400/80 font-mono">{insights.avgIncome[i].toFixed(1)}</span></div>
                  <div>{language === 'en' ? 'Best turn' : 'Bester Zug'}: <span className="font-mono text-machi-yellow">{insights.biggestIncome[i]}</span></div>
                  <div>{t('h2h.purchases')}: <span className="font-mono">{insights.totalPurchases[i]}</span></div>
                  <div>{t('h2h.saves')}: <span className="font-mono">{insights.saveTurns[i]}</span></div>
                  <div>1d6: <span className="font-mono">{insights.diceChoices1d6[i]}</span></div>
                  <div>2d6: <span className="font-mono">{insights.diceChoices2d6[i]}</span></div>
                  {insights.landmarkTurns[i].length > 0 && (
                    <div className="col-span-2 mt-1 text-machi-yellow/80">
                      {language === 'en' ? 'Landmarks' : 'Großprojekte'}: {insights.landmarkTurns[i].map((t: number) => `T${t}`).join(', ')}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
          {/* Luck Analysis (conditionally rendered when luck data present) */}
          {(() => {
            const hasLuck = game.turns.some(tn => tn.rollLuck != null);
            if (!hasLuck) return null;

            // Compute cumulative luck per player
            const cumLuck = [0, 0];
            const luckTimeData: { turn: number; P1: number; P2: number }[] = [];
            const totalLuck = [0, 0];
            let turnNum = 0;

            for (const tn of game.turns) {
              turnNum++;
              if (tn.rollLuck != null) {
                cumLuck[tn.playerIndex] += tn.rollLuck;
                totalLuck[tn.playerIndex] += tn.rollLuck;
              }
              luckTimeData.push({ turn: turnNum, P1: cumLuck[0], P2: cumLuck[1] });
            }

            // Luck-adjusted result
            // Raw WR proxy: winner got 1.0, loser got 0.0
            const winnerIdx = game.winnerIndex;
            const loserIdx = 1 - winnerIdx;
            const winnerLuckAdv = totalLuck[winnerIdx] - totalLuck[loserIdx];
            const isLuckyWin = winnerLuckAdv > 0.05;
            const isUnluckyLoss = winnerLuckAdv < -0.05;

            const chartTooltipStyle = {
              backgroundColor: '#1e1e2e',
              border: '1px solid rgba(255,255,255,0.1)',
              borderRadius: '6px',
              fontSize: '11px',
            };

            return (
              <>
                {/* 6c. Game-level luck summary */}
                <div className="bg-machi-bg rounded-lg p-3 text-xs mb-3">
                  <div className="text-machi-text-dim mb-2 font-semibold">{t('h2h.luckSummary')}</div>
                  <div className="flex flex-wrap gap-x-6 gap-y-1">
                    {engines.map((_eng, i) => (
                      <span key={i}>
                        <span className={i === 0 ? 'text-machi-accent' : 'text-fuchsia-400'}>P{i + 1}</span>
                        {' '}{t('h2h.totalLuck')}:{' '}
                        <span className={`font-mono font-semibold ${
                          totalLuck[i] > 0.02 ? 'text-green-400' :
                          totalLuck[i] < -0.02 ? 'text-red-400' : 'text-machi-text-dim'
                        }`}>
                          {totalLuck[i] >= 0 ? '+' : ''}{(totalLuck[i] * 100).toFixed(1)}%
                        </span>
                      </span>
                    ))}
                  </div>
                  {/* 6d. Luck-adjusted result */}
                  {(isLuckyWin || isUnluckyLoss) && (
                    <div className={`mt-1.5 text-[11px] font-semibold ${
                      isLuckyWin ? 'text-amber-400' : 'text-cyan-400'
                    }`}>
                      {isLuckyWin && `P${winnerIdx + 1}: ${t('h2h.luckyWin')} (+${(winnerLuckAdv * 100).toFixed(1)}% ${t('h2h.lucky').toLowerCase()})`}
                      {isUnluckyLoss && `P${loserIdx + 1}: ${t('h2h.unluckyLoss')} (${(winnerLuckAdv * 100).toFixed(1)}% ${t('h2h.unlucky').toLowerCase()})`}
                    </div>
                  )}
                </div>

                {/* 6b. Luck-over-time chart */}
                <div className="bg-machi-bg rounded-lg p-3 text-xs mb-3">
                  <div className="text-machi-text-dim mb-2 font-semibold">{t('h2h.luckOverTime')}</div>
                  <ResponsiveContainer width="100%" height={200}>
                    <LineChart data={luckTimeData} margin={{ top: 5, right: 10, bottom: 5, left: -10 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.07)" />
                      <XAxis dataKey="turn" tick={{ fontSize: 9, fill: 'rgba(255,255,255,0.4)' }} />
                      <YAxis
                        tick={{ fontSize: 9, fill: 'rgba(255,255,255,0.4)' }}
                        tickFormatter={(v: number) => `${(v * 100).toFixed(0)}%`}
                        width={40}
                      />
                      <ReferenceLine y={0} stroke="rgba(255,255,255,0.2)" strokeDasharray="3 3" />
                      <Tooltip
                        contentStyle={chartTooltipStyle}
                        labelFormatter={(v) => `Turn ${v}`}
                        formatter={(v) => [`${(Number(v) * 100).toFixed(1)}%`]}
                      />
                      <Line type="monotone" dataKey="P1" stroke="#38bdf8" strokeWidth={2} dot={false} />
                      <Line type="monotone" dataKey="P2" stroke="#E879F9" strokeWidth={2} dot={false} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </>
            );
          })()}
          {/* Game-wide events summary */}
          <div className="bg-machi-bg rounded-lg p-3 text-xs mb-3">
            <div className="text-machi-text-dim mb-1 font-semibold">{t('h2h.gameEvents')}</div>
            <div className="flex flex-wrap gap-x-4 gap-y-0.5">
              <div>{t('dice.doubles')}: <span className="font-mono">{insights.doublesCount}</span></div>
              {insights.funkturmCount > 0 && (
                <div>{projects.byId('funkturm')?.[nameKey] ?? (language === 'en' ? 'Radio Tower' : 'Funkturm')}: <span className="font-mono">{insights.funkturmCount}</span></div>
              )}
              {insights.bürohausCount > 0 && (
                <div>{projects.byId('bürohaus')?.[nameKey] ?? (language === 'en' ? 'Business Center' : 'Bürohaus')}: <span className="font-mono">{insights.bürohausCount}</span></div>
              )}
            </div>
          </div>
          {/* Dice Fortune */}
          <div className="bg-machi-bg rounded-lg p-3 text-xs mb-3">
            <div className="text-machi-text-dim mb-2 font-semibold">{t('h2h.diceFortune')}</div>
            <div className="grid gap-3" style={{ gridTemplateColumns: 'auto 1fr 1fr' }}>
              {/* Col 1: Frequency table */}
              {(() => {
                const allAmounts = new Set<number>();
                for (let p = 0; p < playerCount; p++) {
                  for (const k of fortune.ownIncomeFreq[p].keys()) allAmounts.add(k);
                  for (const k of fortune.oppIncomeFreq[p].keys()) allAmounts.add(k);
                }
                const sorted = [...allAmounts].sort((a, b) => a - b);
                if (sorted.length === 0) return <div />;

                return (
                  <div>
                    <div className="text-machi-text-dim/60 text-[10px] mb-1">{language === 'en' ? 'Income frequency' : 'Einkommenshäufigkeit'}</div>
                    <table className="text-center text-[10px] font-mono" style={{ borderSpacing: 0 }}>
                      <thead>
                        <tr className="text-machi-text-dim/50">
                          <td className="pr-1.5 text-left">{language === 'en' ? '¢' : '¢'}</td>
                          {sorted.map(amt => (
                            <td key={amt} className={`px-[3px] ${amt < 0 ? 'text-red-400/60' : amt === 0 ? '' : 'text-green-400/60'}`}>
                              {amt >= 0 ? `+${amt}` : amt}
                            </td>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {engines.map((_eng, i) => (
                          <React.Fragment key={i}>
                            <tr>
                              <td className={`pr-1.5 text-left whitespace-nowrap ${i === 0 ? 'text-machi-accent' : 'text-fuchsia-400'}`}>
                                P{i + 1}{language === 'en' ? ' own' : ' eig'}
                              </td>
                              {sorted.map(amt => {
                                const count = fortune.ownIncomeFreq[i].get(amt) ?? 0;
                                return (
                                  <td key={amt} className={`px-[3px] ${count > 0 ? 'text-machi-text' : 'text-machi-text-dim/20'}`}>
                                    {count || '·'}
                                  </td>
                                );
                              })}
                            </tr>
                            <tr>
                              <td className={`pr-1.5 text-left whitespace-nowrap ${i === 0 ? 'text-machi-accent/50' : 'text-fuchsia-400/50'}`}>
                                P{i + 1}{language === 'en' ? ' opp' : ' geg'}
                              </td>
                              {sorted.map(amt => {
                                const count = fortune.oppIncomeFreq[i].get(amt) ?? 0;
                                return (
                                  <td key={amt} className={`px-[3px] ${count > 0 ? 'text-machi-text' : 'text-machi-text-dim/20'}`}>
                                    {count || '·'}
                                  </td>
                                );
                              })}
                            </tr>
                          </React.Fragment>
                        ))}
                      </tbody>
                    </table>
                  </div>
                );
              })()}
              {/* Col 2: Own turns bar chart */}
              {(() => {
                // Compute shared Y-axis domain across all 4 series
                const allValues = [...fortune.ownIncome, ...fortune.oppIncome].flat();
                const globalMin = allValues.length > 0 ? Math.min(...allValues) : 0;
                const globalMax = allValues.length > 0 ? Math.max(...allValues) : 1;
                const domain: [number, number] = [globalMin, globalMax];

                // Build data for own-turn chart: each turn index has P1/P2 bars
                const ownLen = Math.max(fortune.ownIncome[0]?.length ?? 0, fortune.ownIncome[1]?.length ?? 0);
                const ownData = Array.from({ length: ownLen }, (_, i) => ({
                  turn: i + 1,
                  P1: fortune.ownIncome[0]?.[i] ?? 0,
                  P2: fortune.ownIncome[1]?.[i] ?? 0,
                }));

                const oppLen = Math.max(fortune.oppIncome[0]?.length ?? 0, fortune.oppIncome[1]?.length ?? 0);
                const oppData = Array.from({ length: oppLen }, (_, i) => ({
                  turn: i + 1,
                  P1: fortune.oppIncome[0]?.[i] ?? 0,
                  P2: fortune.oppIncome[1]?.[i] ?? 0,
                }));

                const chartTooltipStyle = {
                  backgroundColor: '#1e1e2e',
                  border: '1px solid rgba(255,255,255,0.1)',
                  borderRadius: '6px',
                  fontSize: '11px',
                };

                return (
                  <>
                    <div>
                      <div className="text-center text-machi-text-dim/60 text-[10px] mb-1">{t('h2h.ownTurns')}</div>
                      <ResponsiveContainer width="100%" height={120}>
                        <BarChart data={ownData} margin={{ top: 2, right: 2, bottom: 0, left: -20 }}>
                          <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.07)" />
                          <XAxis dataKey="turn" tick={false} height={4} />
                          <YAxis domain={domain} tick={{ fontSize: 9, fill: 'rgba(255,255,255,0.4)' }} width={30} />
                          <Tooltip
                            contentStyle={chartTooltipStyle}
                            labelFormatter={(v) => `Turn ${v}`}
                          />
                          <Bar dataKey="P1" fill="#38bdf8" maxBarSize={6} isAnimationActive={false} />
                          <Bar dataKey="P2" fill="#E879F9" maxBarSize={6} isAnimationActive={false} />
                        </BarChart>
                      </ResponsiveContainer>
                      <div className="flex justify-between text-[9px] text-machi-text-dim/50 mt-0.5 px-1">
                        {engines.map((_eng, i) => (
                          <span key={i} className={i === 0 ? 'text-machi-accent' : 'text-fuchsia-400'}>
                            P{i + 1} Ø{fortune.ownIncome[i].length > 0
                              ? (fortune.ownIncome[i].reduce((a, b) => a + b, 0) / fortune.ownIncome[i].length).toFixed(1)
                              : '0'}
                          </span>
                        ))}
                      </div>
                    </div>
                    {/* Col 3: Opponent turns bar chart */}
                    <div>
                      <div className="text-center text-machi-text-dim/60 text-[10px] mb-1">{t('h2h.oppTurns')}</div>
                      <ResponsiveContainer width="100%" height={120}>
                        <BarChart data={oppData} margin={{ top: 2, right: 2, bottom: 0, left: -20 }}>
                          <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.07)" />
                          <XAxis dataKey="turn" tick={false} height={4} />
                          <YAxis domain={domain} tick={{ fontSize: 9, fill: 'rgba(255,255,255,0.4)' }} width={30} />
                          <Tooltip
                            contentStyle={chartTooltipStyle}
                            labelFormatter={(v) => `Turn ${v}`}
                          />
                          <Bar dataKey="P1" fill="#1a6e8a" maxBarSize={6} isAnimationActive={false} />
                          <Bar dataKey="P2" fill="#8b3a96" maxBarSize={6} isAnimationActive={false} />
                        </BarChart>
                      </ResponsiveContainer>
                      <div className="flex justify-between text-[9px] text-machi-text-dim/50 mt-0.5 px-1">
                        {engines.map((_eng, i) => (
                          <span key={i} className={i === 0 ? 'text-machi-accent' : 'text-fuchsia-400'}>
                            P{i + 1} Ø{fortune.oppIncome[i].length > 0
                              ? (fortune.oppIncome[i].reduce((a, b) => a + b, 0) / fortune.oppIncome[i].length).toFixed(1)
                              : '0'}
                          </span>
                        ))}
                      </div>
                    </div>
                  </>
                );
              })()}
            </div>
          </div>
          {/* Event Timeline (inside Game Insights) */}
          {events.length > 0 && (
            <div className="mt-3 bg-machi-bg rounded-lg p-3">
              <div className="text-machi-text-dim text-xs mb-2">{language === 'en' ? 'Key Events' : 'Wichtige Ereignisse'}</div>
              <div className="space-y-1 text-xs max-h-48 overflow-y-auto">
                {events.map((ev, i) => {
                  const typeConfig = ev.type === 'landmark'
                    ? { bg: 'bg-amber-500/20 text-amber-400', tag: '★' }
                    : ev.type === 'burohaus'
                    ? { bg: 'bg-fuchsia-500/20 text-fuchsia-400', tag: '⇄' }
                    : ev.type === 'funkturm'
                    ? { bg: 'bg-cyan-500/20 text-cyan-400', tag: '↻' }
                    : { bg: 'bg-orange-500/20 text-orange-300', tag: '⚖' };
                  return (
                    <div
                      key={i}
                      className="flex items-center gap-2 px-2 py-1 rounded hover:bg-machi-surface/50 cursor-pointer"
                      onClick={() => setTurnIdx(ev.turnIndex)}
                    >
                      <span className={`inline-flex items-center justify-center w-4 h-4 rounded text-[9px] font-bold flex-shrink-0 ${typeConfig.bg}`}>
                        {typeConfig.tag}
                      </span>
                      <span className={`font-mono w-5 flex-shrink-0 ${ev.playerIndex === 0 ? 'text-machi-accent' : 'text-fuchsia-400'}`}>
                        P{ev.playerIndex + 1}
                      </span>
                      <span className="truncate">{ev.label}</span>
                      {ev.detail && <span className="text-machi-text-dim ml-auto flex-shrink-0">{ev.detail}</span>}
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>

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

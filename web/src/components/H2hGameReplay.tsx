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

/** Purple steal cards — victims should not have losses attributed to these in their own card table. */
const STEAL_PURPLE_CARDS = new Set(['stadion', 'fernsehsender']);
const LANDMARK_IDS = ['bahnhof', 'einkaufszentrum', 'freizeitpark', 'funkturm'];
const LANDMARK_ABBR_DE = ['B', 'E', 'F', 'F'];
const LANDMARK_ABBR_EN = ['T', 'S', 'A', 'R'];
const STARTER_CARDS = ['weizenfeld', 'bäckerei'];

/** Pip positions for die face rendering (3×3 grid). */
const PIP_POSITIONS: Record<number, [number, number][]> = {
  1: [[1, 1]],
  2: [[0, 2], [2, 0]],
  3: [[0, 2], [1, 1], [2, 0]],
  4: [[0, 0], [0, 2], [2, 0], [2, 2]],
  5: [[0, 0], [0, 2], [1, 1], [2, 0], [2, 2]],
  6: [[0, 0], [0, 2], [1, 0], [1, 2], [2, 0], [2, 2]],
};

/** Small die face for the replay turn detail. */
function DieFaceSmall({ value }: { value: number }) {
  const pips = PIP_POSITIONS[value] ?? [];
  return (
    <span className="inline-grid grid-cols-3 grid-rows-3 w-7 h-7 p-1 gap-0 rounded border border-machi-border bg-machi-surface">
      {Array.from({ length: 9 }, (_, i) => {
        const row = Math.floor(i / 3);
        const col = i % 3;
        const hasPip = pips.some(([r, c]) => r === row && c === col);
        return (
          <span key={i} className={`w-full h-full rounded-full ${hasPip ? 'bg-machi-text' : ''}`} />
        );
      })}
    </span>
  );
}

/** Decompose a 2d6 sum into two valid die values. */
function decompose2d6(sum: number, isDoubles: boolean): [number, number] {
  if (isDoubles) {
    const v = sum / 2;
    return [v, v];
  }
  // Pick a balanced split: min die as close to sum/2 as possible, within [1,6]
  const d1 = Math.max(1, Math.min(6, Math.ceil(sum / 2)));
  const d2 = sum - d1;
  if (d2 >= 1 && d2 <= 6) return [d1, d2];
  // Fallback for edge cases
  return [Math.max(1, sum - 6), Math.min(6, sum - 1)];
}

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

/** Chart stroke color for a card color. */
function cardChartColor(color?: string): string {
  switch (color) {
    case 'blau': return '#3B82F6';
    case 'rot': return '#EF4444';
    case 'grün': return '#22C55E';
    case 'lila': return '#A855F7';
    case 'gelb': return '#EAB308';
    default: return '#94a3b8';
  }
}

interface CardValueEntry {
  cardId: string;
  color: string;
  /** Per-copy cost (from project definition). */
  unitCost: number;
  /** Total cost across all copies. */
  totalCost: number;
  copies: number;
  totalIncome: number;
  /** Per-copy turns owned (one entry per copy, descending by turns). */
  perCopyTurns: number[];
  turnsOwned: number;
  incomePerTurn: number;
  roi: number;
  expectedIncome: number | null;  // null if no purchasedCardExpectedEv data
  actualVsExpected: number | null;
}

/** Compute per-card value data from cardIncome fields in game turns. */
function computeCardValueData(
  game: H2hGameLog,
  playerIdx: number,
  inventoryTimeline: string[][][],
  byId: (id: string) => ProjectDef | undefined,
) {
  // Track cumulative income per card over turns (for chart)
  const cumIncome: Record<string, number> = {};
  const chartData: Record<string, number>[] = [];  // one entry per turn
  // Track purchase turn per card copy (all copies, not just first)
  const purchaseTurns: Record<string, number[]> = {};
  // Track expected EV per card copy at purchase time (parallel to purchaseTurns)
  const expectedEvPerCopy: Record<string, (number | null)[]> = {};
  // Track total income per card
  const totalIncome: Record<string, number> = {};

  // Determine starters (turn 0 inventory)
  for (const cardId of STARTER_CARDS) {
    purchaseTurns[cardId] = [0];
    expectedEvPerCopy[cardId] = [null]; // no EV data for starters
    cumIncome[cardId] = 0;
    totalIncome[cardId] = 0;
  }

  let playerTurnCount = 0;

  for (let ti = 0; ti < game.turns.length; ti++) {
    const tn = game.turns[ti];
    if (tn.playerIndex === playerIdx) playerTurnCount++;

    // If this turn has cardIncome data, accumulate only for cards this player owns
    if (tn.cardIncome) {
      // Get current inventory for this player (from previous turn's inventory or starters)
      const ownedCards = ti > 0 && inventoryTimeline[ti - 1]
        ? inventoryTimeline[ti - 1][playerIdx]
        : [...STARTER_CARDS];
      const ownedSet = new Set(ownedCards);

      for (const [cardId, deltas] of Object.entries(tn.cardIncome)) {
        const delta = deltas[playerIdx] ?? 0;
        // Only track income from cards this player actually owns.
        // For steal-type purple cards (Stadion/Fernsehsender), only count positive income
        // (the roller's gain) — never attribute a victim's loss to that card in the victim's table.
        if (delta !== 0 && ownedSet.has(cardId)) {
          if (delta < 0 && STEAL_PURPLE_CARDS.has(cardId) && tn.playerIndex !== playerIdx) continue;
          cumIncome[cardId] = (cumIncome[cardId] ?? 0) + delta;
          totalIncome[cardId] = (totalIncome[cardId] ?? 0) + delta;
        }
      }
    }

    // Track purchases by this player (every copy, not just first)
    if (tn.playerIndex === playerIdx && tn.purchasedCardId && !LANDMARK_IDS.includes(tn.purchasedCardId)) {
      if (!purchaseTurns[tn.purchasedCardId]) {
        purchaseTurns[tn.purchasedCardId] = [];
        expectedEvPerCopy[tn.purchasedCardId] = [];
      }
      purchaseTurns[tn.purchasedCardId].push(ti);
      expectedEvPerCopy[tn.purchasedCardId].push(tn.purchasedCardExpectedEv ?? null);
      cumIncome[tn.purchasedCardId] = cumIncome[tn.purchasedCardId] ?? 0;
      totalIncome[tn.purchasedCardId] = totalIncome[tn.purchasedCardId] ?? 0;
    }

    // Snapshot cumulative income for chart
    const snapshot: Record<string, number> = { turn: ti + 1 } as any;
    for (const cardId of Object.keys(cumIncome)) {
      snapshot[cardId] = cumIncome[cardId];
    }
    chartData.push(snapshot);
  }

  // Count turns owned per card copy (from each copy's purchase to end)
  // Also pair with per-copy EV, then sort by turns descending (keeping pairs aligned)
  const perCopyTurns: Record<string, number[]> = {};
  const perCopyEv: Record<string, (number | null)[]> = {};
  for (const [cardId, pTurns] of Object.entries(purchaseTurns)) {
    const evs = expectedEvPerCopy[cardId] ?? [];
    const pairs: { turns: number; ev: number | null }[] = pTurns.map((pTurn, idx) => {
      let owned = 0;
      for (let ti = pTurn; ti < game.turns.length; ti++) {
        if (game.turns[ti].playerIndex === playerIdx) owned++;
      }
      return { turns: owned, ev: evs[idx] ?? null };
    });
    // Sort descending by turns (longest-held copy first)
    pairs.sort((a, b) => b.turns - a.turns);
    perCopyTurns[cardId] = pairs.map(p => p.turns);
    perCopyEv[cardId] = pairs.map(p => p.ev);
  }

  // Build summary entries
  const allCards = new Set([...Object.keys(totalIncome), ...Object.keys(purchaseTurns)]);
  const entries: CardValueEntry[] = [];
  for (const cardId of allCards) {
    const proj = byId(cardId);
    const unitCost = proj?.cost ?? 0;
    const copyTurns = perCopyTurns[cardId] ?? [];
    const copyEvs = perCopyEv[cardId] ?? [];
    const copies = copyTurns.length || 1;
    const totalCost = unitCost * copies;
    const income = totalIncome[cardId] ?? 0;
    const turns = copyTurns.length > 0 ? copyTurns[0] : 0; // longest-held copy
    const totalCopyTurns = copyTurns.reduce((s, t) => s + t, 0);
    const ipt = totalCopyTurns > 0 ? income / totalCopyTurns : 0;
    const roi = totalCost > 0 ? income / totalCost : 0;
    // Expected income: sum of (ev_i × turns_i) for each copy with known EV
    let expIncome: number | null = null;
    const hasAnyEv = copyEvs.some(e => e != null);
    if (hasAnyEv && totalCopyTurns > 0) {
      expIncome = 0;
      for (let i = 0; i < copies; i++) {
        const ev = copyEvs[i];
        if (ev != null) {
          expIncome += ev * (copyTurns[i] ?? 0);
        }
      }
    }
    const delta = expIncome != null ? income - expIncome : null;

    entries.push({
      cardId,
      color: proj?.color ?? '',
      unitCost,
      totalCost,
      copies,
      totalIncome: income,
      perCopyTurns: copyTurns,
      turnsOwned: turns,
      incomePerTurn: ipt,
      roi,
      expectedIncome: expIncome,
      actualVsExpected: delta,
    });
  }

  // Sort by total income descending
  entries.sort((a, b) => b.totalIncome - a.totalIncome);

  // Collect all card IDs that appear in chart (those with non-zero income)
  const chartCardIds = entries.filter(e => e.totalIncome !== 0).map(e => e.cardId);

  return { chartData, entries, chartCardIds };
}

export function H2hGameReplay({ game, engines, matchId, projects, language, onBack }: Props) {
  const { t } = useLocale();
  const [turnIdx, setTurnIdx] = useState(0);
  const [showDetail, setShowDetail] = useState(false);
  const [cardValuePlayer, setCardValuePlayer] = useState(0);
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

  // Card Value data (only when cardIncome data exists)
  const hasCardIncome = useMemo(
    () => game.turns.some(tn => tn.cardIncome != null),
    [game],
  );
  const cardValueData = useMemo(
    () => hasCardIncome ? computeCardValueData(game, cardValuePlayer, inventories, projects.byId) : null,
    [game, cardValuePlayer, inventories, projects, hasCardIncome],
  );

  // Compute own-turn and opponent-turn indices up to turnIdx (for Dice Fortune chart indicators)
  const fortuneTurnIndices = useMemo(() => {
    // ownTurnIndex[p] = how many own turns player p has had through turnIdx (1-indexed for chart)
    // oppTurnIndex[p] = how many opponent turns player p has experienced through turnIdx
    const ownCount = Array(playerCount).fill(0);
    const oppCount = Array(playerCount).fill(0);
    for (let ti = 0; ti <= Math.min(turnIdx, game.turns.length - 1); ti++) {
      const roller = game.turns[ti].playerIndex;
      ownCount[roller]++;
      for (let p = 0; p < playerCount; p++) {
        if (p !== roller) oppCount[p]++;
      }
    }
    return { ownCount, oppCount };
  }, [turnIdx, game.turns, playerCount]);

  // Current game turn number for chart reference lines (1-indexed)
  const currentChartTurn = turnIdx + 1;

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
                  <div className="flex items-center gap-1.5 flex-nowrap">
                    {turn.diceCount === 1 ? (
                      <DieFaceSmall value={turn.roll} />
                    ) : (() => {
                      const [d1, d2] = decompose2d6(turn.roll, turn.isDoubles);
                      return <><DieFaceSmall value={d1} /><DieFaceSmall value={d2} /></>;
                    })()}
                    <span className="font-mono text-lg whitespace-nowrap">={turn.roll}</span>
                  </div>
                  {(turn.isDoubles || turn.funkturmRerolled) && (
                    <div className="flex items-center gap-2 mt-1">
                      {turn.isDoubles && <span className="text-machi-yellow text-xs font-bold">⚄⚄ Doubles</span>}
                      {turn.funkturmRerolled && <span className="text-machi-accent text-xs">↻ Reroll</span>}
                    </div>
                  )}
                  {turn.rollLuck != null && (
                    <div className={`text-xs mt-1 font-mono ${
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
                  <div className="font-mono space-y-0.5">
                    {engines.map((_, i) => {
                      const before = turnIdx > 0 ? (coinHistory[turnIdx - 1]?.[i] ?? 3) : 3;
                      const delta = turn.coinDeltas?.[i] ?? 0;
                      return (
                        <div key={i} className={
                          delta > 0 ? 'text-green-400' :
                          delta < 0 ? 'text-red-400' : 'text-machi-text-dim'
                        }>
                          P{i + 1}: {before}{delta >= 0 ? '+' : ''}{delta}
                        </div>
                      );
                    })}
                  </div>
                  {turn.cardIncome && (
                    <div className="mt-1.5 space-y-0.5">
                      {Object.entries(turn.cardIncome).map(([cardId, deltas]) => {
                        const anyNonZero = deltas.some(d => d !== 0);
                        if (!anyNonZero) return null;
                        const proj = projects.byId(cardId);
                        const name = proj?.[nameKey] ?? proj?.name_de ?? cardId;
                        return (
                          <div key={cardId} className="flex items-center gap-1 text-[10px]">
                            <span className={`${cardTextClass(proj?.color)} truncate w-20`}>{name}</span>
                            {deltas.map((d, i) => (
                              <span key={i} className={`font-mono text-right ${
                                i === turn.playerIndex ? 'w-14 font-semibold' : 'w-14'
                              } ${
                                d > 0 ? 'text-green-400/80' : d < 0 ? 'text-red-400/80' : 'text-machi-text-dim/30'
                              }`}>
                                <span className="text-machi-text-dim/50 mr-0.5">P{i + 1}</span>
                                {d >= 0 ? '+' : ''}{d}
                              </span>
                            ))}
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>

                {/* Purchase */}
                <div className="bg-machi-bg rounded-lg p-3">
                  <div className="text-machi-text-dim text-xs mb-1">{t('h2h.purchase')}</div>
                  <div className="font-mono text-sm">
                    {turn.purchasedCardId ? (() => {
                      const proj = projects.byId(turn.purchasedCardId);
                      const name = proj?.[nameKey] ?? proj?.name_de ?? turn.purchasedCardId;
                      return (
                        <CardTooltip project={proj} language={language}>
                          <span className={`inline-flex items-center flex-wrap ${cardTextClass(proj?.color)}`}>
                            {categoryIconPath(proj?.category) && (
                              <img src={categoryIconPath(proj?.category)} alt="" className="w-3.5 h-3.5 mr-0.5 shrink-0" />
                            )}
                            {name}{proj?.cost != null && <span className="text-machi-text-dim ml-1">({proj.cost}$)</span>}
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
                  <div className="font-mono space-y-0.5">
                    {coinHistory[turnIdx]?.map((c: number, i: number) => (
                      <div key={i}>P{i + 1}: {c}</div>
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
                            <CardTooltip project={proj} language={language}>
                            <span className="flex items-center gap-1 truncate">
                              {!isSave && categoryIconPath(proj?.category) && (
                                <img src={categoryIconPath(proj?.category)} alt="" className="w-3 h-3 flex-shrink-0" />
                              )}
                              <span className={`truncate ${isSave ? 'text-machi-text-dim italic' : cardTextClass(proj?.color)}`}>
                                {name}
                              </span>
                            </span>
                            </CardTooltip>
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
                  <div>{language === 'en' ? 'Lost to opponents' : 'Verloren'}: <span className="text-red-400 font-mono">{insights.totalLost[i]}</span></div>
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
            // winnerLuckAdv > 0 means winner had more luck; < 0 means winner overcame luck deficit
            const winnerIdx = game.winnerIndex;
            const loserIdx = 1 - winnerIdx;
            const winnerLuckAdv = totalLuck[winnerIdx] - totalLuck[loserIdx];
            const isLuckyWin = winnerLuckAdv > 0.05;
            const isSkilledWin = winnerLuckAdv < -0.05;

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
                  {(isLuckyWin || isSkilledWin) && (
                    <div className={`mt-1.5 text-[11px] font-semibold ${
                      isLuckyWin ? 'text-amber-400' : 'text-cyan-400'
                    }`}>
                      {isLuckyWin && `P${winnerIdx + 1}: ${t('h2h.luckyWin')} (+${(winnerLuckAdv * 100).toFixed(1)}% ${t('h2h.lucky').toLowerCase()})`}
                      {isSkilledWin && `P${winnerIdx + 1}: ${t('h2h.skilledWin')} (${(winnerLuckAdv * 100).toFixed(1)}% ${t('h2h.unlucky').toLowerCase()})`}
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
                      <ReferenceLine x={currentChartTurn} stroke="rgba(255,255,255,0.35)" strokeDasharray="4 3" strokeWidth={1} />
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
                          {fortuneTurnIndices.ownCount[0] > 0 && (
                            <ReferenceLine x={Math.max(...fortuneTurnIndices.ownCount)} stroke="rgba(255,255,255,0.35)" strokeDasharray="4 3" strokeWidth={1} />
                          )}
                          <Tooltip
                            contentStyle={chartTooltipStyle}
                            cursor={{ fill: 'rgba(255,255,255,0.05)' }}
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
                          {fortuneTurnIndices.oppCount[0] > 0 && (
                            <ReferenceLine x={Math.max(...fortuneTurnIndices.oppCount)} stroke="rgba(255,255,255,0.35)" strokeDasharray="4 3" strokeWidth={1} />
                          )}
                          <Tooltip
                            contentStyle={chartTooltipStyle}
                            cursor={{ fill: 'rgba(255,255,255,0.05)' }}
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
          {/* Card Value Analysis (conditionally rendered when cardIncome data exists) */}
          {hasCardIncome && cardValueData && (
            <div className="bg-machi-bg rounded-lg p-3 text-xs mb-3">
              <div className="flex items-center gap-3 mb-2">
                <span className="text-machi-text-dim font-semibold">{t('h2h.cardValue')}</span>
                <div className="flex gap-1 ml-auto">
                  {engines.map((eng, i) => (
                    <button
                      key={i}
                      onClick={() => setCardValuePlayer(i)}
                      className={`px-2 py-0.5 rounded text-[10px] font-mono transition ${
                        cardValuePlayer === i
                          ? (i === 0 ? 'bg-machi-accent/20 text-machi-accent' : 'bg-fuchsia-500/20 text-fuchsia-400')
                          : 'text-machi-text-dim/50 hover:text-machi-text-dim'
                      }`}
                    >
                      P{i + 1}: {eng}
                    </button>
                  ))}
                </div>
              </div>

              {/* Cumulative income chart */}
              {cardValueData.chartCardIds.length > 0 && (
                <div className="mb-3">
                  <div className="text-machi-text-dim/60 text-[10px] mb-1">{t('h2h.cardValueCumulative')}</div>
                  <ResponsiveContainer width="100%" height={200}>
                    <LineChart data={cardValueData.chartData} margin={{ top: 5, right: 10, bottom: 5, left: -10 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.07)" />
                      <XAxis
                        dataKey="turn"
                        tick={{ fontSize: 9, fill: 'rgba(255,255,255,0.4)' }}
                      />
                      <YAxis
                        tick={{ fontSize: 9, fill: 'rgba(255,255,255,0.4)' }}
                        width={30}
                      />
                      <ReferenceLine x={currentChartTurn} stroke="rgba(255,255,255,0.35)" strokeDasharray="4 3" strokeWidth={1} />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: '#1e1e2e',
                          border: '1px solid rgba(255,255,255,0.1)',
                          borderRadius: '6px',
                          fontSize: '11px',
                        }}
                        labelFormatter={(v) => `Turn ${v}`}
                        itemSorter={(item) => -(typeof item.value === 'number' ? item.value : 0)}
                      />
                      {cardValueData.chartCardIds.map(cardId => {
                        const proj = projects.byId(cardId);
                        const name = proj?.[nameKey] ?? proj?.name_de ?? cardId;
                        return (
                          <Line
                            key={cardId}
                            type="monotone"
                            dataKey={cardId}
                            name={name}
                            stroke={cardChartColor(proj?.color)}
                            strokeWidth={1.5}
                            dot={false}
                            strokeOpacity={0.8}
                          />
                        );
                      })}
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              )}

              {/* Summary table */}
              <div>
                <div className="text-machi-text-dim/60 text-[10px] mb-1">{t('h2h.cardValueSummary')}</div>
                <div className="overflow-x-auto">
                  <table className="w-full text-[10px] font-mono">
                    <thead>
                      <tr className="text-machi-text-dim/60 border-b border-machi-border/30">
                        <th className="text-left py-1 px-1">{t('h2h.cardName')}</th>
                        <th className="text-right py-1 px-1">×</th>
                        <th className="text-right py-1 px-1">{t('h2h.cardCost')}</th>
                        <th className="text-right py-1 px-1">{t('h2h.cardTotalIncome')}</th>
                        <th className="text-right py-1 px-1">{t('h2h.cardTurnsOwned')}</th>
                        <th className="text-right py-1 px-1">{t('h2h.cardIncomePerTurn')}</th>
                        <th className="text-right py-1 px-1">{t('h2h.cardRoi')}</th>
                        <th className="text-right py-1 px-1">{t('h2h.cardExpectedIncome')}</th>
                        <th className="text-right py-1 px-1">{t('h2h.cardActualVsExpected')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {cardValueData.entries.map(entry => {
                        const proj = projects.byId(entry.cardId);
                        const name = proj?.[nameKey] ?? proj?.name_de ?? entry.cardId;
                        const showPerCopy = entry.copies > 1;
                        // Per-copy ROI: income proportional to turns, divided by unit cost
                        const totalCopyTurns = entry.perCopyTurns.reduce((s, t) => s + t, 0);
                        const perCopyRoi = showPerCopy && entry.unitCost > 0 && totalCopyTurns > 0
                          ? entry.perCopyTurns.map(t => entry.totalIncome * t / totalCopyTurns / entry.unitCost)
                          : null;
                        return (
                          <tr key={entry.cardId} className="border-b border-machi-border/10 hover:bg-machi-surface/30">
                            <td className="py-1 px-1">
                              <span className={`inline-flex items-center gap-0.5 ${cardTextClass(proj?.color)}`}>
                                {categoryIconPath(proj?.category) && (
                                  <img src={categoryIconPath(proj?.category)} alt="" className="w-3 h-3" />
                                )}
                                {name}
                              </span>
                            </td>
                            <td className="text-right py-1 px-1 text-machi-text-dim">
                              {entry.copies > 1 ? `${entry.copies}×` : ''}
                            </td>
                            <td className="text-right py-1 px-1 text-machi-text-dim">{entry.totalCost}</td>
                            <td className={`text-right py-1 px-1 ${entry.totalIncome > 0 ? 'text-green-400' : entry.totalIncome < 0 ? 'text-red-400' : 'text-machi-text-dim'}`}>
                              {entry.totalIncome}
                            </td>
                            <td className="text-right py-1 px-1 text-machi-text-dim">
                              {showPerCopy ? entry.perCopyTurns.join('/') : entry.turnsOwned}
                            </td>
                            <td className="text-right py-1 px-1 text-machi-text-dim">
                              {entry.incomePerTurn.toFixed(2)}
                            </td>
                            <td className="text-right py-1 px-1">
                              {entry.totalCost > 0
                                ? perCopyRoi
                                  ? perCopyRoi.map((roi, ri) => (
                                      <span key={ri} className={roi >= 1 ? 'text-green-400' : 'text-machi-text-dim'}>
                                        {roi.toFixed(1)}x{ri < perCopyRoi.length - 1 ? '/' : ''}
                                      </span>
                                    ))
                                  : <span className={entry.roi >= 1 ? 'text-green-400' : 'text-machi-text-dim'}>{entry.roi.toFixed(1)}x</span>
                                : '-'}
                            </td>
                            <td className="text-right py-1 px-1 text-machi-text-dim">
                              {entry.expectedIncome != null ? entry.expectedIncome.toFixed(1) : '-'}
                            </td>
                            <td className={`text-right py-1 px-1 ${
                              entry.actualVsExpected != null
                                ? (entry.actualVsExpected > 0.5 ? 'text-green-400' : entry.actualVsExpected < -0.5 ? 'text-red-400' : 'text-machi-text-dim')
                                : 'text-machi-text-dim'
                            }`}>
                              {entry.actualVsExpected != null
                                ? `${entry.actualVsExpected >= 0 ? '+' : ''}${entry.actualVsExpected.toFixed(1)}`
                                : '-'}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}
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

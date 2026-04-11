/**
 * PvAI Board Game Screen — 3-row immersive layout for Player vs AI mode.
 *
 * Top row:    AI player hand (name, coins, dice result, landmarks, last-turn summary)
 * Middle row: Card market grid (all regular + landmark cards, supply counts)
 * Bottom row: Human player hand (name, coins, Roll button, dice result, coin flow)
 *
 * The assistant panel is intentionally absent — PvAI is a pure skill test.
 */

import { useState, useCallback, useEffect, useRef } from 'react';
import { useEngine } from '../hooks/useEngine';
import { useRollPreview } from '../hooks/useRollPreview';
import type { UseSessionReturn } from '../hooks/useSession';
import type { Settings } from '../hooks/useSettings';
import type { UsePvAiReturn } from '../hooks/usePlayerVsAi';
import type { ProjectDef, BürohausRequest } from '../api/types';
import { DieFace } from './DiceInterface';
import { CardTooltip } from './CardTooltip';
import { BürohausPanel } from './BürohausPanel';
import { cardTextClass, categoryIconPath } from '../utils/cardDisplay';
import * as api from '../api/client';

const LANDMARK_IDS = ['bahnhof', 'einkaufszentrum', 'freizeitpark', 'funkturm'];

// Gradient colors matching H2hGameReplay.tsx pattern
const HUMAN_GRADIENT = 'linear-gradient(to right, #38bdf8, #1e293b 33%, #1e293b)';
const AI_GRADIENT    = 'linear-gradient(to right, #E879F9, #1e293b 33%, #1e293b)';

interface Props {
  session: UseSessionReturn;
  settings: Settings;
  projects: { projects: ProjectDef[]; byId: (id: string) => ProjectDef | undefined };
  pvai: UsePvAiReturn;
}

// ─── Roll animation ──────────────────────────────────────────────────────────

const ROLL_ANIM_DURATION_MS = 450;
const ROLL_ANIM_INTERVAL_MS = 60;

function useRollAnimation(count: 1 | 2) {
  const [animating, setAnimating] = useState(false);
  const [showFace1, setShowFace1] = useState<number | null>(null);
  const [showFace2, setShowFace2] = useState<number | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const roll = useCallback((): Promise<{ die1: number; die2: number | null }> => {
    const result = {
      die1: Math.floor(Math.random() * 6) + 1,
      die2: count === 2 ? Math.floor(Math.random() * 6) + 1 : null,
    };
    setAnimating(true);
    setShowFace1(null);
    setShowFace2(null);

    return new Promise(resolve => {
      timerRef.current = setInterval(() => {
        setShowFace1(Math.floor(Math.random() * 6) + 1);
        if (count === 2) setShowFace2(Math.floor(Math.random() * 6) + 1);
      }, ROLL_ANIM_INTERVAL_MS);

      timeoutRef.current = setTimeout(() => {
        if (timerRef.current) clearInterval(timerRef.current);
        setShowFace1(result.die1);
        setShowFace2(result.die2);
        setAnimating(false);
        resolve(result);
      }, ROLL_ANIM_DURATION_MS);
    });
  }, [count]);

  useEffect(() => () => {
    if (timerRef.current) clearInterval(timerRef.current);
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
  }, []);

  return { roll, animating, showFace1, showFace2 };
}

// ─── Shared helpers ──────────────────────────────────────────────────────────

function cardName(id: string | null | undefined, projects: Props['projects'], language: 'de' | 'en'): string {
  if (!id) return '—';
  const p = projects.byId(id);
  return p?.[`name_${language}` as 'name_de' | 'name_en'] ?? p?.name_de ?? id;
}

function supplyCount(projectId: string, session: UseSessionReturn, playerCount: number): number {
  const s = session.session!;
  const totalOwned = s.state.players.reduce(
    (sum, pl) => sum + pl.ownedIds.filter(id => id === projectId).length, 0
  );
  const starterCopies = (projectId === 'weizenfeld' || projectId === 'bäckerei') ? playerCount : 0;
  return 6 - (totalOwned - starterCopies);
}

// ─── Coin flow display ───────────────────────────────────────────────────────

/** Shows all players' coin deltas from a roll. Compact column. */
function RollCoinBreakdown({
  players,
  coinDeltas,
}: {
  players: { name: string }[];
  coinDeltas: number[];
}) {
  const anyNonZero = coinDeltas.some(d => d !== 0);
  if (!anyNonZero) return null;
  return (
    <div className="flex flex-col gap-0.5 text-[10px] font-mono">
      {players.map((p, i) => {
        const d = coinDeltas[i] ?? 0;
        return (
          <div key={i} className="flex items-center gap-1">
            <span className="text-machi-text-dim truncate max-w-[56px]">{p.name}</span>
            <span className={`font-semibold ${d > 0 ? 'text-machi-green' : d < 0 ? 'text-machi-red' : 'text-machi-text-dim'}`}>
              {d > 0 ? `+${d}` : d < 0 ? String(d) : '±0'}
            </span>
          </div>
        );
      })}
    </div>
  );
}

// ─── Player Row ──────────────────────────────────────────────────────────────

interface PlayerRowProps {
  name: string;
  coins: number;
  coinDelta?: number | null; // +N/-N preview shown next to coin count
  ownedIds: string[];
  isActive: boolean;
  gradient: string;
  projects: Props['projects'];
  language: 'de' | 'en';
  // All players' coin deltas (shown as roll breakdown left of dice)
  allPlayers?: { name: string }[];
  rollCoinDeltas?: number[] | null;
  // Simple last-turn text summary
  lastTurnText?: string | null;
  // Whether to show lastTurnText even when active (for AI row: always visible)
  alwaysShowSummary?: boolean;
  isAiThinking?: boolean;
  diceResult?: { die1: number; die2: number | null } | null;
  diceAnimating?: boolean; // true = cycling random faces (animation in progress)
  diceAnimFace1?: number | null; // animated face value (during animation)
  diceAnimFace2?: number | null;
  diceHighlighted?: boolean; // true = dice face is highlighted (active result)
  children?: React.ReactNode;
}

function PlayerRow({
  name, coins, coinDelta, ownedIds, isActive, gradient,
  projects, language,
  allPlayers, rollCoinDeltas, lastTurnText, alwaysShowSummary, isAiThinking,
  diceResult, diceAnimating, diceAnimFace1, diceAnimFace2, diceHighlighted,
  children,
}: PlayerRowProps) {
  // During animation, show animated faces; after, show settled result
  const face1 = diceAnimating ? (diceAnimFace1 ?? null) : (diceResult?.die1 ?? null);
  const face2 = diceAnimating ? (diceAnimFace2 ?? null) : (diceResult?.die2 ?? null);
  const nonLandmarkIds = ownedIds.filter(id => !LANDMARK_IDS.includes(id));

  const cardCounts: Record<string, number> = {};
  for (const id of nonLandmarkIds) cardCounts[id] = (cardCounts[id] ?? 0) + 1;

  return (
    <div
      className={`rounded-xl p-[4px] transition-all duration-300 ${!isActive ? 'opacity-60' : ''}`}
      style={{ background: isActive ? gradient : '#1e293b' }}
    >
      <div className="bg-machi-surface rounded-[8px] px-4 py-3 flex items-center gap-3 min-h-[72px]">
        {/* Name + coins */}
        <div className="flex flex-col min-w-[80px]">
          <span className="font-semibold text-sm text-machi-text">{name}</span>
          <span className="text-machi-yellow font-mono text-sm">
            {coins}c
            {coinDelta != null && coinDelta !== 0 && (
              <span className={`ml-1 text-[10px] font-semibold ${coinDelta > 0 ? 'text-machi-green' : 'text-machi-red'}`}>
                {coinDelta > 0 ? `+${coinDelta}` : coinDelta}
              </span>
            )}
          </span>
        </div>

        {/* Landmarks */}
        <div className="flex gap-1 shrink-0">
          {LANDMARK_IDS.map(lid => {
            const owned = ownedIds.includes(lid);
            const proj = projects.byId(lid);
            const lname = proj?.[`name_${language}` as 'name_de' | 'name_en'] ?? lid;
            return (
              <CardTooltip key={lid} project={proj} language={language}>
                <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium transition-colors ${
                  owned ? 'bg-machi-yellow/20 text-machi-yellow' : 'bg-machi-border/30 text-machi-text-dim/30'
                }`}>
                  {lname.charAt(0).toUpperCase()}
                </span>
              </CardTooltip>
            );
          })}
        </div>

        {/* Card hand */}
        <div className="flex-1 flex flex-wrap gap-1 overflow-hidden max-h-12">
          {Object.entries(cardCounts).map(([id, count]) => {
            const proj = projects.byId(id);
            if (!proj) return null;
            return (
              <CardTooltip key={id} project={proj} language={language}>
                <span className={`text-[10px] px-1.5 py-0.5 rounded cursor-default inline-flex items-center ${cardTextClass(proj.color)}`}>
                  {categoryIconPath(proj.category) && (
                    <img src={categoryIconPath(proj.category)} alt="" className="w-2.5 h-2.5 mr-0.5" />
                  )}
                  {proj[`name_${language}` as 'name_de' | 'name_en'] ?? proj.name_de}
                  {count > 1 ? ` ×${count}` : ''}
                </span>
              </CardTooltip>
            );
          })}
        </div>

        {/* Right side: roll breakdown / last-turn summary / thinking / dice / controls */}
        <div className="flex items-center gap-3 ml-auto shrink-0">
          {/* Last-turn summary (always shown for AI row, shown during opponent's turn otherwise) */}
          {(alwaysShowSummary || !isActive) && lastTurnText && (
            <span className="text-xs text-machi-text-dim bg-machi-bg/50 px-2 py-1 rounded max-w-[180px]">
              {lastTurnText}
            </span>
          )}

          {/* Roll coin breakdown (shown left of dice after roll) */}
          {allPlayers && rollCoinDeltas && (
            <RollCoinBreakdown players={allPlayers} coinDeltas={rollCoinDeltas} />
          )}

          {/* AI thinking indicator */}
          {isAiThinking && isActive && (
            <span className="flex items-center gap-1.5 text-xs text-machi-accent animate-pulse">
              <span className="w-2 h-2 rounded-full bg-machi-accent" />
              Thinking…
            </span>
          )}

          {/* Dice faces (AI result — animated when rolling, static when settled) */}
          {!children && (diceAnimating || face1 != null) && (
            <div className="flex items-center gap-1">
              {face1 != null
                ? <DieFace value={face1} selected={!!diceHighlighted} onClick={() => {}} />
                : <div className="w-12 h-12 rounded-lg border-2 border-machi-border bg-machi-surface animate-pulse" />
              }
              {/* Only show 2nd die if relevant (animated with value, or settled result) */}
              {(diceAnimFace2 != null || face2 != null) && (
                face2 != null
                  ? <DieFace value={face2} selected={!!diceHighlighted} onClick={() => {}} />
                  : <div className="w-12 h-12 rounded-lg border-2 border-machi-border bg-machi-surface animate-pulse" />
              )}
            </div>
          )}

          {/* Human controls (Roll button, lock-in, etc.) */}
          {children}
        </div>
      </div>
    </div>
  );
}

// ─── Card Market Grid ────────────────────────────────────────────────────────

interface CardMarketGridProps {
  projects: Props['projects'];
  session: UseSessionReturn;
  playerCount: number;
  humanCoins: number;   // coins AFTER income (for affordability check during buy phase)
  humanOwnedIds: string[];
  aiOwnedIds: string[];
  isBuyPhase: boolean;
  highlightedCardId?: string | null;
  onBuy: (id: string | null) => void;
  language: 'de' | 'en';
}

function CardMarketGrid({
  projects, session, playerCount, humanCoins, humanOwnedIds, aiOwnedIds,
  isBuyPhase, highlightedCardId, onBuy, language,
}: CardMarketGridProps) {
  const regularCards = projects.projects.filter(p => !p.is_grossprojekt);
  const landmarks    = projects.projects.filter(p => p.is_grossprojekt);

  return (
    <div className="flex-1 overflow-y-auto px-4 py-2">
      {/* Regular card grid */}
      <div className="grid grid-cols-4 xl:grid-cols-5 gap-2">
        {regularCards.map(p => {
          const isPurple = p.color === 'lila';
          const remaining = isPurple ? null : supplyCount(p.id, session, playerCount);
          const affordable = humanCoins >= p.cost;
          const empty = !isPurple && remaining === 0;
          // Purple: can only buy if neither player owns it already (uniqueness)
          const humanOwnsPurple = isPurple && humanOwnedIds.includes(p.id);
          const aiOwnsPurple = isPurple && aiOwnedIds.includes(p.id);
          const isHighlighted = p.id === highlightedCardId;
          const clickable = isBuyPhase && affordable && !empty && !humanOwnsPurple;
          const activation = p.dice_activation?.length > 0 ? p.dice_activation.join(', ') : '—';

          return (
            <CardTooltip key={p.id} project={p} language={language}>
              <button
                className={`rounded-lg border p-2 text-left flex flex-col gap-0.5 transition-all w-full ${
                  empty
                    ? 'opacity-20 border-machi-border bg-machi-bg cursor-default'
                    : humanOwnsPurple
                    ? 'border-machi-purple/20 bg-machi-surface opacity-50 cursor-default'
                    : isHighlighted
                    ? 'border-machi-accent bg-machi-accent/10 ring-1 ring-machi-accent/50 scale-[1.04]'
                    : clickable
                    ? 'border-machi-border bg-machi-surface hover:border-machi-accent hover:bg-machi-accent/5 cursor-pointer'
                    : 'border-machi-border bg-machi-surface opacity-50 cursor-default'
                }`}
                onClick={clickable ? () => onBuy(p.id) : undefined}
                disabled={!clickable}
              >
                <span className={`text-xs font-medium leading-tight inline-flex items-center gap-0.5 ${cardTextClass(p.color)}`}>
                  {categoryIconPath(p.category) && (
                    <img src={categoryIconPath(p.category)} alt="" className="w-3 h-3 shrink-0" />
                  )}
                  {p[`name_${language}` as 'name_de' | 'name_en'] ?? p.name_de}
                </span>
                <div className="flex items-center justify-between text-[10px] text-machi-text-dim mt-0.5">
                  <span>{activation}</span>
                  <span>{p.cost}c</span>
                </div>
                {isPurple ? (
                  <div className="flex gap-1 mt-0.5">
                    <span className={`text-[9px] px-1 py-0.5 rounded ${humanOwnsPurple ? 'bg-machi-purple/20 text-machi-purple' : 'bg-machi-border/20 text-machi-text-dim/40'}`}>You</span>
                    <span className={`text-[9px] px-1 py-0.5 rounded ${aiOwnsPurple ? 'bg-machi-purple/20 text-machi-purple' : 'bg-machi-border/20 text-machi-text-dim/40'}`}>AI</span>
                  </div>
                ) : (
                  <span className={`text-[10px] font-mono ${
                    empty ? 'text-machi-text-dim' : remaining! <= 2 ? 'text-machi-yellow' : 'text-machi-text-dim'
                  }`}>
                    {empty ? 'sold out' : `${remaining}/6`}
                  </span>
                )}
              </button>
            </CardTooltip>
          );
        })}
      </div>

      {/* Landmark section */}
      <div className="mt-3">
        <h3 className="text-xs font-semibold text-machi-text-dim mb-1.5">
          {language === 'de' ? 'Großprojekte' : 'Landmarks'}
        </h3>
        <div className="grid grid-cols-4 xl:grid-cols-5 gap-2">
          {landmarks.map(p => {
            const humanOwns = humanOwnedIds.includes(p.id);
            const aiOwns    = aiOwnedIds.includes(p.id);
            const affordable = humanCoins >= p.cost;
            const clickable  = isBuyPhase && affordable && !humanOwns;
            const isHighlighted = p.id === highlightedCardId;

            return (
              <CardTooltip key={p.id} project={p} language={language}>
                <button
                  className={`rounded-lg border p-2 text-left flex flex-col gap-0.5 transition-all w-full ${
                    humanOwns
                      ? 'border-machi-yellow bg-machi-yellow/10 cursor-default'
                      : isHighlighted
                      ? 'border-machi-accent bg-machi-accent/10 ring-1 ring-machi-accent/50 scale-[1.04]'
                      : clickable
                      ? 'border-machi-border bg-machi-surface hover:border-machi-yellow hover:bg-machi-yellow/5 cursor-pointer'
                      : 'border-machi-border bg-machi-surface opacity-50 cursor-default'
                  }`}
                  onClick={clickable ? () => onBuy(p.id) : undefined}
                  disabled={!clickable}
                >
                  <span className="text-xs font-medium leading-tight text-machi-yellow">
                    {p[`name_${language}` as 'name_de' | 'name_en'] ?? p.name_de}
                  </span>
                  <span className="text-[10px] text-machi-text-dim">{p.cost}c</span>
                  <div className="flex gap-1 mt-0.5">
                    <span className={`text-[9px] px-1 py-0.5 rounded ${humanOwns ? 'bg-machi-yellow/20 text-machi-yellow' : 'bg-machi-border/20 text-machi-text-dim/40'}`}>
                      You
                    </span>
                    <span className={`text-[9px] px-1 py-0.5 rounded ${aiOwns ? 'bg-machi-purple/20 text-machi-purple' : 'bg-machi-border/20 text-machi-text-dim/40'}`}>
                      AI
                    </span>
                  </div>
                </button>
              </CardTooltip>
            );
          })}
        </div>
      </div>

      {/* Pass button */}
      {isBuyPhase && (
        <div className="mt-3 flex justify-end">
          <button
            className="px-4 py-1.5 rounded-lg text-sm text-machi-text-dim border border-machi-border hover:text-machi-text hover:border-machi-text-dim transition-colors"
            onClick={() => onBuy(null)}
          >
            Pass / Save coins
          </button>
        </div>
      )}
    </div>
  );
}

// ─── Win Screen ─────────────────────────────────────────────────────────────

interface WinScreenProps {
  players: { name: string; coins: number }[];
  winnerIndex: number;
  humanPlayerIndex: number;
  aiPlayerIndex: number;
  engineId: string;
  onNewGame: () => void;
}

function WinScreen({ players, winnerIndex, humanPlayerIndex, aiPlayerIndex, engineId, onNewGame }: WinScreenProps) {
  const [saveState, setSaveState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [savedId, setSavedId] = useState<string | null>(null);

  const handleSave = useCallback(async () => {
    setSaveState('saving');
    try {
      const res = await api.pvaiSave({
        humanName: players[humanPlayerIndex].name,
        aiPlayerIndex,
        engineId,
      });
      setSavedId(res.id);
      setSaveState('saved');
    } catch {
      setSaveState('error');
    }
  }, [players, humanPlayerIndex, aiPlayerIndex, engineId]);

  const winner = players[winnerIndex];
  const humanWon = winnerIndex === humanPlayerIndex;

  return (
    <div className="min-h-screen bg-machi-bg flex items-center justify-center">
      <div className="bg-machi-surface rounded-xl border border-machi-border p-8 space-y-4 max-w-lg w-full text-center">
        <h2 className={`text-2xl font-bold ${humanWon ? 'text-machi-yellow' : 'text-machi-text-dim'}`}>
          {humanWon ? '🎉 You win!' : `${winner?.name ?? '?'} wins!`}
        </h2>
        <div className="space-y-2">
          {players.map((p, i) => (
            <div key={i} className={`flex justify-between px-3 py-1 rounded ${i === winnerIndex ? 'bg-machi-yellow/10' : ''}`}>
              <span className="text-machi-text">{p.name}</span>
              <span className="text-machi-text-dim">{p.coins}c</span>
            </div>
          ))}
        </div>

        {/* Save game button */}
        {saveState === 'idle' && (
          <button
            className="w-full py-2 rounded-lg font-semibold border border-machi-border text-machi-text hover:border-machi-accent hover:text-machi-accent transition-all"
            onClick={handleSave}
          >
            Save Game
          </button>
        )}
        {saveState === 'saving' && (
          <div className="w-full py-2 text-center text-machi-text-dim text-sm animate-pulse">
            Analysing game…
          </div>
        )}
        {saveState === 'saved' && (
          <div className="w-full py-2 text-center text-machi-accent text-sm">
            Game saved {savedId ? `(#${savedId})` : ''}
          </div>
        )}
        {saveState === 'error' && (
          <div className="w-full py-2 text-center text-red-400 text-sm">
            Save failed — check server log
          </div>
        )}

        <button
          className="w-full py-2 rounded-lg font-semibold bg-machi-accent text-machi-bg hover:brightness-110 transition-all"
          onClick={onNewGame}
        >
          New Game
        </button>
      </div>
    </div>
  );
}

// ─── Main PvAI Board Screen ──────────────────────────────────────────────────

export function PvAiBoardScreen({ session, settings, projects, pvai }: Props) {
  const s = session.session!;
  const engine = useEngine();
  const humanPlayerIndex = 1 - pvai.aiPlayerIndex;
  const humanPlayer = s.state.players[humanPlayerIndex];
  const aiPlayer    = s.state.players[pvai.aiPlayerIndex];
  const isHumanTurn = s.nextPlayerIndex === humanPlayerIndex;
  const isAiTurn    = s.nextPlayerIndex === pvai.aiPlayerIndex;
  const canUse2d6   = humanPlayer.ownedIds.includes('bahnhof');
  const ownsFunkturm = humanPlayer.ownedIds.includes('funkturm');

  // ── Dice state ──────────────────────────────────────────────────────────
  const [diceCount, setDiceCount] = useState<1 | 2>(1);
  const [rolledDice, setRolledDice] = useState<{ die1: number; die2: number | null } | null>(null);
  // lockedIn: false = not yet committed, true = committed (buy phase active)
  // When Funkturm is NOT owned: roll auto-locks in (no reroll).
  // When Funkturm IS owned: roll shows re-roll option; second roll always locks in.
  const [lockedIn, setLockedIn] = useState(false);
  const [hasUsedFunkturmReroll, setHasUsedFunkturmReroll] = useState(false);
  const [pendingBürohausSwap, setPendingBürohausSwap] = useState<BürohausRequest | null>(null);

  // ── Roll animation ───────────────────────────────────────────────────────
  const rollAnim = useRollAnimation(diceCount);

  // ── AI dice display (populated when AI turn result arrives) ─────────────
  const [aiDiceResult, setAiDiceResult] = useState<{ die1: number; die2: number | null } | null>(null);
  const [aiDiceAnimating, setAiDiceAnimating] = useState(false);
  const [aiDiceAnimFace1, setAiDiceAnimFace1] = useState<number | null>(null);
  const [aiDiceAnimFace2, setAiDiceAnimFace2] = useState<number | null>(null);
  const aiAnimTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const aiAnimTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [highlightedCardId, setHighlightedCardId] = useState<string | null>(null);
  // Coin deltas from last AI turn (derived from pvai.lastAiTurn.coinDeltas)
  const aiRollCoinDeltas = pvai.lastAiTurn?.coinDeltas ?? null;

  // ── Last-turn summaries ──────────────────────────────────────────────────
  const [humanLastTurn, setHumanLastTurn] = useState<string | null>(null);
  const [aiLastTurn, setAiLastTurn] = useState<string | null>(null);

  // ── Coin deltas (preview after lock-in) ──────────────────────────────────
  const rollTotal = rolledDice
    ? (diceCount === 2 && rolledDice.die2 != null ? rolledDice.die1 + rolledDice.die2 : rolledDice.die1)
    : 0;
  const preview = useRollPreview(engine.result, rollTotal, diceCount);
  const humanCoinDelta = lockedIn ? (preview.coinDeltas?.[humanPlayerIndex] ?? null) : null;

  // ── Reset state on turn change ───────────────────────────────────────────
  useEffect(() => {
    setRolledDice(null);
    setLockedIn(false);
    setHasUsedFunkturmReroll(false);
    setDiceCount(1);
    setPendingBürohausSwap(null);
    // Clear AI dice display when human's new turn starts (keep summary text)
    if (isHumanTurn) {
      setAiDiceResult(null);
      setAiDiceAnimating(false);
      if (aiAnimTimerRef.current) clearInterval(aiAnimTimerRef.current);
      if (aiAnimTimeoutRef.current) clearTimeout(aiAnimTimeoutRef.current);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s.nextPlayerIndex, s.effectiveTurnCount]);

  // ── Engine eval for human turn ────────────────────────────────────────────
  useEffect(() => {
    if (isHumanTurn && !s.finished) {
      engine.evaluate(s.state, humanPlayerIndex, settings.engineId, s.state);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s.nextPlayerIndex, s.effectiveTurnCount]);

  // ── Auto-request AI turn ──────────────────────────────────────────────────
  const aiTurnRequested = useRef(false);
  useEffect(() => {
    if (isAiTurn && !s.finished && !aiTurnRequested.current) {
      aiTurnRequested.current = true;
      pvai.requestAiTurn().then(() => {
        // Brief pause after animation completes so the user can see the result
        // before the turn flips back to the human.
        setTimeout(() => session.refresh(), 1200);
      });
    }
    if (!isAiTurn) {
      aiTurnRequested.current = false;
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAiTurn, s.effectiveTurnCount]);

  // ── Update AI display when a new AI turn result arrives ──────────────────
  const prevAiTurnRef = useRef<typeof pvai.lastAiTurn>(null);
  useEffect(() => {
    const result = pvai.lastAiTurn;
    if (!result || result === prevAiTurnRef.current) return;
    prevAiTurnRef.current = result;

    // Derive dice faces from the final roll (post-Funkturm)
    const finalRoll = result.funkturmKeep === false ? (result.rerollTotal ?? result.rollTotal) : result.rollTotal;
    const count = result.diceCount as 1 | 2;
    let die1 = finalRoll;
    let die2: number | null = null;
    if (count === 2) {
      die1 = Math.max(1, Math.min(6, Math.round(finalRoll / 2)));
      die2 = finalRoll - die1;
      if (die2 < 1) { die2 = 1; die1 = finalRoll - 1; }
      if (die2 > 6) { die2 = 6; die1 = finalRoll - 6; }
    }
    const settled = { die1, die2 };

    // Animate dice cycling then settle
    if (aiAnimTimerRef.current) clearInterval(aiAnimTimerRef.current);
    if (aiAnimTimeoutRef.current) clearTimeout(aiAnimTimeoutRef.current);
    setAiDiceAnimating(true);
    setAiDiceAnimFace1(Math.floor(Math.random() * 6) + 1);
    setAiDiceAnimFace2(count === 2 ? Math.floor(Math.random() * 6) + 1 : null);

    aiAnimTimerRef.current = setInterval(() => {
      setAiDiceAnimFace1(Math.floor(Math.random() * 6) + 1);
      if (count === 2) setAiDiceAnimFace2(Math.floor(Math.random() * 6) + 1);
    }, 60);

    aiAnimTimeoutRef.current = setTimeout(() => {
      if (aiAnimTimerRef.current) clearInterval(aiAnimTimerRef.current);
      setAiDiceAnimating(false);
      setAiDiceResult(settled);

      // Build summary
      const incomeDelta = result.coinDeltas?.[pvai.aiPlayerIndex];
      const bought = result.purchasedCardId
        ? cardName(result.purchasedCardId, projects, settings.language)
        : 'saved coins';
      setAiLastTurn(`Rolled ${finalRoll}${incomeDelta ? ` → ${incomeDelta > 0 ? '+' : ''}${incomeDelta}c` : ''} → ${bought}`);

      // Highlight purchased card briefly (or just show settled dice for a save)
      if (result.purchasedCardId) {
        setHighlightedCardId(result.purchasedCardId);
        setTimeout(() => setHighlightedCardId(null), 2500);
      }
    }, 450);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pvai.lastAiTurn]);

  // Cleanup AI animation timers on unmount
  useEffect(() => () => {
    if (aiAnimTimerRef.current) clearInterval(aiAnimTimerRef.current);
    if (aiAnimTimeoutRef.current) clearTimeout(aiAnimTimeoutRef.current);
  }, []);

  // ── Roll handler ──────────────────────────────────────────────────────────
  const handleRoll = useCallback(async () => {
    const result = await rollAnim.roll();
    setRolledDice(result);
    // Auto-lock-in if player doesn't own Funkturm (no reroll option)
    if (!ownsFunkturm) {
      setLockedIn(true);
    }
  }, [rollAnim, ownsFunkturm]);

  // ── Re-roll handler (Funkturm only, once per turn) ────────────────────────
  const handleReroll = useCallback(async () => {
    setHasUsedFunkturmReroll(true);
    setPendingBürohausSwap(null);
    const result = await rollAnim.roll();
    setRolledDice(result);
    // Second roll always locks in (Funkturm rule: one reroll max)
    setLockedIn(true);
  }, [rollAnim]);

  // ── Lock-in handler (Funkturm owners keeping their first roll) ────────────
  const handleLockIn = useCallback(() => {
    setLockedIn(true);
  }, []);

  // ── Buy handler ───────────────────────────────────────────────────────────
  const handleBuy = useCallback(async (projectId: string | null) => {
    if (!rolledDice || !lockedIn) return;
    const isDoubles = diceCount === 2 && rolledDice.die1 === rolledDice.die2;
    const incomeDelta = preview.coinDeltas?.[humanPlayerIndex];
    const boughtName = projectId ? cardName(projectId, projects, settings.language) : 'saved coins';
    setHumanLastTurn(
      `Rolled ${rollTotal}${incomeDelta ? ` → ${incomeDelta > 0 ? '+' : ''}${incomeDelta}c` : ''} → ${boughtName}`
    );

    await pvai.onHumanBuy({
      roll: rollTotal,
      boughtId: projectId,
      isDoubles,
      diceCount,
      bürohausOwnCardId: pendingBürohausSwap?.ownCardId ?? null,
      bürohausOppCardId: pendingBürohausSwap?.oppCardId ?? null,
      bürohausOppPlayer: pendingBürohausSwap?.oppPlayerIndex ?? null,
    });

    await session.refresh();
  }, [rolledDice, lockedIn, diceCount, rollTotal, preview, pvai, session, projects, settings.language, pendingBürohausSwap, humanPlayerIndex]);

  // ── Bürohaus ──────────────────────────────────────────────────────────────
  const humanOwnsBürohaus = humanPlayer.ownedIds.includes('bürohaus');
  const showBürohaus = lockedIn && rollTotal === 6 && humanOwnsBürohaus;

  // ── Human roll controls ───────────────────────────────────────────────────
  const humanControls = isHumanTurn ? (
    <div className="flex items-center gap-2 flex-wrap justify-end">
      {/* 1d6 / 2d6 toggle — shown before rolling */}
      {canUse2d6 && !rolledDice && (
        <div className="flex rounded-lg overflow-hidden border border-machi-border text-xs">
          <button
            className={`px-2 py-1 ${diceCount === 1 ? 'bg-machi-accent text-machi-bg' : 'text-machi-text-dim hover:text-machi-text'}`}
            onClick={() => setDiceCount(1)}
          >
            1d6
          </button>
          <button
            className={`px-2 py-1 ${diceCount === 2 ? 'bg-machi-accent text-machi-bg' : 'text-machi-text-dim hover:text-machi-text'}`}
            onClick={() => setDiceCount(2)}
          >
            2d6
          </button>
        </div>
      )}

      {/* Roll button (no result yet) */}
      {!rolledDice && (
        <button
          className="w-12 h-12 rounded-lg border-2 border-machi-border bg-machi-surface hover:border-machi-accent hover:scale-105 transition-all font-bold text-machi-text-dim hover:text-machi-accent flex items-center justify-center text-xl"
          onClick={handleRoll}
          disabled={rollAnim.animating}
        >
          {rollAnim.animating ? '…' : '?'}
        </button>
      )}

      {/* Animation frames */}
      {rollAnim.animating && (
        <div className="flex items-center gap-1">
          {rollAnim.showFace1 != null && <DieFace value={rollAnim.showFace1} selected={false} onClick={() => {}} />}
          {diceCount === 2 && rollAnim.showFace2 != null && <DieFace value={rollAnim.showFace2} selected={false} onClick={() => {}} />}
        </div>
      )}

      {/* Settled roll — before lock-in (only when Funkturm owned and not yet rerolled) */}
      {rolledDice && !rollAnim.animating && !lockedIn && (
        <>
          <div className="flex items-center gap-1">
            <DieFace value={rolledDice.die1} selected={true} onClick={() => {}} />
            {rolledDice.die2 != null && <DieFace value={rolledDice.die2} selected={true} onClick={() => {}} />}
          </div>
          {diceCount === 2 && rolledDice.die1 === rolledDice.die2 && (
            <span className="text-xs bg-machi-yellow/20 text-machi-yellow px-1.5 py-0.5 rounded font-bold">Doubles</span>
          )}
          {/* Reroll — Funkturm only, once per turn */}
          {ownsFunkturm && !hasUsedFunkturmReroll && (
            <button
              className="text-xs text-machi-text-dim hover:text-machi-text transition-colors px-1 border border-machi-border rounded"
              onClick={handleReroll}
              title="Reroll (Funkturm)"
            >
              ↺ Reroll
            </button>
          )}
          <button
            className="px-3 py-1.5 rounded-lg text-xs font-semibold bg-machi-accent text-machi-bg hover:brightness-110 transition-all"
            onClick={handleLockIn}
          >
            Lock In
          </button>
        </>
      )}

      {/* Locked-in dice */}
      {rolledDice && lockedIn && !rollAnim.animating && (
        <div className="flex items-center gap-1">
          <DieFace value={rolledDice.die1} selected={true} onClick={() => {}} />
          {rolledDice.die2 != null && <DieFace value={rolledDice.die2} selected={true} onClick={() => {}} />}
          {diceCount === 2 && rolledDice.die1 === rolledDice.die2 && (
            <span className="text-xs bg-machi-yellow/20 text-machi-yellow px-1.5 py-0.5 rounded font-bold">Doubles</span>
          )}
        </div>
      )}
    </div>
  ) : null;

  // ── Game over ─────────────────────────────────────────────────────────────
  if (s.finished) {
    return (
      <WinScreen
        players={s.state.players}
        winnerIndex={s.winnerIndex}
        humanPlayerIndex={humanPlayerIndex}
        aiPlayerIndex={pvai.aiPlayerIndex}
        engineId={settings.engineId}
        onNewGame={() => { pvai.stopPvAi(); session.clearSession(); }}
      />
    );
  }

  return (
    <div className="min-h-screen bg-machi-bg text-machi-text flex flex-col">
      {/* Top bar */}
      <header className="bg-machi-surface border-b border-machi-border px-4 py-2 flex items-center justify-between shrink-0">
        <h1 className="text-base font-bold text-machi-text">
          Machi Koro <span className="text-machi-accent text-sm font-normal">vs AI</span>
        </h1>
        <div className="flex items-center gap-3">
          <button
            className="text-sm text-machi-text-dim hover:text-machi-accent transition-colors disabled:opacity-30"
            onClick={() => session.undo()}
            disabled={session.loading || s.history.length === 0}
          >
            Undo
          </button>
          <button
            className="text-sm text-machi-text-dim hover:text-machi-text transition-colors"
            onClick={() => { pvai.stopPvAi(); session.clearSession(); }}
          >
            Quit
          </button>
        </div>
      </header>

      {/* 3-row layout */}
      <div className="flex-1 flex flex-col overflow-hidden">

        {/* AI row (top) */}
        <div className="px-4 pt-3 pb-1 shrink-0">
          <PlayerRow
            name={aiPlayer.name}
            coins={aiPlayer.coins}
            coinDelta={
              // Only show delta while AI's turn is active (dice result just shown)
              // Once session refreshes, turn switches to human and delta should clear
              isAiTurn ? (
                aiRollCoinDeltas ? (aiRollCoinDeltas[pvai.aiPlayerIndex] ?? null) : null
              ) : (
                // During human's locked-in turn: show AI's income from human's roll (red cards)
                lockedIn && preview.coinDeltas ? (preview.coinDeltas[pvai.aiPlayerIndex] ?? null) : null
              )
            }
            ownedIds={aiPlayer.ownedIds}
            isActive={isAiTurn}
            gradient={AI_GRADIENT}
            projects={projects}
            language={settings.language}
            diceResult={aiDiceResult}
            diceAnimating={aiDiceAnimating}
            diceAnimFace1={aiDiceAnimFace1}
            diceAnimFace2={aiDiceAnimFace2}
            diceHighlighted={aiDiceResult != null}
            allPlayers={isAiTurn ? s.state.players : undefined}
            rollCoinDeltas={isAiTurn ? aiRollCoinDeltas : null}
            lastTurnText={aiLastTurn}
            alwaysShowSummary={true}
            isAiThinking={pvai.aiThinking}
          />
        </div>

        {/* Bürohaus panel */}
        {showBürohaus && (
          <div className="px-4 py-1 shrink-0">
            <BürohausPanel
              activePlayer={humanPlayer}
              opponents={s.state.players
                .map((p, i) => ({ index: i, player: p }))
                .filter(o => o.index !== humanPlayerIndex)}
              projects={projects}
              language={settings.language}
              pendingSwap={pendingBürohausSwap}
              onSwapChange={setPendingBürohausSwap}
            />
          </div>
        )}

        {/* Card market (center, grows to fill space) */}
        <CardMarketGrid
          projects={projects}
          session={session}
          playerCount={s.state.players.length}
          humanCoins={humanPlayer.coins + (humanCoinDelta ?? 0)}
          humanOwnedIds={humanPlayer.ownedIds}
          aiOwnedIds={aiPlayer.ownedIds}
          isBuyPhase={isHumanTurn && lockedIn}
          highlightedCardId={highlightedCardId}
          onBuy={handleBuy}
          language={settings.language}
        />

        {/* Human row (bottom) */}
        <div className="px-4 pb-3 pt-1 shrink-0">
          <PlayerRow
            name={humanPlayer.name}
            coins={humanPlayer.coins}
            coinDelta={
              isAiTurn
                ? (aiRollCoinDeltas ? (aiRollCoinDeltas[humanPlayerIndex] ?? null) : null)
                : (lockedIn ? (preview.coinDeltas?.[humanPlayerIndex] ?? null) : null)
            }
            ownedIds={humanPlayer.ownedIds}
            isActive={isHumanTurn}
            gradient={HUMAN_GRADIENT}
            projects={projects}
            language={settings.language}
            allPlayers={lockedIn ? s.state.players : undefined}
            rollCoinDeltas={lockedIn ? preview.coinDeltas ?? null : null}
            lastTurnText={humanLastTurn}
          >
            {humanControls}
          </PlayerRow>
        </div>
      </div>

      {/* Error toast */}
      {(session.error || pvai.error) && (
        <div className="fixed bottom-4 left-1/2 -translate-x-1/2 bg-red-900/90 border border-red-500 rounded-lg px-4 py-2 text-red-300 text-sm z-50">
          {session.error || pvai.error}
        </div>
      )}
    </div>
  );
}

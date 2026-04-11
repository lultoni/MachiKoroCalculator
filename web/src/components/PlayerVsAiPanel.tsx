/**
 * Player-vs-AI panel — setup form + AI turn animated reveal.
 */

import { useState } from 'react';
import type { UsePvAiReturn, AiTurnStep } from '../hooks/usePlayerVsAi';
import type { ProjectDef } from '../api/types';

interface Props {
  pvai: UsePvAiReturn;
  projects: ProjectDef[];
  language: 'de' | 'en';
  playerNames: string[];
  /** Called after setup confirmed — triggers game start + pvai.startPvAi */
  onSetup: (engineId: string, aiPlayerIndex: number, minThinkTimeMs: number) => void;
  /** Called when user wants to exit PvAI mode */
  onStop: () => void;
}

const ENGINE_OPTIONS = [
  { id: 'mcts-v1',     label: 'MCTS v1 (Recommended)' },
  { id: 'flat-mc',     label: 'Flat MC' },
  { id: 'creator',     label: 'Creator' },
  { id: 'expectimax',  label: 'Expectimax' },
  { id: 'heuristic-ev',label: 'Heuristic (fast)' },
];

// ─── Setup Form ──────────────────────────────────────────────────────────

export function PlayerVsAiSetup({ pvai, playerNames, onSetup }: {
  pvai: UsePvAiReturn;
  playerNames: string[];
  onSetup: (engineId: string, aiPlayerIndex: number, minThinkTimeMs: number) => void;
}) {
  const [engineId, setEngineId]         = useState('mcts-v1');
  const [aiSeat, setAiSeat]             = useState(1);
  const [minThinkMs, setMinThinkMs]     = useState(1500);
  const [loading, setLoading]           = useState(false);

  const handleStart = async () => {
    setLoading(true);
    try {
      await pvai.startPvAi(engineId, aiSeat, minThinkMs);
      onSetup(engineId, aiSeat, minThinkMs);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="bg-machi-surface rounded-xl border border-machi-border p-4 space-y-4">
      <h3 className="font-semibold text-machi-text text-sm">Player vs AI</h3>

      {pvai.error && (
        <p className="text-red-400 text-xs">{pvai.error}</p>
      )}

      {/* Human seat selection */}
      <div>
        <label className="text-xs text-machi-text-dim block mb-1">AI plays as</label>
        <div className="flex gap-2">
          {playerNames.map((name, i) => (
            <button
              key={i}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                aiSeat === i
                  ? 'bg-machi-accent text-machi-bg'
                  : 'bg-machi-bg border border-machi-border text-machi-text-dim hover:text-machi-text'
              }`}
              onClick={() => setAiSeat(i)}
            >
              {name || `Player ${i + 1}`}
            </button>
          ))}
        </div>
      </div>

      {/* Engine selection */}
      <div>
        <label className="text-xs text-machi-text-dim block mb-1">Engine</label>
        <select
          className="w-full bg-machi-bg border border-machi-border rounded-lg px-3 py-1.5 text-sm text-machi-text focus:outline-none focus:border-machi-accent"
          value={engineId}
          onChange={e => setEngineId(e.target.value)}
        >
          {ENGINE_OPTIONS.map(o => (
            <option key={o.id} value={o.id}>{o.label}</option>
          ))}
        </select>
      </div>

      {/* Min think time */}
      <div>
        <label className="text-xs text-machi-text-dim block mb-1">
          Min think time: {minThinkMs / 1000}s
        </label>
        <input
          type="range"
          min={500} max={10000} step={500}
          value={minThinkMs}
          onChange={e => setMinThinkMs(Number(e.target.value))}
          className="w-full accent-machi-accent"
        />
      </div>

      <button
        className="w-full py-2 rounded-lg text-sm font-semibold bg-machi-accent text-machi-bg hover:brightness-110 disabled:opacity-50 disabled:cursor-not-allowed transition-all"
        onClick={handleStart}
        disabled={loading}
      >
        {loading ? 'Starting...' : 'Start vs AI'}
      </button>
    </div>
  );
}

// ─── AI Thinking Indicator ────────────────────────────────────────────────

export function AiThinkingIndicator({ iterationsHint }: { iterationsHint?: number }) {
  return (
    <div className="flex items-center gap-2 px-3 py-2 bg-machi-surface rounded-lg border border-machi-border">
      <span className="relative flex h-2 w-2">
        <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-machi-accent opacity-75" />
        <span className="relative inline-flex rounded-full h-2 w-2 bg-machi-accent" />
      </span>
      <span className="text-xs text-machi-text-dim">AI is thinking…</span>
      {iterationsHint != null && iterationsHint > 0 && (
        <span className="text-xs text-machi-text-dim/60 ml-auto">{iterationsHint.toLocaleString()} iter</span>
      )}
    </div>
  );
}

// ─── AI Turn Reveal ───────────────────────────────────────────────────────

function cardName(projectId: string, projects: ProjectDef[], language: 'de' | 'en'): string {
  const p = projects.find(x => x.id === projectId);
  if (!p) return projectId;
  return language === 'en' ? (p.name_en ?? p.name_de) : p.name_de;
}

function DiceFace({ value }: { value: number }) {
  return (
    <span className="inline-flex items-center justify-center w-7 h-7 bg-white rounded border border-gray-300 text-gray-800 font-bold text-sm shadow-sm">
      {value}
    </span>
  );
}

function StepRow({ step, projects, language, active }: {
  step: AiTurnStep;
  projects: ProjectDef[];
  language: 'de' | 'en';
  active: boolean;
}) {
  const base = `flex items-start gap-2 py-1.5 transition-all ${active ? 'opacity-100' : 'opacity-40'}`;

  if (step.type === 'dice') {
    return (
      <div className={base}>
        <span className="text-machi-text-dim text-xs w-20 shrink-0">Dice</span>
        <div className="flex items-center gap-1">
          {step.diceCount === 2 ? (
            <>
              <DiceFace value={Math.ceil((step.rollTotal ?? 2) / 2)} />
              <DiceFace value={Math.floor((step.rollTotal ?? 2) / 2)} />
            </>
          ) : (
            <DiceFace value={step.rollTotal ?? 1} />
          )}
          <span className="text-xs text-machi-text ml-1">= {step.rollTotal}</span>
          {step.isDoubles && <span className="text-xs text-yellow-400 ml-1">doubles</span>}
        </div>
      </div>
    );
  }

  if (step.type === 'funkturm') {
    return (
      <div className={base}>
        <span className="text-machi-text-dim text-xs w-20 shrink-0">Funkturm</span>
        <span className="text-xs text-machi-text">
          {step.funkturmKeep ? 'Keep roll' : `Reroll → ${step.rerollTotal}`}
        </span>
      </div>
    );
  }

  if (step.type === 'income') {
    return (
      <div className={base}>
        <span className="text-machi-text-dim text-xs w-20 shrink-0">Income</span>
        <div className="flex flex-wrap gap-1">
          {(step.coinDeltas ?? []).map((d, i) => (
            <span key={i} className={`text-xs font-medium ${d >= 0 ? 'text-green-400' : 'text-red-400'}`}>
              P{i + 1} {d >= 0 ? '+' : ''}{d}
            </span>
          ))}
        </div>
      </div>
    );
  }

  if (step.type === 'bürohaus') {
    return (
      <div className={base}>
        <span className="text-machi-text-dim text-xs w-20 shrink-0">Bürohaus</span>
        <span className="text-xs text-machi-text">
          {cardName(step.swapOwn ?? '', projects, language)} ↔ {cardName(step.swapOpp ?? '', projects, language)}
        </span>
      </div>
    );
  }

  if (step.type === 'purchase') {
    return (
      <div className={base}>
        <span className="text-machi-text-dim text-xs w-20 shrink-0">Buy</span>
        <span className={`text-xs font-semibold ${step.cardId ? 'text-machi-accent' : 'text-machi-text-dim'}`}>
          {step.cardId ? cardName(step.cardId, projects, language) : 'Save'}
        </span>
      </div>
    );
  }

  return null;
}

export function AiTurnReveal({ pvai, projects, language }: {
  pvai: UsePvAiReturn;
  projects: ProjectDef[];
  language: 'de' | 'en';
}) {
  if (!pvai.animating && !pvai.lastAiTurn) return null;

  return (
    <div className="bg-machi-surface rounded-xl border border-machi-border p-4 space-y-1">
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs font-semibold text-machi-text">AI Turn</span>
        {pvai.lastAiTurn && (
          <span className="text-xs text-machi-text-dim/60">
            {pvai.lastAiTurn.iterationsUsed.toLocaleString()} iter · {pvai.lastAiTurn.thinkTimeMs}ms
          </span>
        )}
      </div>
      {pvai.steps.map((step, i) => (
        step.type !== 'done' && (
          <StepRow
            key={i}
            step={step}
            projects={projects}
            language={language}
            active={i <= pvai.currentStep}
          />
        )
      ))}
    </div>
  );
}

// ─── Combined Panel (used in GameScreen) ─────────────────────────────────

export function PlayerVsAiPanel({ pvai, projects, language, playerNames, onSetup, onStop }: Props) {
  if (!pvai.pvaiActive) {
    return (
      <PlayerVsAiSetup
        pvai={pvai}
        playerNames={playerNames}
        onSetup={onSetup}
      />
    );
  }

  return (
    <div className="space-y-3">
      {pvai.aiThinking && <AiThinkingIndicator />}
      <AiTurnReveal pvai={pvai} projects={projects} language={language} />
      <button
        className="w-full py-1.5 rounded-lg text-xs text-machi-text-dim hover:text-machi-text border border-machi-border hover:border-machi-accent transition-colors"
        onClick={onStop}
      >
        Exit PvAI mode
      </button>
    </div>
  );
}

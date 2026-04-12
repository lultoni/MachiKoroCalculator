/**
 * Player-vs-AI mode hook.
 *
 * Manages: PvAI activation, human turn lock-in delivery, AI turn animated reveal.
 */

import { useState, useCallback, useRef } from 'react';
import type { SessionJson, AiTurnResult } from '../api/types';
import * as api from '../api/client';

export interface AiTurnStep {
  type: 'dice' | 'income' | 'funkturm' | 'bürohaus' | 'purchase' | 'done';
  /** For 'dice': roll details */
  diceCount?: 1 | 2;
  rollTotal?: number;
  isDoubles?: boolean;
  /** For 'income': coin deltas per player */
  coinDeltas?: number[];
  /** For 'funkturm': keep/reroll decision */
  funkturmKeep?: boolean;
  rerollTotal?: number;
  rerollIsDoubles?: boolean;
  /** For 'bürohaus': card swap */
  swapOwn?: string;
  swapOpp?: string;
  swapOppPlayer?: number;
  /** For 'purchase': card id (null = save) */
  cardId?: string | null;
  /** Pause before showing this step (ms) */
  delayMs: number;
}

export interface UsePvAiReturn {
  /** True when PvAI mode is active. */
  pvaiActive: boolean;
  /** Seat index the AI occupies. */
  aiPlayerIndex: number;
  /** Engine ID of the AI opponent (set when PvAI starts, null before). */
  aiEngineId: string | null;
  /** True while the AI is thinking (between human lock-in and turn reveal). */
  aiThinking: boolean;
  /** True while the AI's turn is being animated. */
  animating: boolean;
  /** Current animation step index. */
  currentStep: number;
  /** All animation steps for the current AI turn. */
  steps: AiTurnStep[];
  /** The raw AI turn result (available after executeAiTurn resolves). */
  lastAiTurn: AiTurnResult | null;
  /** Error string, if any. */
  error: string | null;

  /** Activate PvAI mode for the current session. */
  startPvAi: (engineId: string, aiPlayerIndex: number, minThinkTimeMs: number) => Promise<void>;
  /** Deliver human turn lock-in to the engine. */
  onHumanBuy: (req: Parameters<typeof api.pvaiHumanTurn>[0]) => Promise<SessionJson>;
  /** Request and animate the AI's turn. Returns updated session. */
  requestAiTurn: () => Promise<SessionJson | null>;
  /** Deactivate PvAI mode. */
  stopPvAi: () => void;
}

const STEP_DELAY_MS = 220; // pause between animation steps

export function usePlayerVsAi(): UsePvAiReturn {
  const [pvaiActive, setPvaiActive] = useState(false);
  const [aiPlayerIndex, setAiPlayerIndex] = useState(1);
  const [aiEngineId, setAiEngineId] = useState<string | null>(null);
  const [aiThinking, setAiThinking] = useState(false);
  const [animating, setAnimating] = useState(false);
  const [currentStep, setCurrentStep] = useState(0);
  const [steps, setSteps] = useState<AiTurnStep[]>([]);
  const [lastAiTurn, setLastAiTurn] = useState<AiTurnResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const sessionResolveRef = useRef<((s: SessionJson | null) => void) | null>(null);

  const startPvAi = useCallback(async (
    engineId: string,
    aiIdx: number,
    minThinkTimeMs: number,
  ) => {
    setError(null);
    try {
      await api.pvaiStart({ engineId, aiPlayerIndex: aiIdx, minThinkTimeMs });
      setAiPlayerIndex(aiIdx);
      setAiEngineId(engineId);
      setPvaiActive(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      throw e;
    }
  }, []);

  const onHumanBuy = useCallback(async (
    req: Parameters<typeof api.pvaiHumanTurn>[0],
  ): Promise<SessionJson> => {
    setError(null);
    setAiThinking(true);
    try {
      const session = await api.pvaiHumanTurn(req);
      return session;
    } catch (e) {
      setAiThinking(false);
      setError(e instanceof Error ? e.message : String(e));
      throw e;
    }
  }, []);

  const requestAiTurn = useCallback(async (): Promise<SessionJson | null> => {
    setError(null);
    setAiThinking(false);
    try {
      const result = await api.pvaiAiTurn();
      setLastAiTurn(result);

      // Decompose into animation steps
      const newSteps = buildSteps(result);
      setSteps(newSteps);
      setCurrentStep(0);
      setAnimating(true);

      // Animate steps sequentially
      return new Promise(resolve => {
        sessionResolveRef.current = resolve;
        animateSteps(newSteps, 0, resolve, result.session ?? null, setCurrentStep, setAnimating);
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return null;
    }
  }, []);

  const stopPvAi = useCallback(() => {
    setPvaiActive(false);
    setAiEngineId(null);
    setAiThinking(false);
    setAnimating(false);
    setSteps([]);
    setCurrentStep(0);
    setLastAiTurn(null);
  }, []);

  return {
    pvaiActive, aiPlayerIndex, aiEngineId,
    aiThinking, animating, currentStep, steps, lastAiTurn, error,
    startPvAi, onHumanBuy, requestAiTurn, stopPvAi,
  };
}

// ─── Helpers ──────────────────────────────────────────────────────────────

function buildSteps(result: AiTurnResult): AiTurnStep[] {
  const steps: AiTurnStep[] = [];

  // Step 1: dice roll
  steps.push({
    type: 'dice',
    diceCount: result.diceCount as 1 | 2,
    rollTotal: result.rollTotal,
    isDoubles: result.isDoubles,
    delayMs: STEP_DELAY_MS,
  });

  // Step 2: Funkturm (if applicable)
  if (result.funkturmKeep != null) {
    steps.push({
      type: 'funkturm',
      funkturmKeep: result.funkturmKeep,
      rerollTotal: result.rerollTotal ?? undefined,
      rerollIsDoubles: result.rerollIsDoubles ?? undefined,
      delayMs: STEP_DELAY_MS,
    });
  }

  // Step 3: income
  steps.push({
    type: 'income',
    coinDeltas: result.coinDeltas,
    delayMs: STEP_DELAY_MS,
  });

  // Step 4: Bürohaus (if applicable)
  if (result.bürohausOwnCardId != null) {
    steps.push({
      type: 'bürohaus',
      swapOwn: result.bürohausOwnCardId,
      swapOpp: result.bürohausOppCardId ?? undefined,
      swapOppPlayer: result.bürohausOppPlayer ?? undefined,
      delayMs: STEP_DELAY_MS,
    });
  }

  // Step 5: purchase
  steps.push({
    type: 'purchase',
    cardId: result.purchasedCardId,
    delayMs: STEP_DELAY_MS,
  });

  // Step 6: done
  steps.push({ type: 'done', delayMs: 0 });

  return steps;
}

function animateSteps(
  steps: AiTurnStep[],
  index: number,
  resolve: (s: SessionJson | null) => void,
  finalSession: SessionJson | null,
  setStep: (i: number) => void,
  setAnimating: (b: boolean) => void,
) {
  if (index >= steps.length) {
    setAnimating(false);
    resolve(finalSession);
    return;
  }
  const step = steps[index];
  setTimeout(() => {
    setStep(index);
    if (step.type === 'done') {
      setAnimating(false);
      resolve(finalSession);
    } else {
      animateSteps(steps, index + 1, resolve, finalSession, setStep, setAnimating);
    }
  }, step.delayMs);
}

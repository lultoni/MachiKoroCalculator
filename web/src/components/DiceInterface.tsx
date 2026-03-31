/** Dice interface — clickable die faces for instant roll selection. */

import { useState, useCallback } from 'react';
import { useLocale } from '../i18n/useLocale';

interface Props {
  canUse2d6: boolean;
  onRollSelect: (diceCount: 1 | 2, die1: number, die2: number | null) => void;
  selectedDie1: number | null;
  selectedDie2: number | null;
  selectedDiceCount: 1 | 2;
}

const FACES = [1, 2, 3, 4, 5, 6];

/** Pip positions for a die face (relative within a 3×3 grid). */
const PIP_POSITIONS: Record<number, [number, number][]> = {
  1: [[1, 1]],
  2: [[0, 2], [2, 0]],
  3: [[0, 2], [1, 1], [2, 0]],
  4: [[0, 0], [0, 2], [2, 0], [2, 2]],
  5: [[0, 0], [0, 2], [1, 1], [2, 0], [2, 2]],
  6: [[0, 0], [0, 2], [1, 0], [1, 2], [2, 0], [2, 2]],
};

function DieFace({ value, selected, onClick }: { value: number; selected: boolean; onClick: () => void }) {
  const pips = PIP_POSITIONS[value];
  return (
    <button
      onClick={onClick}
      className={`w-12 h-12 rounded-lg border-2 transition-all duration-150 grid grid-cols-3 grid-rows-3 p-1.5 gap-0 ${
        selected
          ? 'border-machi-accent bg-machi-accent/10 scale-110 shadow-lg shadow-machi-accent/20'
          : 'border-machi-border bg-machi-surface hover:border-machi-text-dim hover:scale-105'
      }`}
    >
      {Array.from({ length: 9 }, (_, i) => {
        const row = Math.floor(i / 3);
        const col = i % 3;
        const hasPip = pips.some(([r, c]) => r === row && c === col);
        return (
          <span
            key={i}
            className={`w-full h-full rounded-full transition-colors ${
              hasPip ? (selected ? 'bg-machi-accent' : 'bg-machi-text') : ''
            }`}
          />
        );
      })}
    </button>
  );
}

export function DiceInterface({ canUse2d6, onRollSelect, selectedDie1, selectedDie2, selectedDiceCount }: Props) {
  const { t } = useLocale();
  const [diceCount, setDiceCount] = useState<1 | 2>(selectedDiceCount);

  const handleDiceCountChange = useCallback((count: 1 | 2) => {
    setDiceCount(count);
    // Reset selection when switching dice count
    onRollSelect(count, selectedDie1 ?? 0, count === 2 ? (selectedDie2 ?? 0) : null);
  }, [onRollSelect, selectedDie1, selectedDie2]);

  const handleDie1 = useCallback((face: number) => {
    onRollSelect(diceCount, face, diceCount === 2 ? (selectedDie2 ?? 0) : null);
  }, [diceCount, selectedDie2, onRollSelect]);

  const handleDie2 = useCallback((face: number) => {
    onRollSelect(2, selectedDie1 ?? 0, face);
  }, [selectedDie1, onRollSelect]);

  const isDoubles = diceCount === 2 && selectedDie1 != null && selectedDie2 != null && selectedDie1 === selectedDie2 && selectedDie1 > 0;

  return (
    <div className="space-y-3">
      {/* Dice count toggle (only if player owns Bahnhof) */}
      {canUse2d6 && (
        <div className="flex items-center justify-center gap-2">
          <button
            className={`px-3 py-1 rounded-lg text-sm font-medium transition-all ${
              diceCount === 1
                ? 'bg-machi-accent text-machi-bg'
                : 'text-machi-text-dim hover:text-machi-text border border-machi-border'
            }`}
            onClick={() => handleDiceCountChange(1)}
          >
            {t('dice.1d6')}
          </button>
          <button
            className={`px-3 py-1 rounded-lg text-sm font-medium transition-all ${
              diceCount === 2
                ? 'bg-machi-accent text-machi-bg'
                : 'text-machi-text-dim hover:text-machi-text border border-machi-border'
            }`}
            onClick={() => handleDiceCountChange(2)}
          >
            {t('dice.2d6')}
          </button>
        </div>
      )}

      {/* Die 1 */}
      <div className="flex items-center justify-center gap-2">
        {FACES.map(f => (
          <DieFace key={f} value={f} selected={selectedDie1 === f} onClick={() => handleDie1(f)} />
        ))}
      </div>

      {/* Die 2 (only when 2d6) */}
      {diceCount === 2 && (
        <div className="flex items-center justify-center gap-2">
          {FACES.map(f => (
            <DieFace key={f} value={f} selected={selectedDie2 === f} onClick={() => handleDie2(f)} />
          ))}
        </div>
      )}

      {/* Doubles indicator */}
      {isDoubles && (
        <div className="text-center">
          <span className="px-2 py-0.5 rounded bg-machi-yellow/20 text-machi-yellow text-xs font-bold">
            {t('dice.doubles')}
          </span>
        </div>
      )}
    </div>
  );
}

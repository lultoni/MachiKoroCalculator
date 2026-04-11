import { useState, useEffect } from 'react';
import * as api from '../api/client';
import type { PvAiGameSummary, PvAiGameRecord, ProjectDef } from '../api/types';
import { H2hGameReplay } from './H2hGameReplay';

interface Props {
  onBack: () => void;
  projects: { projects: ProjectDef[]; byId: (id: string) => ProjectDef | undefined };
  language: 'de' | 'en';
}

function formatDate(dateStr: string): string {
  try {
    return new Date(dateStr).toLocaleString();
  } catch {
    return dateStr;
  }
}

function formatLuck(luck: number[]): string {
  if (!luck || luck.length === 0) return '';
  return luck.map(l => (l >= 0 ? '+' : '') + l.toFixed(2)).join(' / ');
}

export function PvAiGamesOverview({ onBack, projects, language }: Props) {
  const [games, setGames] = useState<PvAiGameSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedRecord, setSelectedRecord] = useState<PvAiGameRecord | null>(null);
  const [loadingId, setLoadingId] = useState<string | null>(null);

  useEffect(() => {
    api.pvaiGames()
      .then(setGames)
      .catch(e => setError(String(e)))
      .finally(() => setLoading(false));
  }, []);

  const handleSelect = async (id: string) => {
    setLoadingId(id);
    try {
      const record = await api.pvaiGameById(id);
      setSelectedRecord(record);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoadingId(null);
    }
  };

  if (selectedRecord) {
    const playerLabels = selectedRecord.playerNames.slice();
    return (
      <H2hGameReplay
        game={selectedRecord.gameLog}
        engines={playerLabels}
        matchId={selectedRecord.id}
        projects={projects}
        language={language}
        onBack={() => setSelectedRecord(null)}
      />
    );
  }

  return (
    <div className="min-h-screen bg-machi-bg p-4">
      <div className="max-w-3xl mx-auto space-y-4">
        {/* Header */}
        <div className="flex items-center gap-4">
          <button
            className="text-machi-text-dim hover:text-machi-text transition-colors text-sm"
            onClick={onBack}
          >
            ← Back
          </button>
          <h1 className="text-xl font-bold text-machi-text">Saved PvAI Games</h1>
        </div>

        {/* Content */}
        {loading && (
          <p className="text-machi-text-dim text-sm animate-pulse">Loading…</p>
        )}
        {error && (
          <div className="bg-red-900/30 border border-red-500/50 rounded-lg p-3 text-red-300 text-sm">
            {error}
          </div>
        )}
        {!loading && !error && games.length === 0 && (
          <div className="bg-machi-surface rounded-xl p-8 border border-machi-border text-center text-machi-text-dim">
            No saved games yet. Finish a PvAI game and click "Save Game" on the win screen.
          </div>
        )}
        {!loading && games.length > 0 && (
          <div className="space-y-2">
            {[...games].reverse().map(g => {
              const humanIdx = g.humanPlayerIndex;
              const aiIdx = 1 - humanIdx;
              const humanWon = g.winnerIndex === humanIdx;
              const humanName = g.playerNames[humanIdx] ?? 'Human';
              const aiName = g.playerNames[aiIdx] ?? 'AI';
              return (
                <button
                  key={g.id}
                  className="w-full text-left bg-machi-surface hover:bg-machi-surface/80 border border-machi-border rounded-xl p-4 transition-colors"
                  onClick={() => handleSelect(g.id)}
                  disabled={loadingId === g.id}
                >
                  <div className="flex items-start justify-between gap-4">
                    {/* Left: outcome + names */}
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <span className={`text-xs font-bold px-2 py-0.5 rounded-full ${humanWon ? 'bg-green-900/40 text-green-400' : 'bg-red-900/40 text-red-400'}`}>
                          {humanWon ? 'WIN' : 'LOSS'}
                        </span>
                        <span className="text-sm font-semibold text-machi-text">
                          {humanName} vs {aiName}
                        </span>
                        <span className="text-xs text-machi-text-dim">({g.engineId})</span>
                      </div>
                      <div className="text-xs text-machi-text-dim flex gap-3">
                        <span>{g.totalTurns} turns</span>
                        {g.finalCoins && <span>Coins: {g.finalCoins.join(' / ')}</span>}
                        {g.landmarkCounts && <span>Landmarks: {g.landmarkCounts.join(' / ')}</span>}
                        {g.totalLuck && g.totalLuck.length > 0 && (
                          <span>Luck: {formatLuck(g.totalLuck)}</span>
                        )}
                      </div>
                    </div>
                    {/* Right: date + loading indicator */}
                    <div className="text-right shrink-0">
                      <div className="text-xs text-machi-text-dim">{formatDate(g.date)}</div>
                      {loadingId === g.id && (
                        <div className="text-xs text-machi-accent animate-pulse mt-1">Loading…</div>
                      )}
                    </div>
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

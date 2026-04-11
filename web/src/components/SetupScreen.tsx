/** Setup screen — new game creation + saved games list. */

import { useState, useEffect } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { CreateSessionRequest, FromSnapshotRequest } from '../api/types';
import * as api from '../api/client';

export interface PvAiSetupConfig {
  engineId: string;
  aiPlayerIndex: number;
  minThinkTimeMs: number;
}

interface Props {
  onStart: (req: CreateSessionRequest, pvai?: PvAiSetupConfig) => Promise<void>;
  onLoad: (filename: string) => Promise<void>;
  onFromSnapshot: (req: FromSnapshotRequest) => Promise<void>;
  loading: boolean;
  error: string | null;
  onH2h?: () => void;
}

const DEFAULT_NAMES = ['Alice', 'Bob', 'Carol', 'Dave'];

const ENGINE_OPTIONS = [
  { id: 'mcts-v1',      label: 'MCTS v1 (Recommended)' },
  { id: 'flat-mc',      label: 'Flat MC' },
  { id: 'creator',      label: 'Creator' },
  { id: 'expectimax',   label: 'Expectimax' },
  { id: 'heuristic-ev', label: 'Heuristic (instant)' },
];

export function SetupScreen({ onStart, onLoad, onFromSnapshot, loading, error, onH2h }: Props) {
  const { t, locale, setLocale } = useLocale();
  const [playerCount, setPlayerCount] = useState(2);
  const [names, setNames] = useState<string[]>([...DEFAULT_NAMES]);
  const [saves, setSaves] = useState<{ filename: string; lastModified: string }[]>([]);
  const [showSaves, setShowSaves] = useState(false);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [snapshotJson, setSnapshotJson] = useState('');

  // PvAI mode state
  const [mode, setMode] = useState<'pvp' | 'pvai'>('pvp');
  const [aiSeat, setAiSeat] = useState(1);
  const [engineId, setEngineId] = useState('mcts-v1');
  const [minThinkMs, setMinThinkMs] = useState(1500);

  // Fetch save count on mount so the counter shows immediately
  useEffect(() => {
    api.listSaves().then(setSaves).catch(() => {});
  }, []);

  const handleStart = () => {
    const req: CreateSessionRequest = { playerCount, playerNames: names.slice(0, playerCount) };
    const pvai: PvAiSetupConfig | undefined = mode === 'pvai'
      ? { engineId, aiPlayerIndex: aiSeat, minThinkTimeMs: minThinkMs }
      : undefined;
    onStart(req, pvai);
  };

  const handleSnapshot = () => {
    try {
      const parsed = JSON.parse(snapshotJson);
      onFromSnapshot({ players: parsed.players ?? parsed });
    } catch { /* ignore bad JSON */ }
  };

  return (
    <div className="min-h-screen bg-machi-bg flex items-center justify-center p-4">
      <div className="w-full max-w-md space-y-6">
        {/* Header */}
        <div className="text-center">
          <h1 className="text-3xl font-bold text-machi-text">{t('app.title')}</h1>
          <div className="mt-2 flex items-center justify-center gap-4">
            <button
              className="text-sm text-machi-text-dim hover:text-machi-accent transition-colors"
              onClick={() => setLocale(locale === 'de' ? 'en' : 'de')}
            >
              {locale === 'de' ? 'EN' : 'DE'}
            </button>
            {onH2h && (
              <button
                className="text-sm text-machi-text-dim hover:text-machi-accent transition-colors"
                onClick={onH2h}
              >
                {t('h2h.nav')}
              </button>
            )}
          </div>
        </div>

        {/* Error */}
        {error && (
          <div className="bg-red-900/30 border border-red-500/50 rounded-lg p-3 text-red-300 text-sm">
            {error}
          </div>
        )}

        {/* New Game */}
        <div className="bg-machi-surface rounded-xl p-6 border border-machi-border space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold text-machi-text">{t('setup.title')}</h2>
            {/* PvP / PvAI toggle */}
            <div className="flex rounded-lg overflow-hidden border border-machi-border text-xs font-medium">
              <button
                className={`px-3 py-1.5 transition-all ${mode === 'pvp' ? 'bg-machi-accent text-machi-bg' : 'bg-machi-bg text-machi-text-dim hover:text-machi-text'}`}
                onClick={() => setMode('pvp')}
              >
                PvP
              </button>
              <button
                className={`px-3 py-1.5 transition-all ${mode === 'pvai' ? 'bg-machi-accent text-machi-bg' : 'bg-machi-bg text-machi-text-dim hover:text-machi-text'}`}
                onClick={() => { setMode('pvai'); setPlayerCount(2); }}
              >
                vs AI
              </button>
            </div>
          </div>

          {/* Player count — hidden in PvAI (always 2) */}
          {mode === 'pvp' && (
            <div>
              <label className="text-sm text-machi-text-dim">{t('setup.playerCount')}</label>
              <div className="flex gap-2 mt-1">
                {[2, 3, 4].map(n => (
                  <button
                    key={n}
                    className={`px-4 py-2 rounded-lg font-medium transition-all ${
                      playerCount === n
                        ? 'bg-machi-accent text-machi-bg'
                        : 'bg-machi-bg text-machi-text-dim hover:text-machi-text border border-machi-border'
                    }`}
                    onClick={() => setPlayerCount(n)}
                  >
                    {n}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Player names */}
          <div className="space-y-2">
            {Array.from({ length: playerCount }, (_, i) => (
              <div key={i} className="flex items-center gap-2">
                <input
                  className="flex-1 bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-machi-text placeholder:text-machi-text-dim/50 focus:outline-none focus:border-machi-accent transition-colors"
                  placeholder={t('setup.playerName', { n: i + 1 })}
                  value={names[i] ?? ''}
                  onChange={e => {
                    const next = [...names];
                    next[i] = e.target.value;
                    setNames(next);
                  }}
                />
                {mode === 'pvai' && (
                  <span className={`text-xs px-2 py-1 rounded ${aiSeat === i ? 'bg-machi-accent/20 text-machi-accent' : 'text-machi-text-dim'}`}>
                    {aiSeat === i ? 'AI' : 'You'}
                  </span>
                )}
              </div>
            ))}
          </div>

          {/* PvAI configuration */}
          {mode === 'pvai' && (
            <div className="space-y-3 pt-1 border-t border-machi-border">
              <div>
                <label className="text-xs text-machi-text-dim block mb-1">AI plays as</label>
                <div className="flex gap-2">
                  {Array.from({ length: 2 }, (_, i) => (
                    <button
                      key={i}
                      className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                        aiSeat === i
                          ? 'bg-machi-accent text-machi-bg'
                          : 'bg-machi-bg border border-machi-border text-machi-text-dim hover:text-machi-text'
                      }`}
                      onClick={() => setAiSeat(i)}
                    >
                      {names[i] || `Player ${i + 1}`}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="text-xs text-machi-text-dim block mb-1">AI Engine</label>
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

              <div>
                <label className="text-xs text-machi-text-dim block mb-1">
                  Think time: {minThinkMs / 1000}s
                </label>
                <input
                  type="range"
                  min={500} max={10000} step={500}
                  value={minThinkMs}
                  onChange={e => setMinThinkMs(Number(e.target.value))}
                  className="w-full accent-machi-accent"
                />
              </div>
            </div>
          )}

          {/* Start button */}
          <button
            className="w-full py-3 rounded-lg font-semibold bg-machi-accent text-machi-bg hover:brightness-110 active:scale-[0.98] transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            onClick={handleStart}
            disabled={loading}
          >
            {loading ? '...' : mode === 'pvai' ? 'Start vs AI' : t('btn.start')}
          </button>
        </div>

        {/* Saved games — collapsible */}
        <div className="bg-machi-surface rounded-xl border border-machi-border overflow-hidden">
          <button
            className="w-full px-6 py-3 flex items-center gap-2 text-left text-sm text-machi-text-dim hover:text-machi-text transition-colors"
            onClick={() => setShowSaves(!showSaves)}
          >
            <span className="font-medium">{t('setup.savedGames')}</span>
            <span className="text-xs">({saves.length})</span>
            <span className="ml-auto">{showSaves ? '▾' : '▸'}</span>
          </button>
          {showSaves && (
            <div className="px-6 pb-4 space-y-2">
              {saves.length === 0 && (
                <p className="text-xs text-machi-text-dim">—</p>
              )}
              {saves.map(s => (
                <button
                  key={s.filename}
                  className="w-full text-left bg-machi-bg border border-machi-border rounded-lg px-3 py-2 hover:border-machi-accent transition-colors group"
                  onClick={() => onLoad(s.filename)}
                  disabled={loading}
                >
                  <span className="text-sm text-machi-text group-hover:text-machi-accent transition-colors">
                    {s.filename}
                  </span>
                  <span className="block text-xs text-machi-text-dim">{s.lastModified}</span>
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Advanced — from snapshot */}
        <div className="bg-machi-surface rounded-xl border border-machi-border overflow-hidden">
          <button
            className="w-full px-6 py-3 text-left text-sm text-machi-text-dim hover:text-machi-text transition-colors"
            onClick={() => setShowAdvanced(!showAdvanced)}
          >
            {t('setup.advanced')} {showAdvanced ? '▾' : '▸'}
          </button>
          {showAdvanced && (
            <div className="px-6 pb-4 space-y-2">
              <label className="text-xs text-machi-text-dim">{t('setup.jumpBackIn')}</label>
              <textarea
                className="w-full h-24 bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-sm text-machi-text font-mono resize-none focus:outline-none focus:border-machi-accent"
                placeholder='{"players": [...]}'
                value={snapshotJson}
                onChange={e => setSnapshotJson(e.target.value)}
              />
              <button
                className="px-4 py-2 rounded-lg text-sm bg-machi-accent/20 text-machi-accent hover:bg-machi-accent/30 transition-colors disabled:opacity-50"
                onClick={handleSnapshot}
                disabled={loading || !snapshotJson.trim()}
              >
                {t('btn.load')}
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

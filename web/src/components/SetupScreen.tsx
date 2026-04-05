/** Setup screen — new game creation + saved games list. */

import { useState, useEffect } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { CreateSessionRequest, FromSnapshotRequest } from '../api/types';
import * as api from '../api/client';

interface Props {
  onStart: (req: CreateSessionRequest) => Promise<void>;
  onLoad: (filename: string) => Promise<void>;
  onFromSnapshot: (req: FromSnapshotRequest) => Promise<void>;
  loading: boolean;
  error: string | null;
  onH2h?: () => void;
}

const DEFAULT_NAMES = ['Alice', 'Bob', 'Carol', 'Dave'];

export function SetupScreen({ onStart, onLoad, onFromSnapshot, loading, error, onH2h }: Props) {
  const { t, locale, setLocale } = useLocale();
  const [playerCount, setPlayerCount] = useState(2);
  const [names, setNames] = useState<string[]>([...DEFAULT_NAMES]);
  const [saves, setSaves] = useState<{ filename: string; lastModified: string }[]>([]);
  const [savesLoaded, setSavesLoaded] = useState(false);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [snapshotJson, setSnapshotJson] = useState('');

  // Fetch save count on mount so the counter shows immediately
  useEffect(() => {
    api.listSaves().then(list => { setSaves(list); setSavesLoaded(true); }).catch(() => {});
  }, []);

  const loadSaves = async () => {
    if (savesLoaded) return;
    try {
      const list = await api.listSaves();
      setSaves(list);
    } catch { /* ignore */ }
    setSavesLoaded(true);
  };

  const handleStart = () => {
    onStart({ playerCount, playerNames: names.slice(0, playerCount) });
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
          <h2 className="text-lg font-semibold text-machi-text">{t('setup.title')}</h2>

          {/* Player count */}
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

          {/* Player names */}
          <div className="space-y-2">
            {Array.from({ length: playerCount }, (_, i) => (
              <input
                key={i}
                className="w-full bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-machi-text placeholder:text-machi-text-dim/50 focus:outline-none focus:border-machi-accent transition-colors"
                placeholder={t('setup.playerName', { n: i + 1 })}
                value={names[i] ?? ''}
                onChange={e => {
                  const next = [...names];
                  next[i] = e.target.value;
                  setNames(next);
                }}
              />
            ))}
          </div>

          {/* Start button */}
          <button
            className="w-full py-3 rounded-lg font-semibold bg-machi-accent text-machi-bg hover:brightness-110 active:scale-[0.98] transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            onClick={handleStart}
            disabled={loading}
          >
            {loading ? '...' : t('btn.start')}
          </button>
        </div>

        {/* Saved games */}
        <div className="bg-machi-surface rounded-xl p-6 border border-machi-border space-y-3">
          <button
            className="flex items-center gap-2 text-machi-text-dim hover:text-machi-text transition-colors text-sm w-full"
            onClick={loadSaves}
          >
            <span className="font-medium">{t('setup.savedGames')}</span>
            <span className="text-xs">({saves.length})</span>
          </button>
          {savesLoaded && saves.length === 0 && (
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

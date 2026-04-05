/** Save/Load menu — slide-out panel for game persistence. */

import { useState, useEffect } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { SaveEntry } from '../api/types';
import * as api from '../api/client';

interface Props {
  onSave: (filename?: string) => Promise<string>;
  onLoad: (filename: string) => Promise<void>;
  loading: boolean;
  onClose: () => void;
}

export function SaveLoadMenu({ onSave, onLoad, loading, onClose }: Props) {
  const { t } = useLocale();
  const [saves, setSaves] = useState<SaveEntry[]>([]);
  const [filename, setFilename] = useState('');
  const [savedPath, setSavedPath] = useState<string | null>(null);

  useEffect(() => {
    api.listSaves().then(setSaves).catch(() => {});
  }, []);

  const handleSave = async () => {
    try {
      const path = await onSave(filename || undefined);
      setSavedPath(path);
      // Refresh saves list
      api.listSaves().then(setSaves).catch(() => {});
    } catch {
      // Error is shown via session.error in parent
    }
  };

  const handleLoad = async (fn: string) => {
    await onLoad(fn);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div
        className="bg-machi-surface rounded-2xl border border-machi-border p-6 max-w-md w-full mx-4 space-y-5 shadow-2xl"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold text-machi-text">
            {t('btn.save')} / {t('btn.load')}
          </h2>
          <button
            className="text-machi-text-dim hover:text-machi-text transition-colors text-lg"
            onClick={onClose}
          >
            ✕
          </button>
        </div>

        {/* Save */}
        <div className="space-y-2">
          <label className="text-sm text-machi-text-dim">{t('btn.save')}</label>
          <div className="flex gap-2">
            <input
              className="flex-1 bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-sm text-machi-text placeholder:text-machi-text-dim/50 focus:outline-none focus:border-machi-accent"
              placeholder="game_name.mkoro"
              value={filename}
              onChange={e => setFilename(e.target.value)}
            />
            <button
              className="px-4 py-2 rounded-lg font-medium bg-machi-accent text-machi-bg hover:brightness-110 transition-all disabled:opacity-50 text-sm"
              onClick={handleSave}
              disabled={loading}
            >
              {t('btn.save')}
            </button>
          </div>
          {savedPath && (
            <p className="text-xs text-machi-green">Saved: {savedPath}</p>
          )}
        </div>

        {/* Load */}
        <div className="space-y-2">
          <label className="text-sm text-machi-text-dim">{t('btn.load')}</label>
          {saves.length === 0 ? (
            <p className="text-xs text-machi-text-dim">—</p>
          ) : (
            <div className="space-y-1.5 max-h-48 overflow-y-auto">
              {saves.map(s => (
                <button
                  key={s.filename}
                  className="w-full text-left bg-machi-bg border border-machi-border rounded-lg px-3 py-2 hover:border-machi-accent transition-colors group"
                  onClick={() => handleLoad(s.filename)}
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
      </div>
    </div>
  );
}

/** Settings screen — engine, mode, language, autosave, user player selection. */

import { useState, useEffect } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { Settings } from '../hooks/useSettings';
import type { EngineRegistryEntry, PlayerState } from '../api/types';
import * as api from '../api/client';

interface Props {
  settings: Settings;
  update: (partial: Partial<Settings>) => void;
  players: PlayerState[];
  onClose: () => void;
}

export function SettingsScreen({ settings, update, players, onClose }: Props) {
  const { t, locale, setLocale } = useLocale();
  const [engines, setEngines] = useState<EngineRegistryEntry[]>([]);

  useEffect(() => {
    api.getEngines().then(setEngines).catch(() => {});
  }, []);

  // Group engines by class
  const grouped = engines.reduce<Record<string, EngineRegistryEntry[]>>((acc, e) => {
    (acc[e.engineClass] ??= []).push(e);
    return acc;
  }, {});

  // Selected engine details
  const selectedEngine = engines.find(e => e.id === settings.engineId);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div
        className="bg-machi-surface rounded-2xl border border-machi-border p-6 max-w-2xl w-full mx-4 space-y-5 shadow-2xl max-h-[85vh] overflow-y-auto"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold text-machi-text">{t('settings.title')}</h2>
          <button
            className="text-machi-text-dim hover:text-machi-text transition-colors text-lg"
            onClick={onClose}
          >
            ✕
          </button>
        </div>

        {/* Engine */}
        <div className="space-y-1.5">
          <label className="text-sm text-machi-text-dim">{t('settings.engine')}</label>
          {Object.entries(grouped).map(([cls, list]) => (
            <div key={cls}>
              <div className="text-xs text-machi-text-dim/60 uppercase tracking-wider mt-2 mb-1">{cls}</div>
              <div className="flex flex-wrap gap-1.5">
                {list.map(e => (
                  <button
                    key={e.id}
                    className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                      settings.engineId === e.id
                        ? 'bg-machi-accent text-machi-bg'
                        : 'border border-machi-border text-machi-text-dim hover:text-machi-text hover:border-machi-text-dim'
                    }`}
                    onClick={() => update({ engineId: e.id })}
                    title={e.description}
                  >
                    {e.id}
                    {e.isDefault && ' ★'}
                  </button>
                ))}
              </div>
            </div>
          ))}
          {engines.length === 0 && (
            <p className="text-xs text-machi-text-dim animate-pulse">Loading engines...</p>
          )}

          {/* Selected engine details */}
          {selectedEngine && (
            <div className="mt-3 p-3 rounded-lg bg-machi-bg/50 border border-machi-border/50 space-y-1.5">
              <div className="text-xs font-medium text-machi-text">{selectedEngine.id}</div>
              <div className="text-xs text-machi-text-dim">{selectedEngine.description}</div>
              <div className="flex flex-wrap gap-2 text-[10px]">
                <span className="px-1.5 py-0.5 rounded bg-machi-accent/15 text-machi-accent font-medium uppercase">
                  {selectedEngine.tier}
                </span>
                <span className="text-machi-text-dim">
                  {selectedEngine.config?.iterations ?? 0} iterations
                </span>
                {selectedEngine.config?.extra && Object.entries(selectedEngine.config.extra).map(([k, v]) => (
                  <span key={k} className="text-machi-text-dim">
                    {k}: {v}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Language */}
        <div className="space-y-1.5">
          <label className="text-sm text-machi-text-dim">{t('settings.language')}</label>
          <div className="flex gap-2">
            {(['de', 'en'] as const).map(l => (
              <button
                key={l}
                className={`px-4 py-2 rounded-lg font-medium text-sm transition-all ${
                  locale === l
                    ? 'bg-machi-accent text-machi-bg'
                    : 'border border-machi-border text-machi-text-dim hover:text-machi-text'
                }`}
                onClick={() => { setLocale(l); update({ language: l }); }}
              >
                {l.toUpperCase()}
              </button>
            ))}
          </div>
        </div>

        {/* Autosave */}
        <div className="flex items-center justify-between">
          <label className="text-sm text-machi-text-dim">{t('settings.autosave')}</label>
          <button
            className={`w-12 h-6 rounded-full transition-colors relative ${
              settings.autosave ? 'bg-machi-accent' : 'bg-machi-border'
            }`}
            onClick={() => update({ autosave: !settings.autosave })}
          >
            <span
              className={`absolute top-0.5 w-5 h-5 rounded-full bg-white transition-transform ${
                settings.autosave ? 'translate-x-6' : 'translate-x-0.5'
              }`}
            />
          </button>
        </div>

        {/* User player */}
        <div className="space-y-1.5">
          <label className="text-sm text-machi-text-dim">{t('settings.userPlayer')}</label>
          <div className="flex gap-2">
            {players.map((p, i) => (
              <button
                key={i}
                className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-all ${
                  settings.userPlayerIndex === i
                    ? 'bg-machi-accent text-machi-bg'
                    : 'border border-machi-border text-machi-text-dim hover:text-machi-text'
                }`}
                onClick={() => update({ userPlayerIndex: i })}
              >
                {p.name}
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

/** Settings hook — localStorage-backed user preferences. */

import { useState, useCallback } from 'react';

export interface Settings {
  engineId: string;
  language: 'de' | 'en';
  autosave: boolean;
  userPlayerIndex: number;
  showBürohausPanel: boolean;
}

const STORAGE_KEY = 'machi-settings';

const DEFAULTS: Settings = {
  engineId: 'mcts-v1-balanced',
  language: 'de',
  autosave: false,
  userPlayerIndex: 0,
  showBürohausPanel: false,
};

function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? { ...DEFAULTS, ...JSON.parse(raw) } : { ...DEFAULTS };
    // Sync language with locale provider's storage (single source of truth)
    const storedLocale = localStorage.getItem('machi-locale') as 'de' | 'en' | null;
    if (storedLocale && (storedLocale === 'de' || storedLocale === 'en')) {
      parsed.language = storedLocale;
    }
    return parsed;
  } catch { /* ignore corrupt data */ }
  return { ...DEFAULTS };
}

function persistSettings(s: Settings): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
}

export function useSettings() {
  const [settings, setSettingsState] = useState<Settings>(loadSettings);

  const update = useCallback((partial: Partial<Settings>) => {
    setSettingsState(prev => {
      const next = { ...prev, ...partial };
      persistSettings(next);
      return next;
    });
  }, []);

  return { settings, update };
}

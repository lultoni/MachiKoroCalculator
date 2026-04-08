import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { useH2h } from '../hooks/useH2h';
import { useLocale } from '../i18n/useLocale';
import * as api from '../api/client';
import type { EngineRegistryEntry, ProjectDef } from '../api/types';
import { H2hMatchDetail } from './H2hMatchDetail';
import { H2hGameReplay } from './H2hGameReplay';
import { H2hRatings } from './H2hRatings';
import { H2hSweepResults } from './H2hSweepResults';

interface Props {
  onBack: () => void;
  projects: { projects: ProjectDef[]; byId: (id: string) => ProjectDef | undefined };
  language: 'de' | 'en';
}

/** Derive editable config fields from a registry entry's config. */
function buildConfigFields(entry: EngineRegistryEntry): { key: string; value: string }[] {
  const fields: { key: string; value: string }[] = [];
  const cfg = entry.config;
  if (cfg) {
    fields.push({ key: 'iterations', value: String(cfg.iterations ?? 0) });
    if (cfg.timeBudgetMs) fields.push({ key: 'timeBudgetMs', value: String(cfg.timeBudgetMs) });
    if (cfg.riskToleranceWeight) fields.push({ key: 'riskToleranceWeight', value: String(cfg.riskToleranceWeight) });
    if (cfg.extra) {
      for (const [k, v] of Object.entries(cfg.extra)) {
        fields.push({ key: k, value: v });
      }
    }
  }
  return fields;
}

/** Build a config map from field entries. */
function fieldsToConfigMap(fields: { key: string; value: string }[]): Record<string, string> {
  const map: Record<string, string> = {};
  for (const f of fields) {
    map[f.key] = f.value;
  }
  return map;
}

/** Known parameter hints for engine config fields (#18). */
const PARAM_HINTS: Record<string, { description: string; options?: string[] }> = {
  iterations:           { description: 'MCTS iterations (0 = unlimited)' },
  timeBudgetMs:         { description: 'Time budget in ms (0 = no limit)' },
  riskToleranceWeight:  { description: 'Risk tolerance [0.0–1.0]' },
  rolloutTemperature:   { description: 'Boltzmann temperature (e.g. 0.3, 0.7, 2.0)' },
  maxRolloutDepth:      { description: 'Max rollout depth in turns (e.g. 3, 7, 10)' },
  maxDepthRounds:       { description: 'Expectimax search depth (e.g. 1, 2)' },
  leafEval:             { description: 'Leaf evaluator function', options: ['winprob', 'composite'] },
};

/** Format milliseconds as human-readable elapsed time. */
function formatElapsed(ms: number): string {
  const totalSec = Math.floor(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

export function H2hOverview({ onBack, projects, language }: Props) {
  const h2h = useH2h();
  const { t } = useLocale();
  const [engines, setEngines] = useState<EngineRegistryEntry[]>([]);
  const [engineA, setEngineA] = useState('mcts-v1-fast');
  const [engineB, setEngineB] = useState('mcts-v1-fast');
  const [games, setGames] = useState(100);
  const [maxTurns, setMaxTurns] = useState(200);
  const [seatSwap, setSeatSwap] = useState(true);
  const [fieldsA, setFieldsA] = useState<{ key: string; value: string }[]>([]);
  const [fieldsB, setFieldsB] = useState<{ key: string; value: string }[]>([]);
  const [view, setView] = useState<'overview' | 'ratings' | 'sweep'>('overview');

  // Auto Battle state
  const [autoStatus, setAutoStatus] = useState<api.AutoBattleStatusResponse | null>(null);
  const [autoGames, setAutoGames] = useState(50);
  const [autoMaxRounds, setAutoMaxRounds] = useState(20);
  const [autoEndless, setAutoEndless] = useState(true);
  const [autoTier, setAutoTier] = useState('');
  const [autoStopping, setAutoStopping] = useState(false);
  const autoPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastSeenRoundsRef = useRef<number>(-1);
  const loadResultsRef = useRef(h2h.loadResults);
  loadResultsRef.current = h2h.loadResults;

  // Export/Import state
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [importMessage, setImportMessage] = useState<string | null>(null);

  const pollAutoStatus = useCallback(() => {
    if (autoPollRef.current) clearInterval(autoPollRef.current);
    lastSeenRoundsRef.current = -1;
    autoPollRef.current = setInterval(async () => {
      try {
        const status = await api.h2hAutoStatus();
        setAutoStatus(status);
        // Reload results when a new round completes
        const rounds = status.roundsCompleted ?? 0;
        if (rounds > lastSeenRoundsRef.current && lastSeenRoundsRef.current >= 0) {
          loadResultsRef.current();
        }
        lastSeenRoundsRef.current = rounds;
        if (!status.running) {
          if (autoPollRef.current) clearInterval(autoPollRef.current);
          autoPollRef.current = null;
          setAutoStopping(false);
          loadResultsRef.current();
        }
      } catch { /* ignore */ }
    }, 2000);
  }, []);

  // Check auto battle status on mount
  useEffect(() => {
    api.h2hAutoStatus().then(s => {
      setAutoStatus(s);
      if (s.running) pollAutoStatus();
    }).catch(() => {});
  }, [pollAutoStatus]);

  // Cleanup auto poll on unmount
  useEffect(() => {
    return () => {
      if (autoPollRef.current) clearInterval(autoPollRef.current);
    };
  }, []);

  const handleAutoStart = async () => {
    try {
      const rounds = autoEndless ? 0 : autoMaxRounds;
      await api.h2hAutoStart({
        gamesPerMatch: autoGames,
        maxRounds: rounds,
        tier: autoTier || undefined,
      });
      setAutoStatus({ running: true, roundsCompleted: 0, maxRounds: rounds, endless: autoEndless });
      pollAutoStatus();
    } catch (e: unknown) {
      setAutoStatus({ running: false, error: (e as Error).message });
    }
  };

  const handleAutoStop = async () => {
    setAutoStopping(true);
    try {
      await api.h2hAutoStop();
    } catch { /* ignore */ }
  };

  const handleExport = async () => {
    try {
      await api.h2hExport();
    } catch { /* ignore */ }
  };

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = async (event) => {
      const content = event.target?.result as string;
      const result = await h2h.importResults(content);
      if (result) {
        if (result.imported > 0) {
          setImportMessage(
            t('h2h.importSuccess')
              .replace('{imported}', String(result.imported))
              .replace('{skipped}', String(result.skipped))
          );
        } else {
          setImportMessage(t('h2h.importNone'));
        }
        setTimeout(() => setImportMessage(null), 5000);
      }
    };
    reader.readAsText(file);
    e.target.value = '';
  };

  useEffect(() => {
    h2h.loadResults();
    api.getEngines().then(setEngines).catch(() => {});
  }, []);

  // When engines load, initialize config fields for default selections
  const engineAInfo = useMemo(() => engines.find(e => e.id === engineA), [engines, engineA]);
  const engineBInfo = useMemo(() => engines.find(e => e.id === engineB), [engines, engineB]);

  // Reset fields when engine selection changes
  useEffect(() => {
    if (engineAInfo) setFieldsA(buildConfigFields(engineAInfo));
  }, [engineAInfo]);
  useEffect(() => {
    if (engineBInfo) setFieldsB(buildConfigFields(engineBInfo));
  }, [engineBInfo]);

  // Ratings view
  if (view === 'ratings') {
    return <H2hRatings onBack={() => setView('overview')} />;
  }

  // Sweep results view
  if (view === 'sweep') {
    return <H2hSweepResults onBack={() => setView('overview')} />;
  }

  // Game replay view
  if (h2h.selectedGame && h2h.selectedResult) {
    return (
      <H2hGameReplay
        game={h2h.selectedGame}
        engines={h2h.selectedResult.config.engineIds}
        matchId={h2h.selectedResult.id}
        projects={projects}
        language={language}
        onBack={h2h.clearGame}
      />
    );
  }

  // Match detail view
  if (h2h.selectedResult) {
    return (
      <H2hMatchDetail
        result={h2h.selectedResult}
        onBack={h2h.clearSelection}
        onSelectGame={(idx) => h2h.selectGame(h2h.selectedResult!.id, idx)}
      />
    );
  }

  const updateFieldA = (index: number, value: string) => {
    setFieldsA(prev => prev.map((f, i) => i === index ? { ...f, value } : f));
  };
  const updateFieldB = (index: number, value: string) => {
    setFieldsB(prev => prev.map((f, i) => i === index ? { ...f, value } : f));
  };

  const handleStart = () => {
    const configA = fieldsA.length > 0 ? fieldsToConfigMap(fieldsA) : undefined;
    const configB = fieldsB.length > 0 ? fieldsToConfigMap(fieldsB) : undefined;
    h2h.startMatch(engineA, engineB, games, configA, configB,
      maxTurns !== 200 ? maxTurns : undefined,
      seatSwap ? undefined : false);
  };

  // Overview
  return (
    <div className="min-h-screen bg-machi-bg text-machi-text p-6">
      <div className="max-w-4xl mx-auto">
        {/* Header */}
        <div className="flex items-center gap-4 mb-6">
          <button
            onClick={onBack}
            className="text-machi-text-dim hover:text-machi-text transition"
          >
            ← {t('btn.back')}
          </button>
          <h1 className="text-2xl font-bold">{t('h2h.title')}</h1>
          <button
            onClick={() => setView('ratings')}
            className="ml-auto text-sm text-machi-text-dim hover:text-machi-accent transition-colors"
          >
            {t('h2h.ratingsNav')}
          </button>
          <button
            onClick={() => setView('sweep')}
            className="text-sm text-machi-text-dim hover:text-machi-accent transition-colors"
          >
            {t('sweep.nav')}
          </button>
        </div>

        {/* Start Match Panel */}
        <div className="bg-machi-surface rounded-xl p-6 mb-6 border border-machi-border">
          <h2 className="text-lg font-semibold mb-4">{t('h2h.newMatch')}</h2>
          <div className="grid grid-cols-2 gap-4 mb-4">
            {/* Engine A */}
            <div>
              <label className="block text-sm text-machi-text-dim mb-1">{t('h2h.engineA')}</label>
              <select
                value={engineA}
                onChange={e => setEngineA(e.target.value)}
                className="w-full bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-sm"
              >
                {engines.map(e => (
                  <option key={e.id} value={e.id}>{e.id}</option>
                ))}
              </select>
              {engineAInfo && (
                <div className="text-[10px] text-machi-text-dim/60 mt-1">
                  {engineAInfo.tier} · {engineAInfo.description}
                </div>
              )}
              {/* Per-engine config fields */}
              {fieldsA.length > 0 && (
                <div className="mt-2 space-y-1.5">
                  {fieldsA.map((f, i) => {
                    const hint = PARAM_HINTS[f.key];
                    return (
                      <div key={f.key} className="flex items-center gap-2">
                        <label className="text-[11px] text-machi-text-dim w-32 shrink-0 text-right" title={hint?.description}>{f.key}</label>
                        {hint?.options ? (
                          <select
                            value={f.value}
                            onChange={e => updateFieldA(i, e.target.value)}
                            className="flex-1 bg-machi-bg border border-machi-border rounded px-2 py-1 text-xs font-mono"
                          >
                            {hint.options.map(o => <option key={o} value={o}>{o}</option>)}
                          </select>
                        ) : (
                          <input
                            type="text"
                            value={f.value}
                            onChange={e => updateFieldA(i, e.target.value)}
                            className="flex-1 bg-machi-bg border border-machi-border rounded px-2 py-1 text-xs font-mono"
                            title={hint?.description}
                          />
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
            {/* Engine B */}
            <div>
              <label className="block text-sm text-machi-text-dim mb-1">{t('h2h.engineB')}</label>
              <select
                value={engineB}
                onChange={e => setEngineB(e.target.value)}
                className="w-full bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-sm"
              >
                {engines.map(e => (
                  <option key={e.id} value={e.id}>{e.id}</option>
                ))}
              </select>
              {engineBInfo && (
                <div className="text-[10px] text-machi-text-dim/60 mt-1">
                  {engineBInfo.tier} · {engineBInfo.description}
                </div>
              )}
              {/* Per-engine config fields */}
              {fieldsB.length > 0 && (
                <div className="mt-2 space-y-1.5">
                  {fieldsB.map((f, i) => {
                    const hint = PARAM_HINTS[f.key];
                    return (
                      <div key={f.key} className="flex items-center gap-2">
                        <label className="text-[11px] text-machi-text-dim w-32 shrink-0 text-right" title={hint?.description}>{f.key}</label>
                        {hint?.options ? (
                          <select
                            value={f.value}
                            onChange={e => updateFieldB(i, e.target.value)}
                            className="flex-1 bg-machi-bg border border-machi-border rounded px-2 py-1 text-xs font-mono"
                          >
                            {hint.options.map(o => <option key={o} value={o}>{o}</option>)}
                          </select>
                        ) : (
                          <input
                            type="text"
                            value={f.value}
                            onChange={e => updateFieldB(i, e.target.value)}
                            className="flex-1 bg-machi-bg border border-machi-border rounded px-2 py-1 text-xs font-mono"
                            title={hint?.description}
                          />
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </div>

          {/* Games (full width row) */}
          <div className="mb-4 flex flex-wrap items-center gap-6">
            <div>
              <label className="block text-sm text-machi-text-dim mb-1">{t('h2h.games')}</label>
              <input
                type="number"
                value={games}
                onChange={e => setGames(Number(e.target.value))}
                min={1}
                max={1000}
                className="w-40 bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label className="block text-sm text-machi-text-dim mb-1">{t('h2h.maxTurns')}</label>
              <input
                type="number"
                value={maxTurns}
                onChange={e => setMaxTurns(Number(e.target.value))}
                min={10}
                max={1000}
                className="w-40 bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-sm"
              />
            </div>
            <label className="flex items-center gap-2 cursor-pointer mt-5">
              <input
                type="checkbox"
                checked={seatSwap}
                onChange={e => setSeatSwap(e.target.checked)}
                className="accent-machi-accent"
              />
              <span className="text-sm text-machi-text-dim">{t('h2h.seatSwap')}</span>
            </label>
          </div>

          {h2h.progress ? (
            <div className="mb-2">
              <div className="flex justify-between text-sm text-machi-text-dim mb-1">
                <span>{t('h2h.running')}</span>
                <span>{h2h.progress.completed} / {h2h.progress.total}</span>
              </div>
              <div className="w-full bg-machi-bg rounded-full h-3 overflow-hidden mb-2">
                <div
                  className="bg-machi-accent h-full transition-all duration-500 rounded-full"
                  style={{ width: `${(h2h.progress.completed / h2h.progress.total) * 100}%` }}
                />
              </div>
              <button
                onClick={() => h2h.cancelMatch()}
                disabled={h2h.cancelling}
                className="w-full bg-red-500/80 text-white font-semibold py-2 rounded-lg
                           hover:bg-red-500 transition disabled:opacity-60 disabled:cursor-not-allowed text-sm"
              >
                {h2h.cancelling ? `${t('h2h.stop')}...` : t('h2h.stop')}
              </button>
              {h2h.cancelling && (
                <p className="text-center text-[10px] text-machi-text-dim/60 mt-1">
                  {language === 'en' ? 'Finishing current game...' : 'Aktuelles Spiel wird beendet...'}
                </p>
              )}
            </div>
          ) : (
            <button
              onClick={handleStart}
              disabled={h2h.loading}
              className="w-full bg-machi-accent text-machi-bg font-semibold py-2.5 rounded-lg
                         hover:brightness-110 transition disabled:opacity-50"
            >
              {h2h.loading ? '...' : t('h2h.start')}
            </button>
          )}

          {h2h.error && (
            <p className="mt-2 text-red-400 text-sm">{h2h.error}</p>
          )}
        </div>

        {/* Auto Battle Panel */}
        <div className="bg-machi-surface rounded-xl p-6 mb-6 border border-machi-border">
          <h2 className="text-lg font-semibold mb-4">{t('h2h.autoBattle')}</h2>
          <p className="text-xs text-machi-text-dim mb-3">{t('h2h.autoBattleDesc')}</p>

          {autoStatus?.running ? (
            <div>
              <div className="flex justify-between text-sm text-machi-text-dim mb-1">
                <span>{t('h2h.autoRunning')}</span>
                <span className="tabular-nums">
                  {t('h2h.game')} {autoStatus.gamesCompletedInMatch ?? 0}/{autoStatus.gamesPerMatch ?? '?'}
                  {' · '}
                  {autoStatus.endless
                    ? `${t('h2h.autoMatches')} ${autoStatus.roundsCompleted ?? 0}`
                    : `Round ${(autoStatus.roundsCompleted ?? 0) + 1}/${autoStatus.maxRounds ?? '?'}`
                  }
                </span>
              </div>
              {autoStatus.currentMatchup && (
                <div className="text-xs text-machi-text-dim mb-2">
                  {t('h2h.autoCurrent')}: <span className="font-mono text-machi-text">{autoStatus.currentMatchup}</span>
                </div>
              )}
              {/* Game progress within current match */}
              <div className="w-full bg-machi-bg rounded-full h-2 overflow-hidden mb-1">
                <div
                  className="bg-machi-purple/60 h-full transition-all duration-300 rounded-full"
                  style={{ width: `${((autoStatus.gamesCompletedInMatch ?? 0) / (autoStatus.gamesPerMatch ?? 1)) * 100}%` }}
                />
              </div>
              {/* Overall round progress (only for finite mode) */}
              {!autoStatus.endless && (
                <div className="w-full bg-machi-bg rounded-full h-3 overflow-hidden mb-1">
                  <div
                    className="bg-machi-purple h-full transition-all duration-500 rounded-full"
                    style={{ width: `${((autoStatus.roundsCompleted ?? 0) / (autoStatus.maxRounds ?? 1)) * 100}%` }}
                  />
                </div>
              )}
              {/* Session stats */}
              <div className="flex flex-wrap gap-4 text-xs text-machi-text-dim mt-2 mb-3 tabular-nums">
                <span>{t('h2h.autoMatches')}: <span className="text-machi-text">{autoStatus.roundsCompleted ?? 0}</span></span>
                <span>{t('h2h.autoTotalGames')}: <span className="text-machi-text">{autoStatus.totalGamesPlayed ?? 0}</span></span>
                <span>{t('h2h.autoElapsed')}: <span className="text-machi-text">{formatElapsed(autoStatus.elapsedMs ?? 0)}</span></span>
              </div>
              <button
                onClick={handleAutoStop}
                disabled={autoStopping}
                className="w-full bg-red-500/80 text-white font-semibold py-2 rounded-lg
                           hover:bg-red-500 transition disabled:opacity-60 disabled:cursor-not-allowed"
              >
                {autoStopping ? `${t('h2h.autoStop')}...` : t('h2h.autoStop')}
              </button>
              <p className="text-center text-[10px] text-machi-text-dim/60 mt-1">{t('h2h.autoStopHint')}</p>
            </div>
          ) : (
            <div>
              <div className="flex flex-wrap items-center gap-4 mb-3">
                <div>
                  <label className="block text-[11px] text-machi-text-dim mb-1">{t('h2h.autoGamesPerMatch')}</label>
                  <input
                    type="number"
                    value={autoGames}
                    onChange={e => setAutoGames(Number(e.target.value))}
                    min={10}
                    max={500}
                    className="w-24 bg-machi-bg border border-machi-border rounded px-2 py-1 text-sm"
                  />
                </div>
                <div>
                  <label className="block text-[11px] text-machi-text-dim mb-1">{t('h2h.autoMaxRounds')}</label>
                  <div className="flex items-center gap-2">
                    <input
                      type="number"
                      value={autoMaxRounds}
                      onChange={e => setAutoMaxRounds(Number(e.target.value))}
                      min={1}
                      max={9999}
                      disabled={autoEndless}
                      className="w-24 bg-machi-bg border border-machi-border rounded px-2 py-1 text-sm
                                 disabled:opacity-40"
                    />
                    <label className="flex items-center gap-1.5 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={autoEndless}
                        onChange={e => setAutoEndless(e.target.checked)}
                        className="accent-machi-purple"
                      />
                      <span className="text-xs text-machi-text-dim">{t('h2h.autoEndless')}</span>
                    </label>
                  </div>
                </div>
                <div>
                  <label className="block text-[11px] text-machi-text-dim mb-1">{t('h2h.autoTier')}</label>
                  <select
                    value={autoTier}
                    onChange={e => setAutoTier(e.target.value)}
                    className="w-32 bg-machi-bg border border-machi-border rounded px-2 py-1 text-sm"
                  >
                    <option value="">{t('h2h.autoAllEngines')}</option>
                    <option value="fast">fast</option>
                    <option value="balanced">balanced</option>
                    <option value="deep">deep</option>
                  </select>
                </div>
              </div>
              <button
                onClick={handleAutoStart}
                className="w-full bg-machi-purple text-white font-semibold py-2 rounded-lg
                           hover:brightness-110 transition"
              >
                {t('h2h.autoStart')}
              </button>
              {autoStatus?.error && (
                <p className="mt-2 text-red-400 text-xs">{autoStatus.error}</p>
              )}
            </div>
          )}
        </div>

        {/* Results Table */}
        <div className="bg-machi-surface rounded-xl p-6 border border-machi-border">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold">{t('h2h.results')}</h2>
            <div className="flex items-center gap-2">
              {importMessage && (
                <span className="text-xs text-machi-accent mr-2">{importMessage}</span>
              )}
              <button
                onClick={handleExport}
                disabled={h2h.results.length === 0}
                className="text-sm px-3 py-1.5 rounded-lg border border-machi-border text-machi-text-dim
                           hover:text-machi-text hover:border-machi-accent transition disabled:opacity-40"
              >
                {t('h2h.export')}
              </button>
              <button
                onClick={() => fileInputRef.current?.click()}
                disabled={h2h.loading}
                className="text-sm px-3 py-1.5 rounded-lg border border-machi-border text-machi-text-dim
                           hover:text-machi-text hover:border-machi-accent transition disabled:opacity-40"
              >
                {t('h2h.import')}
              </button>
              <input
                ref={fileInputRef}
                type="file"
                accept=".json"
                className="hidden"
                onChange={handleFileSelect}
              />
            </div>
          </div>
          {h2h.results.length === 0 ? (
            <p className="text-machi-text-dim text-sm">{t('h2h.noResults')}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-machi-text-dim border-b border-machi-border">
                    <th className="text-right py-2 px-2 text-[11px]">Elo</th>
                    <th className="text-right py-2 px-2">{t('h2h.avgEval')}</th>
                    <th className="text-right py-2 px-2">{t('h2h.winRate')}</th>
                    <th className="text-center py-2 px-2">{t('h2h.matchup')}</th>
                    <th className="text-left py-2 px-2">{t('h2h.winRate')}</th>
                    <th className="text-left py-2 px-2">{t('h2h.avgEval')}</th>
                    <th className="text-left py-2 px-2 text-[11px]">Elo</th>
                    <th className="text-center py-2 px-2">{t('h2h.gamesCol')}</th>
                    <th className="text-center py-2 px-2">{t('h2h.avgTurns')}</th>
                    <th className="text-right py-2 px-2">{t('h2h.time')}</th>
                  </tr>
                </thead>
                <tbody>
                  {[...h2h.results].reverse().map(r => {
                    const evalA = r.avgEvalTimeMsPerEngine?.[0] ?? r.avgEvalTimeMs;
                    const evalB = r.avgEvalTimeMsPerEngine?.[1] ?? r.avgEvalTimeMs;
                    const winA = r.winRates[0] * 100;
                    const winB = r.winRates[1] * 100;
                    const aWins = winA > winB;
                    const bWins = winB > winA;
                    const deltaA = r.ratingDelta?.[0] ?? 0;
                    const deltaB = r.ratingDelta?.[1] ?? 0;
                    return (
                      <tr
                        key={r.id}
                        onClick={() => h2h.selectResult(r.id)}
                        className="border-b border-machi-border/50 hover:bg-machi-bg/50 cursor-pointer transition"
                      >
                        <td className={`text-right py-2 px-2 text-[11px] tabular-nums ${deltaA > 0 ? 'text-green-400' : deltaA < 0 ? 'text-red-400' : 'text-machi-text-dim'}`}>
                          {deltaA > 0 ? '+' : ''}{deltaA}
                        </td>
                        <td className="text-right py-2 px-2 text-machi-text-dim text-xs tabular-nums">
                          {evalA.toFixed(0)}<span className="text-[10px]">ms</span>
                        </td>
                        <td className={`text-right py-2 px-2 font-semibold tabular-nums ${aWins ? 'text-machi-accent' : ''}`}>
                          {winA.toFixed(1)}%
                        </td>
                        <td className="text-center py-2 px-2">
                          <span className={`font-mono text-xs ${aWins ? 'text-machi-accent font-bold' : ''}`}>{r.engines[0]}</span>
                          <span className="text-machi-text-dim mx-1.5">vs</span>
                          <span className={`font-mono text-xs ${bWins ? 'text-machi-accent font-bold' : ''}`}>{r.engines[1]}</span>
                        </td>
                        <td className={`text-left py-2 px-2 font-semibold tabular-nums ${bWins ? 'text-machi-accent' : ''}`}>
                          {winB.toFixed(1)}%
                        </td>
                        <td className="text-left py-2 px-2 text-machi-text-dim text-xs tabular-nums">
                          {evalB.toFixed(0)}<span className="text-[10px]">ms</span>
                        </td>
                        <td className={`text-left py-2 px-2 text-[11px] tabular-nums ${deltaB > 0 ? 'text-green-400' : deltaB < 0 ? 'text-red-400' : 'text-machi-text-dim'}`}>
                          {deltaB > 0 ? '+' : ''}{deltaB}
                        </td>
                        <td className="text-center py-2 px-2 text-machi-text-dim">{r.gameCount}</td>
                        <td className="text-center py-2 px-2 text-machi-text-dim">{r.avgGameLength.toFixed(0)}</td>
                        <td className="text-right py-2 px-2 text-machi-text-dim text-xs tabular-nums">
                          {formatElapsed(r.totalTimeMs)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

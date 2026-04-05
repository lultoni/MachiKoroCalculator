import { useState, useEffect, useMemo } from 'react';
import { useH2h } from '../hooks/useH2h';
import { useLocale } from '../i18n/useLocale';
import * as api from '../api/client';
import type { EngineRegistryEntry } from '../api/types';
import { H2hMatchDetail } from './H2hMatchDetail';
import { H2hGameReplay } from './H2hGameReplay';

interface Props {
  onBack: () => void;
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

export function H2hOverview({ onBack }: Props) {
  const h2h = useH2h();
  const { t } = useLocale();
  const [engines, setEngines] = useState<EngineRegistryEntry[]>([]);
  const [engineA, setEngineA] = useState('mcts-v1-fast');
  const [engineB, setEngineB] = useState('mcts-v1-fast');
  const [games, setGames] = useState(100);
  const [fieldsA, setFieldsA] = useState<{ key: string; value: string }[]>([]);
  const [fieldsB, setFieldsB] = useState<{ key: string; value: string }[]>([]);

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

  // Game replay view
  if (h2h.selectedGame && h2h.selectedResult) {
    return (
      <H2hGameReplay
        game={h2h.selectedGame}
        engines={h2h.selectedResult.config.engineIds}
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
    h2h.startMatch(engineA, engineB, games, configA, configB);
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
                  {fieldsA.map((f, i) => (
                    <div key={f.key} className="flex items-center gap-2">
                      <label className="text-[11px] text-machi-text-dim w-32 shrink-0 text-right">{f.key}</label>
                      <input
                        type="text"
                        value={f.value}
                        onChange={e => updateFieldA(i, e.target.value)}
                        className="flex-1 bg-machi-bg border border-machi-border rounded px-2 py-1 text-xs font-mono"
                      />
                    </div>
                  ))}
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
                  {fieldsB.map((f, i) => (
                    <div key={f.key} className="flex items-center gap-2">
                      <label className="text-[11px] text-machi-text-dim w-32 shrink-0 text-right">{f.key}</label>
                      <input
                        type="text"
                        value={f.value}
                        onChange={e => updateFieldB(i, e.target.value)}
                        className="flex-1 bg-machi-bg border border-machi-border rounded px-2 py-1 text-xs font-mono"
                      />
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* Games (full width row) */}
          <div className="mb-4">
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

          {h2h.progress ? (
            <div className="mb-2">
              <div className="flex justify-between text-sm text-machi-text-dim mb-1">
                <span>{t('h2h.running')}</span>
                <span>{h2h.progress.completed} / {h2h.progress.total}</span>
              </div>
              <div className="w-full bg-machi-bg rounded-full h-3 overflow-hidden">
                <div
                  className="bg-machi-accent h-full transition-all duration-500 rounded-full"
                  style={{ width: `${(h2h.progress.completed / h2h.progress.total) * 100}%` }}
                />
              </div>
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

        {/* Results Table */}
        <div className="bg-machi-surface rounded-xl p-6 border border-machi-border">
          <h2 className="text-lg font-semibold mb-4">{t('h2h.results')}</h2>
          {h2h.results.length === 0 ? (
            <p className="text-machi-text-dim text-sm">{t('h2h.noResults')}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-machi-text-dim border-b border-machi-border">
                    <th className="text-left py-2 px-2">{t('h2h.matchup')}</th>
                    <th className="text-center py-2 px-2">{t('h2h.gamesCol')}</th>
                    <th className="text-center py-2 px-2">P1 {t('h2h.winRate')}</th>
                    <th className="text-center py-2 px-2">P2 {t('h2h.winRate')}</th>
                    <th className="text-center py-2 px-2">{t('h2h.avgTurns')}</th>
                    <th className="text-center py-2 px-2">{t('h2h.time')}</th>
                  </tr>
                </thead>
                <tbody>
                  {[...h2h.results].reverse().map(r => (
                    <tr
                      key={r.id}
                      onClick={() => h2h.selectResult(r.id)}
                      className="border-b border-machi-border/50 hover:bg-machi-bg/50 cursor-pointer transition"
                    >
                      <td className="py-2 px-2">
                        <span className="font-mono text-xs">{r.engines[0]}</span>
                        <span className="text-machi-text-dim mx-1">vs</span>
                        <span className="font-mono text-xs">{r.engines[1]}</span>
                      </td>
                      <td className="text-center py-2 px-2">{r.gameCount}</td>
                      <td className="text-center py-2 px-2 font-semibold">
                        {(r.winRates[0] * 100).toFixed(1)}%
                      </td>
                      <td className="text-center py-2 px-2 font-semibold">
                        {(r.winRates[1] * 100).toFixed(1)}%
                      </td>
                      <td className="text-center py-2 px-2">{r.avgGameLength.toFixed(0)}</td>
                      <td className="text-center py-2 px-2 text-machi-text-dim">
                        {(r.totalTimeMs / 1000).toFixed(1)}s
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

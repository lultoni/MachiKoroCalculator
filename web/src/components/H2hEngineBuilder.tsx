import { useState, useEffect, useMemo } from 'react';
import { useLocale } from '../i18n/useLocale';
import * as api from '../api/client';
import type { EngineRegistryEntry } from '../api/types';
import { ENGINE_PARAMS, ENGINE_CLASS_IDS, groupByCategory } from './engineParamSchema';
import type { ParamDef } from './engineParamSchema';

interface Props {
  onBack: () => void;
  onEnginesChanged: () => void;
}

/** Auto-generate an ID from engine class + tier. */
function suggestId(engineClass: string, tier: string): string {
  return `custom-${engineClass}-${tier}`;
}

export function H2hEngineBuilder({ onBack, onEnginesChanged }: Props) {
  const { t } = useLocale();
  const [engines, setEngines] = useState<EngineRegistryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Form state
  const [editingId, setEditingId] = useState<string | null>(null);
  const [engineClass, setEngineClass] = useState(ENGINE_CLASS_IDS[0]);
  const [id, setId] = useState(suggestId(ENGINE_CLASS_IDS[0], 'fast'));
  const [description, setDescription] = useState('');
  const [tier, setTier] = useState('fast');
  const [paramValues, setParamValues] = useState<Record<string, string>>({});

  const loadEngines = () => {
    api.getEngines().then(list => {
      setEngines(list);
      setLoading(false);
    }).catch(() => setLoading(false));
  };

  useEffect(() => { loadEngines(); }, []);

  const customEngines = useMemo(() => engines.filter(e => e.custom), [engines]);

  // Current schema based on selected engine class
  const schema = ENGINE_PARAMS[engineClass] ?? [];
  const groupedSchema = useMemo(() => groupByCategory(schema), [schema]);

  // Initialize param values with defaults when engine class changes
  useEffect(() => {
    if (editingId) return; // Don't reset when editing
    const defaults: Record<string, string> = {};
    for (const p of schema) {
      if (p.default != null) defaults[p.key] = p.default;
    }
    setParamValues(defaults);
  }, [engineClass, schema, editingId]);

  // Auto-suggest ID when class/tier changes (only when not editing)
  useEffect(() => {
    if (!editingId) {
      setId(suggestId(engineClass, tier));
    }
  }, [engineClass, tier, editingId]);

  const resetForm = () => {
    setEditingId(null);
    setEngineClass(ENGINE_CLASS_IDS[0]);
    setId(suggestId(ENGINE_CLASS_IDS[0], 'fast'));
    setDescription('');
    setTier('fast');
    setParamValues({});
    setError(null);
  };

  const handleEdit = (entry: EngineRegistryEntry) => {
    setEditingId(entry.id);
    setEngineClass(entry.engineClass);
    setId(entry.id);
    setDescription(entry.description);
    setTier(entry.tier);

    // Populate param values from entry config
    const vals: Record<string, string> = {};
    const cfg = entry.config;
    if (cfg) {
      vals['iterations'] = String(cfg.iterations ?? 0);
      vals['timeBudgetMs'] = String(cfg.timeBudgetMs ?? 0);
      vals['riskToleranceWeight'] = String(cfg.riskToleranceWeight ?? 0);
      if (cfg.extra) {
        for (const [k, v] of Object.entries(cfg.extra)) {
          vals[k] = v;
        }
      }
    }
    setParamValues(vals);
    setError(null);
    setSuccess(null);
  };

  const handleSave = async () => {
    if (!id.trim()) {
      setError(t('builder.errorId'));
      return;
    }

    // Check for duplicate with built-in entries (only on create, not update)
    if (!editingId) {
      const existing = engines.find(e => e.id === id.trim());
      if (existing && !existing.custom) {
        setError(t('builder.duplicateId'));
        return;
      }
    }

    setSaving(true);
    setError(null);
    try {
      // Build config map: all param values as strings
      const config: Record<string, string> = {};
      for (const p of schema) {
        const val = paramValues[p.key];
        if (val != null && val !== '') {
          config[p.key] = val;
        }
      }

      await api.saveCustomEngine({
        id: id.trim(),
        engineClass,
        description: description.trim(),
        tier,
        config,
      });

      setSuccess(editingId ? t('builder.updated') : t('builder.saved'));
      setTimeout(() => setSuccess(null), 3000);
      loadEngines();
      onEnginesChanged();
      if (!editingId) resetForm();
    } catch (e: unknown) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (entryId: string) => {
    if (!confirm(t('builder.confirmDelete').replace('{id}', entryId))) return;
    try {
      await api.deleteCustomEngine(entryId);
      setSuccess(t('builder.deleted'));
      setTimeout(() => setSuccess(null), 3000);
      loadEngines();
      onEnginesChanged();
      if (editingId === entryId) resetForm();
    } catch (e: unknown) {
      setError((e as Error).message);
    }
  };

  const renderParamInput = (p: ParamDef) => {
    const val = paramValues[p.key] ?? p.default ?? '';

    if (p.type === 'select' && p.options) {
      return (
        <select
          value={val}
          onChange={e => setParamValues(prev => ({ ...prev, [p.key]: e.target.value }))}
          className="flex-1 bg-machi-bg border border-machi-border rounded px-2 py-1 text-xs font-mono"
        >
          {p.options.map(o => <option key={o} value={o}>{o}</option>)}
        </select>
      );
    }

    return (
      <div className="flex-1 flex items-center gap-1.5">
        <input
          type="number"
          value={val}
          onChange={e => setParamValues(prev => ({ ...prev, [p.key]: e.target.value }))}
          min={p.min}
          max={p.max}
          step={p.step}
          className="flex-1 bg-machi-bg border border-machi-border rounded px-2 py-1 text-xs font-mono tabular-nums"
        />
        {p.min != null && p.max != null && (
          <span className="text-[10px] text-machi-text-dim/50 whitespace-nowrap">
            [{p.min}–{p.max}]
          </span>
        )}
      </div>
    );
  };

  if (loading) {
    return (
      <div className="min-h-screen bg-machi-bg text-machi-text p-6">
        <p className="text-machi-text-dim">{t('sweep.loading')}</p>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-machi-bg text-machi-text p-6">
      <div className="max-w-4xl mx-auto">
        {/* Header */}
        <div className="flex items-center gap-4 mb-6">
          <button onClick={onBack} className="text-machi-text-dim hover:text-machi-text transition">
            ← {t('btn.back')}
          </button>
          <h1 className="text-2xl font-bold">{t('builder.title')}</h1>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Create/Edit Form */}
          <div className="bg-machi-surface rounded-xl p-6 border border-machi-border">
            <h2 className="text-lg font-semibold mb-4">
              {editingId ? `${t('builder.update')}: ${editingId}` : t('builder.create')}
            </h2>

            {/* Engine Class */}
            <div className="mb-3">
              <label className="block text-sm text-machi-text-dim mb-1">{t('builder.engineClass')}</label>
              <select
                value={engineClass}
                onChange={e => setEngineClass(e.target.value)}
                disabled={!!editingId}
                className="w-full bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-sm font-mono disabled:opacity-50"
              >
                {ENGINE_CLASS_IDS.map(cls => (
                  <option key={cls} value={cls}>{cls}</option>
                ))}
              </select>
            </div>

            {/* ID */}
            <div className="mb-3">
              <label className="block text-sm text-machi-text-dim mb-1">{t('builder.id')}</label>
              <input
                type="text"
                value={id}
                onChange={e => setId(e.target.value)}
                disabled={!!editingId}
                placeholder="e.g. custom-mcts-v1-fast"
                className="w-full bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-sm font-mono disabled:opacity-50"
              />
              <p className="text-[10px] text-machi-text-dim/50 mt-0.5">{t('builder.idHint')}</p>
            </div>

            {/* Description */}
            <div className="mb-3">
              <label className="block text-sm text-machi-text-dim mb-1">{t('builder.description')}</label>
              <input
                type="text"
                value={description}
                onChange={e => setDescription(e.target.value)}
                placeholder="Short description of this config"
                className="w-full bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-sm"
              />
            </div>

            {/* Tier */}
            <div className="mb-4">
              <label className="block text-sm text-machi-text-dim mb-1">{t('builder.tier')}</label>
              <select
                value={tier}
                onChange={e => setTier(e.target.value)}
                className="w-full bg-machi-bg border border-machi-border rounded-lg px-3 py-2 text-sm"
              >
                <option value="fast">fast</option>
                <option value="balanced">balanced</option>
                <option value="deep">deep</option>
              </select>
            </div>

            {/* Parameters (grouped by category) */}
            <div className="mb-4">
              <label className="block text-sm text-machi-text-dim mb-2">{t('builder.params')}</label>
              <div className="space-y-3 max-h-[50vh] overflow-y-auto pr-1">
                {groupedSchema.map(([category, params]) => (
                  <div key={category}>
                    <div className="text-[10px] uppercase tracking-wider text-machi-text-dim/60 mb-1.5 mt-1">
                      {category}
                    </div>
                    <div className="space-y-1.5">
                      {params.map(p => (
                        <div key={p.key} className="flex items-center gap-2">
                          <label
                            className="text-[11px] text-machi-text-dim w-36 shrink-0 text-right cursor-help"
                            title={p.description}
                          >
                            {p.key}
                          </label>
                          {renderParamInput(p)}
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* Actions */}
            <div className="flex gap-2">
              <button
                onClick={handleSave}
                disabled={saving}
                className="flex-1 bg-machi-accent text-machi-bg font-semibold py-2.5 rounded-lg
                           hover:brightness-110 transition disabled:opacity-50"
              >
                {saving ? '...' : editingId ? t('builder.update') : t('builder.save')}
              </button>
              {editingId && (
                <button
                  onClick={resetForm}
                  className="px-4 py-2.5 rounded-lg border border-machi-border text-machi-text-dim
                             hover:text-machi-text transition"
                >
                  {t('builder.cancel')}
                </button>
              )}
            </div>

            {error && <p className="mt-2 text-red-400 text-sm">{error}</p>}
            {success && <p className="mt-2 text-green-400 text-sm">{success}</p>}
          </div>

          {/* Custom Engines List */}
          <div className="bg-machi-surface rounded-xl p-6 border border-machi-border">
            <h2 className="text-lg font-semibold mb-4">{t('builder.customEngines')}</h2>

            {customEngines.length === 0 ? (
              <p className="text-machi-text-dim text-sm">{t('builder.noCustom')}</p>
            ) : (
              <div className="space-y-3">
                {customEngines.map(entry => (
                  <div
                    key={entry.id}
                    className={`p-3 rounded-lg border transition ${
                      editingId === entry.id
                        ? 'border-machi-accent bg-machi-accent/5'
                        : 'border-machi-border bg-machi-bg/30 hover:border-machi-border/80'
                    }`}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="flex-1 min-w-0">
                        <div className="font-mono text-sm font-semibold truncate">{entry.id}</div>
                        <div className="text-[11px] text-machi-text-dim mt-0.5">
                          {entry.engineClass} · {entry.tier}
                          {entry.description && ` · ${entry.description}`}
                        </div>
                        {/* Show key config values */}
                        <div className="flex flex-wrap gap-x-3 gap-y-0.5 mt-1">
                          {entry.config.iterations > 0 && (
                            <span className="text-[10px] text-machi-text-dim/60">
                              iter={entry.config.iterations}
                            </span>
                          )}
                          {entry.config.timeBudgetMs > 0 && (
                            <span className="text-[10px] text-machi-text-dim/60">
                              time={entry.config.timeBudgetMs}ms
                            </span>
                          )}
                          {entry.config.extra && Object.entries(entry.config.extra).slice(0, 3).map(([k, v]) => (
                            <span key={k} className="text-[10px] text-machi-text-dim/60">
                              {k}={v}
                            </span>
                          ))}
                          {entry.config.extra && Object.keys(entry.config.extra).length > 3 && (
                            <span className="text-[10px] text-machi-text-dim/40">
                              +{Object.keys(entry.config.extra).length - 3} more
                            </span>
                          )}
                        </div>
                      </div>
                      <div className="flex gap-1 shrink-0">
                        <button
                          onClick={() => handleEdit(entry)}
                          className="text-xs px-2 py-1 rounded border border-machi-border text-machi-text-dim
                                     hover:text-machi-text hover:border-machi-accent transition"
                        >
                          {t('builder.edit')}
                        </button>
                        <button
                          onClick={() => handleDelete(entry.id)}
                          className="text-xs px-2 py-1 rounded border border-machi-border text-red-400/70
                                     hover:text-red-400 hover:border-red-400/50 transition"
                        >
                          {t('builder.delete')}
                        </button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

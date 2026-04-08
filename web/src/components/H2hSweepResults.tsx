import { useState, useEffect, useMemo } from 'react';
import { useLocale } from '../i18n/useLocale';
import * as api from '../api/client';
import type { SweepRun, SweepTrial } from '../api/types';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  BarChart, Bar, Cell,
} from 'recharts';

interface Props {
  onBack: () => void;
}

/** Group key = creatorEngine + opponent (same logic as --resume). */
function groupKey(run: SweepRun): string {
  return `${run.creatorEngine}|||${run.opponent}`;
}

interface RunGroup {
  key: string;
  creatorEngine: string;
  opponent: string;
  runs: SweepRun[];
  trials: SweepTrial[];
  gamesPerTrial: number;
}

function buildGroups(runs: SweepRun[]): RunGroup[] {
  const map = new Map<string, RunGroup>();
  for (const run of runs) {
    const k = groupKey(run);
    let g = map.get(k);
    if (!g) {
      g = {
        key: k,
        creatorEngine: run.creatorEngine,
        opponent: run.opponent,
        runs: [],
        trials: [],
        gamesPerTrial: run.gamesPerTrial,
      };
      map.set(k, g);
    }
    g.runs.push(run);
    g.trials.push(...run.trials);
  }
  // Sort groups by newest run date descending
  return Array.from(map.values()).sort((a, b) => {
    const dateA = a.runs[a.runs.length - 1].date;
    const dateB = b.runs[b.runs.length - 1].date;
    return dateB.localeCompare(dateA);
  });
}

/** Pearson correlation coefficient between two arrays. */
function pearsonR(xs: number[], ys: number[]): number {
  const n = xs.length;
  if (n < 3) return 0;
  const mx = xs.reduce((s, x) => s + x, 0) / n;
  const my = ys.reduce((s, y) => s + y, 0) / n;
  let num = 0, dx2 = 0, dy2 = 0;
  for (let i = 0; i < n; i++) {
    const dx = xs[i] - mx;
    const dy = ys[i] - my;
    num += dx * dy;
    dx2 += dx * dx;
    dy2 += dy * dy;
  }
  const denom = Math.sqrt(dx2 * dy2);
  return denom === 0 ? 0 : num / denom;
}

// Colors for top-N trial lines
const LINE_COLORS = [
  '#f59e0b', '#3b82f6', '#10b981', '#ef4444', '#8b5cf6',
  '#ec4899', '#14b8a6', '#f97316', '#06b6d4', '#84cc16',
  '#e879f9', '#fbbf24', '#22d3ee', '#a78bfa', '#fb7185',
  '#34d399', '#facc15', '#2dd4bf', '#c084fc', '#f87171',
];

// ─── Parameter definitions (matching SweepMain.PARAMS) ─────────────────
const PARAM_DEFS: { name: string; min: number; max: number; group: string }[] = [
  { name: 'wIncome',          min: 0.0,  max: 8.0,   group: 'Base weights' },
  { name: 'wRisk',            min: 0.0,  max: 6.0,   group: 'Base weights' },
  { name: 'wCoverage',        min: 0.0,  max: 6.0,   group: 'Base weights' },
  { name: 'wTempo',           min: 0.0,  max: 6.0,   group: 'Base weights' },
  { name: 'wWinProb',         min: 0.0,  max: 10.0,  group: 'Base weights' },
  { name: 'wLandmark',        min: 0.0,  max: 8.0,   group: 'Base weights' },
  { name: 'wUrgency',         min: 0.0,  max: 6.0,   group: 'Base weights' },
  { name: 'wRoi',             min: 0.0,  max: 6.0,   group: 'Base weights' },
  { name: 'sitLandmark',      min: 0.0,  max: 1.0,   group: 'Situation' },
  { name: 'sitIncome',        min: 0.0,  max: 1.0,   group: 'Situation' },
  { name: 'sitCoins',         min: 0.0,  max: 1.0,   group: 'Situation' },
  { name: 'sitTempo',         min: 0.0,  max: 1.0,   group: 'Situation' },
  { name: 'targetEvPerRound', min: 1.0,  max: 15.0,  group: 'Thresholds' },
  { name: 'maxETW',           min: 10.0, max: 100.0,  group: 'Thresholds' },
  { name: 'sigmoidK',         min: 0.5,  max: 20.0,  group: 'Sigmoid & gravity' },
  { name: 'sprintHorizon',    min: 2.0,  max: 25.0,  group: 'Sigmoid & gravity' },
  { name: 'sprintSharpness',  min: 0.1,  max: 5.0,   group: 'Sigmoid & gravity' },
  { name: 'threatHorizon',    min: 2.0,  max: 25.0,  group: 'Sigmoid & gravity' },
  { name: 'threatSharpness',  min: 0.1,  max: 5.0,   group: 'Sigmoid & gravity' },
  { name: 'wBurohausSwap',    min: 0.0,  max: 8.0,   group: 'Bürohaus' },
];

function formatMs(ms: number): string {
  const sec = Math.floor(ms / 1000);
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

export function H2hSweepResults({ onBack }: Props) {
  const { t } = useLocale();
  const [runs, setRuns] = useState<SweepRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedGroupIdx, setSelectedGroupIdx] = useState(0);
  const [topN, setTopN] = useState(10);

  useEffect(() => {
    api.sweepResults()
      .then(r => setRuns(r))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const groups = useMemo(() => buildGroups(runs), [runs]);
  const group = groups[selectedGroupIdx] ?? null;

  // ─── Derived data ────────────────────────────────────────────────

  // Convergence: best WR so far at each trial index
  const convergenceData = useMemo(() => {
    if (!group) return [];
    let best = 0;
    return group.trials.map((trial, i) => {
      best = Math.max(best, trial.winRate);
      return { trial: i, winRate: trial.winRate, bestSoFar: best };
    });
  }, [group]);

  // Parameter importance: abs(Pearson r) between each param and winRate
  const importanceData = useMemo(() => {
    if (!group || group.trials.length < 3) return [];
    const winRates = group.trials.map(t => t.winRate);
    return PARAM_DEFS
      .map(pd => {
        const values = group.trials.map(t => t.params[pd.name] ?? 0);
        return { name: pd.name, group: pd.group, importance: Math.abs(pearsonR(values, winRates)) };
      })
      .sort((a, b) => b.importance - a.importance);
  }, [group]);

  // Top N trials by win rate
  const topTrials = useMemo(() => {
    if (!group) return [];
    return [...group.trials]
      .sort((a, b) => b.winRate - a.winRate)
      .slice(0, topN);
  }, [group, topN]);

  // Parallel coordinates data: normalize each param to [0,1]
  const parallelData = useMemo(() => {
    return topTrials.map((trial, i) => {
      const row: Record<string, number> = { _rank: i + 1, _wr: trial.winRate, _index: trial.index };
      for (const pd of PARAM_DEFS) {
        const raw = trial.params[pd.name] ?? 0;
        row[pd.name] = (raw - pd.min) / (pd.max - pd.min);
      }
      return row;
    });
  }, [topTrials]);

  // ─── Render ──────────────────────────────────────────────────────

  return (
    <div className="min-h-screen bg-machi-bg text-machi-text p-6">
      <div className="max-w-6xl mx-auto">
        {/* Header */}
        <div className="flex items-center gap-4 mb-6">
          <button
            onClick={onBack}
            className="text-machi-text-dim hover:text-machi-text transition"
          >
            ← {t('btn.back')}
          </button>
          <h1 className="text-2xl font-bold">{t('sweep.title')}</h1>
        </div>

        {loading ? (
          <p className="text-machi-text-dim text-sm">{t('sweep.loading')}</p>
        ) : groups.length === 0 ? (
          <p className="text-machi-text-dim text-sm">{t('sweep.noData')}</p>
        ) : (
          <>
            {/* Group selector + info */}
            <div className="bg-machi-surface rounded-xl p-4 mb-6 border border-machi-border">
              <div className="flex flex-wrap items-center gap-4">
                <label className="text-sm text-machi-text-dim">{t('sweep.runGroup')}</label>
                <select
                  value={selectedGroupIdx}
                  onChange={e => setSelectedGroupIdx(Number(e.target.value))}
                  className="bg-machi-bg border border-machi-border rounded-lg px-3 py-1.5 text-sm flex-1 min-w-[200px]"
                >
                  {groups.map((g, i) => (
                    <option key={g.key} value={i}>
                      {g.creatorEngine} vs {g.opponent} ({g.trials.length} trials)
                    </option>
                  ))}
                </select>
              </div>
              {group && (
                <div className="flex flex-wrap gap-x-6 gap-y-1 mt-3 text-xs text-machi-text-dim">
                  <span>{t('sweep.engine')}: <b className="text-machi-text">{group.creatorEngine}</b></span>
                  <span>{t('sweep.opponent')}: <b className="text-machi-text">{group.opponent}</b></span>
                  <span>{t('sweep.trials')}: <b className="text-machi-text">{group.trials.length}</b></span>
                  <span>{t('sweep.gamesPerTrial')}: <b className="text-machi-text">{group.gamesPerTrial}</b></span>
                  <span>{t('sweep.bestWr')}: <b className="text-machi-accent">{(Math.max(...group.trials.map(t => t.winRate)) * 100).toFixed(1)}%</b></span>
                  <span>{t('sweep.totalTime')}: <b className="text-machi-text">{formatMs(group.runs.reduce((s, r) => s + r.totalTimeMs, 0))}</b></span>
                  <span>{t('sweep.runDate')}: <b className="text-machi-text">{group.runs.map(r => new Date(r.date).toLocaleDateString()).join(', ')}</b></span>
                </div>
              )}
            </div>

            {group && group.trials.length >= 2 && (
              <>
                {/* 1) Convergence Plot */}
                <ChartCard title={t('sweep.convergence')} desc={t('sweep.convergenceDesc')}>
                  <ResponsiveContainer width="100%" height={300}>
                    <LineChart data={convergenceData} margin={{ top: 5, right: 20, bottom: 5, left: 10 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.07)" />
                      <XAxis
                        dataKey="trial"
                        label={{ value: t('sweep.trialIndex'), position: 'insideBottom', offset: -2, fill: '#888', fontSize: 12 }}
                        stroke="#555"
                        tick={{ fill: '#888', fontSize: 11 }}
                      />
                      <YAxis
                        domain={[0, 1]}
                        tickFormatter={(v: number) => `${(v * 100).toFixed(0)}%`}
                        stroke="#555"
                        tick={{ fill: '#888', fontSize: 11 }}
                      />
                      <Tooltip
                        contentStyle={{ backgroundColor: '#1e1e2e', border: '1px solid #333', borderRadius: 8 }}
                        labelStyle={{ color: '#888' }}
                        formatter={((value: number, name: string) => [
                          `${(value * 100).toFixed(1)}%`,
                          name === 'bestSoFar' ? t('sweep.bestSoFar') : t('sweep.winRate'),
                        ]) as never}
                        labelFormatter={((label: number) => `${t('sweep.trialIndex')} ${label}`) as never}
                      />
                      <Line type="monotone" dataKey="winRate" stroke="#555" strokeWidth={1} dot={{ r: 1.5, fill: '#666' }} name={t('sweep.winRate')} />
                      <Line type="monotone" dataKey="bestSoFar" stroke="#f59e0b" strokeWidth={2} dot={false} name={t('sweep.bestSoFar')} />
                    </LineChart>
                  </ResponsiveContainer>
                </ChartCard>

                {/* 2) Parameter Importance */}
                {importanceData.length > 0 && (
                  <ChartCard title={t('sweep.importance')} desc={t('sweep.importanceDesc')}>
                    <div className="flex flex-wrap gap-3 mb-3 text-xs text-machi-text-dim">
                      <span className="flex items-center gap-1.5">
                        <span className="inline-block w-3 h-3 rounded-sm" style={{ backgroundColor: '#f59e0b' }} />
                        {t('sweep.importanceStrong')}
                      </span>
                      <span className="flex items-center gap-1.5">
                        <span className="inline-block w-3 h-3 rounded-sm" style={{ backgroundColor: '#6b7280' }} />
                        {t('sweep.importanceMod')}
                      </span>
                      <span className="flex items-center gap-1.5">
                        <span className="inline-block w-3 h-3 rounded-sm" style={{ backgroundColor: '#374151' }} />
                        {t('sweep.importanceWeak')}
                      </span>
                    </div>
                    <ResponsiveContainer width="100%" height={Math.max(300, importanceData.length * 28)}>
                      <BarChart data={importanceData} layout="vertical" margin={{ top: 5, right: 20, bottom: 5, left: 120 }}>
                        <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.07)" />
                        <XAxis
                          type="number"
                          domain={[0, 1]}
                          tickFormatter={(v: number) => v.toFixed(2)}
                          stroke="#555"
                          tick={{ fill: '#888', fontSize: 11 }}
                        />
                        <YAxis
                          dataKey="name"
                          type="category"
                          width={110}
                          stroke="#555"
                          tick={{ fill: '#aaa', fontSize: 11, fontFamily: 'monospace' }}
                        />
                        <Tooltip
                          contentStyle={{ backgroundColor: '#1e1e2e', border: '1px solid #333', borderRadius: 8, color: '#ddd' }}
                          labelStyle={{ color: '#aaa' }}
                          formatter={((value: number) => [`|r| = ${value.toFixed(3)}`, 'Importance']) as never}
                        />
                        <Bar dataKey="importance" radius={[0, 4, 4, 0]}>
                          {importanceData.map((d, i) => (
                            <Cell key={i} fill={d.importance > 0.3 ? '#f59e0b' : d.importance > 0.15 ? '#6b7280' : '#374151'} />
                          ))}
                        </Bar>
                      </BarChart>
                    </ResponsiveContainer>
                  </ChartCard>
                )}

                {/* Top-N Slider */}
                <div className="bg-machi-surface rounded-xl p-4 mb-4 border border-machi-border flex items-center gap-4">
                  <label className="text-sm text-machi-text-dim whitespace-nowrap">{t('sweep.topN')}: <b className="text-machi-text">{topN}</b></label>
                  <input
                    type="range"
                    min={1}
                    max={Math.min(20, group.trials.length)}
                    value={topN}
                    onChange={e => setTopN(Number(e.target.value))}
                    className="flex-1 accent-machi-accent"
                  />
                  {/* Legend */}
                  <div className="flex flex-wrap gap-2 text-xs">
                    {topTrials.slice(0, 5).map((trial, i) => (
                      <span key={i} className="flex items-center gap-1">
                        <span className="inline-block w-3 h-3 rounded-sm" style={{ backgroundColor: LINE_COLORS[i % LINE_COLORS.length] }} />
                        #{trial.index} ({(trial.winRate * 100).toFixed(1)}%)
                      </span>
                    ))}
                    {topTrials.length > 5 && <span className="text-machi-text-dim">+{topTrials.length - 5} more</span>}
                  </div>
                </div>

                {/* 3) Parallel Coordinates */}
                <ChartCard title={t('sweep.parallelCoords')} desc={t('sweep.parallelCoordsDesc')}>
                  <ParallelCoordinatesChart data={parallelData} />
                </ChartCard>

                {/* 4) Parameter Ranges */}
                <ChartCard title={t('sweep.paramRanges')} desc={t('sweep.paramRangesDesc')}>
                  <ParamRangesChart trials={group.trials} topTrials={topTrials} />
                </ChartCard>
              </>
            )}
          </>
        )}
      </div>
    </div>
  );
}

// ─── Reusable chart wrapper ──────────────────────────────────────────

function ChartCard({ title, desc, children }: { title: string; desc: string; children: React.ReactNode }) {
  return (
    <div className="bg-machi-surface rounded-xl p-6 mb-6 border border-machi-border">
      <h3 className="text-lg font-semibold mb-1">{title}</h3>
      <p className="text-xs text-machi-text-dim mb-4">{desc}</p>
      {children}
    </div>
  );
}

// ─── Parallel Coordinates (custom SVG) ────────────────────────────────

function ParallelCoordinatesChart({ data }: { data: Record<string, number>[] }) {
  if (data.length === 0) return null;

  const params = PARAM_DEFS.map(p => p.name);
  const width = 900;
  const height = 350;
  const padLeft = 30;
  const padRight = 30;
  const padTop = 30;
  const padBottom = 50;
  const axisSpacing = (width - padLeft - padRight) / (params.length - 1);

  return (
    <div className="overflow-x-auto">
      <svg viewBox={`0 0 ${width} ${height}`} className="w-full min-w-[700px]" style={{ maxHeight: 400 }}>
        {/* Axes */}
        {params.map((p, i) => {
          const x = padLeft + i * axisSpacing;
          return (
            <g key={p}>
              <line x1={x} y1={padTop} x2={x} y2={height - padBottom} stroke="#444" strokeWidth={1} />
              <text
                x={x}
                y={height - padBottom + 14}
                textAnchor="middle"
                fill="#888"
                fontSize={8}
                transform={`rotate(45, ${x}, ${height - padBottom + 14})`}
              >
                {p}
              </text>
              <text x={x} y={padTop - 8} textAnchor="middle" fill="#555" fontSize={7}>1.0</text>
              <text x={x} y={height - padBottom + 10} textAnchor="middle" fill="#555" fontSize={7}>0.0</text>
            </g>
          );
        })}

        {/* Lines for each trial (reversed so rank-1 is on top) */}
        {[...data].reverse().map((row, revIdx) => {
          const idx = data.length - 1 - revIdx;
          const points = params.map((p, i) => {
            const x = padLeft + i * axisSpacing;
            const y = padTop + (1 - (row[p] ?? 0)) * (height - padTop - padBottom);
            return `${x},${y}`;
          }).join(' ');
          return (
            <polyline
              key={idx}
              points={points}
              fill="none"
              stroke={LINE_COLORS[idx % LINE_COLORS.length]}
              strokeWidth={idx === 0 ? 2.5 : 1.5}
              strokeOpacity={idx < 3 ? 0.9 : 0.5}
            />
          );
        })}
      </svg>
    </div>
  );
}

// ─── Parameter Ranges Chart ───────────────────────────────────────────

function ParamRangesChart({ trials, topTrials }: { trials: SweepTrial[]; topTrials: SweepTrial[] }) {
  const { t } = useLocale();
  if (trials.length === 0) return null;

  // Group params by category
  const groups: { label: string; params: typeof PARAM_DEFS }[] = [];
  let currentGroup = '';
  for (const pd of PARAM_DEFS) {
    if (pd.group !== currentGroup) {
      currentGroup = pd.group;
      groups.push({ label: pd.group, params: [] });
    }
    groups[groups.length - 1].params.push(pd);
  }

  return (
    <div>
      {/* Legend */}
      <div className="flex flex-wrap gap-4 mb-4 text-xs text-machi-text-dim">
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-6 h-3 rounded-sm bg-machi-border/30 border border-machi-border/20" />
          {t('sweep.observedRange')}
        </span>
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-3 h-3 rounded-full" style={{ backgroundColor: LINE_COLORS[0] }} />
          {t('sweep.topTrialValues')}
        </span>
      </div>

      <div className="space-y-4">
        {groups.map(grp => (
          <div key={grp.label}>
            <h4 className="text-xs font-semibold text-machi-text-dim uppercase tracking-wider mb-2 mt-1">{grp.label}</h4>
            <div className="space-y-1.5">
              {grp.params.map(pd => {
                const allValues = trials.map(t => t.params[pd.name] ?? 0);
                const observedMin = Math.min(...allValues);
                const observedMax = Math.max(...allValues);
                const range = pd.max - pd.min;

                return (
                  <div key={pd.name} className="flex items-center gap-2">
                    <span className="w-[130px] text-right text-xs font-mono text-machi-text-dim shrink-0 truncate" title={`${pd.name} [${pd.min} – ${pd.max}]`}>
                      {pd.name}
                    </span>
                    <div className="flex-1 relative h-5 bg-machi-bg rounded-full overflow-hidden border border-machi-border/30">
                      {/* Observed range bar */}
                      <div
                        className="absolute top-0 h-full rounded-full"
                        style={{
                          left: `${((observedMin - pd.min) / range) * 100}%`,
                          width: `${Math.max(1, ((observedMax - observedMin) / range) * 100)}%`,
                          backgroundColor: 'rgba(148, 163, 184, 0.2)',
                        }}
                        title={`Observed: ${observedMin.toFixed(2)} – ${observedMax.toFixed(2)}`}
                      />
                      {/* Top trial dots */}
                      {topTrials.map((trial, i) => {
                        const val = trial.params[pd.name] ?? 0;
                        const pct = ((val - pd.min) / range) * 100;
                        return (
                          <div
                            key={i}
                            className="absolute top-1/2 -translate-y-1/2 w-2.5 h-2.5 rounded-full border border-black/30"
                            style={{
                              left: `calc(${pct}% - 5px)`,
                              backgroundColor: LINE_COLORS[i % LINE_COLORS.length],
                              opacity: i < 3 ? 1 : 0.6,
                              zIndex: topTrials.length - i,
                            }}
                            title={`#${trial.index}: ${val.toFixed(3)} (WR: ${(trial.winRate * 100).toFixed(1)}%)`}
                          />
                        );
                      })}
                    </div>
                    <span className="w-[70px] text-right text-[10px] text-machi-text-dim shrink-0">
                      {pd.min}–{pd.max}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

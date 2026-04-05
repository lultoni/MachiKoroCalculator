/** Ranked list — engine-adaptive sortable table with color-coded metrics. */

import { useState, useMemo } from 'react';
import type { RankedOption, MetricRange, ProjectDef } from '../api/types';
import { COLUMNS, formatMetric, type ColumnDef } from '../utils/columns';
import { metricBgStyle } from '../utils/metricColor';
import { ExplanationFactors } from './ExplanationFactors';

interface Props {
  options: RankedOption[];
  metricRanges: Record<string, MetricRange> | undefined;
  projects: { byId: (id: string) => ProjectDef | undefined };
  language: 'de' | 'en';
  onHover: (card: { projectId: string; cost: number } | null) => void;
  onSelect: (projectId: string | null) => void;
  selectedId: string | null;
}

export function RankedList({ options, metricRanges, projects, language, onHover, onSelect, selectedId }: Props) {
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [sortAsc, setSortAsc] = useState(false);
  const [expandedRow, setExpandedRow] = useState<string | null>(null);

  // Filter columns to only those present in the first option's metrics
  const visibleColumns = useMemo(() => {
    if (options.length === 0) return COLUMNS.filter(c => c.key === 'rank' || c.key === 'projectId');
    const firstMetrics = options[0].metrics;
    return COLUMNS.filter(c => {
      if (c.key === 'rank' || c.key === 'projectId') return true;
      if (c.key === 'score') return true;
      if (c.key === 'cost') return true;
      return firstMetrics != null && c.key in firstMetrics;
    });
  }, [options]);

  // Extend metricRanges with client-computed ranges for built-in fields
  const extendedRanges = useMemo(() => {
    const ranges: Record<string, { min: string; max: string }> = { ...metricRanges };
    if (options.length > 0) {
      // winRate range (from score field)
      const scores = options.map(o => o.score);
      ranges['winRate'] = { min: String(Math.min(...scores)), max: String(Math.max(...scores)) };
      // cost range
      const costs = options
        .filter(o => o.projectId !== '_wait_')
        .map(o => projects.byId(o.projectId)?.cost ?? 0);
      if (costs.length > 0) {
        ranges['cost'] = { min: String(Math.min(...costs)), max: String(Math.max(...costs)) };
      }
    }
    return ranges;
  }, [options, metricRanges, projects]);

  // Deduplicate _wait_ entries (backend may return multiple)
  const dedupedOptions = useMemo(() => {
    let seenWait = false;
    return options.filter(o => {
      if (o.projectId === '_wait_') {
        if (seenWait) return false;
        seenWait = true;
      }
      return true;
    });
  }, [options]);

  // Sort
  const sorted = useMemo(() => {
    const ranked = dedupedOptions.map((o, i) => ({ ...o, rank: i + 1 }));
    if (!sortKey) return ranked;
    return [...ranked].sort((a, b) => {
      const av = getValue(a, sortKey);
      const bv = getValue(b, sortKey);
      const diff = av - bv;
      return sortAsc ? diff : -diff;
    });
  }, [dedupedOptions, sortKey, sortAsc]);

  const handleSort = (col: ColumnDef) => {
    if (!col.sortable) return;
    if (sortKey === col.key) {
      setSortAsc(!sortAsc);
    } else {
      setSortKey(col.key);
      setSortAsc(false);
    }
  };

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-machi-border">
            {visibleColumns.map(col => (
              <th
                key={col.key}
                className={`px-2 py-1.5 text-left text-xs text-machi-text-dim font-medium ${
                  col.sortable ? 'cursor-pointer hover:text-machi-text transition-colors select-none' : ''
                }`}
                onClick={() => handleSort(col)}
                title={col.tooltip}
              >
                {col.label}
                {sortKey === col.key && (
                  <span className="ml-0.5">{sortAsc ? '↑' : '↓'}</span>
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {sorted.map((opt, idx) => {
            const proj = projects.byId(opt.projectId);
            const isWait = opt.projectId === '_wait_';
            const isSelected = opt.projectId === selectedId;
            const isExpanded = opt.projectId === expandedRow;
            return (
              <tr
                key={isWait ? `_wait_${idx}` : opt.projectId}
                className={`border-b border-machi-border/50 transition-colors cursor-pointer ${
                  isSelected ? 'bg-machi-accent/10' : 'hover:bg-machi-surface/50'
                }`}
                onClick={() => {
                  onSelect(isSelected ? null : opt.projectId);
                  setExpandedRow(isExpanded ? null : opt.projectId);
                }}
                onMouseEnter={() => !isWait && proj && onHover({ projectId: opt.projectId, cost: proj.cost })}
                onMouseLeave={() => !isWait && onHover(null)}
              >
                {visibleColumns.map(col => {
                  const raw = getCellValue(opt, col.key, proj, language);
                  const formatted = formatMetric(raw, col.format);
                  const rk = col.rangeKey ?? col.key;
                  const cellStyle = col.colorGradient && extendedRanges && rk in (extendedRanges ?? {})
                    ? metricBgStyle(
                        parseFloat(String(raw)),
                        parseFloat(extendedRanges[rk].min),
                        parseFloat(extendedRanges[rk].max),
                        col.invertColor,
                      )
                    : {};
                  return (
                    <td key={col.key} className="px-2 py-1.5" style={cellStyle}>
                      {col.key === 'projectId' ? (
                        <span className={cardTextClass(proj?.color)}>
                          {formatted}
                          <span className="ml-1 text-[10px] text-machi-text-dim/50">
                            {isExpanded ? '▾' : '▸'}
                          </span>
                        </span>
                      ) : formatted}
                    </td>
                  );
                })}
              </tr>
            );
          })}
          {/* Expanded row detail — rendered as a separate element after the table */}
        </tbody>
      </table>
      {/* Row expand detail panel (outside table for layout flexibility) */}
      {expandedRow && (() => {
        const opt = sorted.find(o => o.projectId === expandedRow);
        if (!opt) return null;
        return (
          <div className="mt-1 mb-2 px-2 py-2 bg-machi-border/10 rounded-lg">
            <ExplanationFactors
              factors={opt.structuredFactors ?? []}
              fallback={opt.explanationFactors}
            />
          </div>
        );
      })()}
    </div>
  );
}

function getValue(opt: RankedOption & { rank: number }, key: string): number {
  if (key === 'rank') return opt.rank;
  if (key === 'score') return opt.score;
  if (key === 'affordable') return opt.affordable ? 1 : 0;
  if (opt.metrics && key in opt.metrics) return parseFloat(opt.metrics[key]) || 0;
  return 0;
}

function getCellValue(
  opt: RankedOption & { rank: number },
  key: string,
  proj: ProjectDef | undefined,
  language: 'de' | 'en',
): string | number | boolean {
  switch (key) {
    case 'rank': return opt.rank;
    case 'projectId': {
      if (opt.projectId === '_wait_') return language === 'de' ? 'Sparen' : 'Save';
      return proj?.[`name_${language}` as 'name_de' | 'name_en'] ?? opt.projectId;
    }
    case 'score': return opt.score;
    case 'cost': return opt.projectId === '_wait_' ? 0 : (proj?.cost ?? 0);
    case 'affordable': return opt.affordable;
    default: return opt.metrics?.[key] ?? '—';
  }
}

function cardTextClass(color?: string): string {
  switch (color) {
    case 'blau': return 'text-machi-blue';
    case 'rot': return 'text-machi-red';
    case 'grün': return 'text-machi-green';
    case 'lila': return 'text-machi-purple';
    case 'gelb': return 'text-machi-yellow';
    default: return 'text-machi-text';
  }
}

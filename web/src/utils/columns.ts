/** Ranked list column definitions — easily adjustable array. */

export interface ColumnDef {
  key: string;
  label: string;
  sortable: boolean;
  format: 'number' | 'percent' | 'decimal' | 'coins' | 'card' | 'boolean';
  colorGradient?: boolean;
  invertColor?: boolean;
  /** Metric key to use for color gradient range lookup (defaults to key). */
  rangeKey?: string;
  /** Tooltip shown on hover over column header. */
  tooltip?: string;
}

/** Default columns for the ranked options table. */
export const COLUMNS: ColumnDef[] = [
  { key: 'rank',            label: '#',              sortable: false, format: 'number',
    tooltip: 'Rank by engine score (best first)' },
  { key: 'projectId',       label: 'Card',           sortable: true,  format: 'card',
    tooltip: 'Card name' },
  { key: 'score',           label: 'Win Rate',       sortable: true,  format: 'percent', colorGradient: true, rangeKey: 'winRate',
    tooltip: 'MCTS simulation win rate — probability of winning if you buy this card' },
  { key: 'cost',            label: 'Cost',           sortable: true,  format: 'coins', colorGradient: true, invertColor: true,
    tooltip: 'Purchase cost in coins' },
  { key: 'immediateEV',     label: 'EV/Turn',        sortable: true,  format: 'decimal', colorGradient: true,
    tooltip: 'Expected coins gained on your own turn from this card alone' },
  { key: 'evPerRound',      label: 'EV/Round',       sortable: true,  format: 'decimal', colorGradient: true,
    tooltip: 'Expected coins gained per full round (all players\' turns) from this card' },
  { key: 'portfolioDeltaEV',label: 'Port. Delta',    sortable: true,  format: 'decimal', colorGradient: true,
    tooltip: 'How much this card improves your total portfolio EV per round' },
  { key: 'winProbDelta',    label: 'Win% Delta',     sortable: true,  format: 'percent', colorGradient: true,
    tooltip: 'Analytical heuristic change in win probability from buying this card' },
  { key: 'turnsToWin',      label: 'ETW',            sortable: true,  format: 'decimal', colorGradient: true, invertColor: true,
    tooltip: 'Estimated Turns to Win — how many turns until you can buy all remaining landmarks' },
  { key: 'tempoAdvantage',  label: 'Tempo',          sortable: true,  format: 'decimal', colorGradient: true,
    tooltip: 'Turns ahead (+) or behind (-) compared to nearest opponent' },
  { key: 'variance',        label: 'Variance',       sortable: true,  format: 'decimal', colorGradient: true, invertColor: true,
    tooltip: 'Income volatility — higher variance means more unpredictable income' },
  { key: 'affordable',      label: 'Affordable',     sortable: false, format: 'boolean',
    tooltip: 'Whether you have enough coins to buy this card after the roll' },
];

/**
 * Formats a metric value for display based on its column format.
 */
export function formatMetric(value: string | number | boolean, format: ColumnDef['format']): string {
  if (value === undefined || value === null || value === 'N/A' || value === '-') return '—';
  switch (format) {
    case 'percent': {
      const n = typeof value === 'number' ? value : parseFloat(String(value));
      return isNaN(n) ? '—' : `${(n * 100).toFixed(1)}%`;
    }
    case 'decimal': {
      const n = typeof value === 'number' ? value : parseFloat(String(value));
      return isNaN(n) ? '—' : n.toFixed(2);
    }
    case 'coins': {
      const n = typeof value === 'number' ? value : parseInt(String(value), 10);
      return isNaN(n) ? '—' : `${n}¢`;
    }
    case 'boolean':
      return value === true || value === 'true' ? '✓' : '✗';
    case 'card':
      return String(value);
    case 'number':
    default:
      return String(value);
  }
}

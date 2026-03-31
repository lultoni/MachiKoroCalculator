/** Ranked list column definitions — easily adjustable array. */

export interface ColumnDef {
  key: string;
  label: string;
  sortable: boolean;
  format: 'number' | 'percent' | 'decimal' | 'coins' | 'card' | 'boolean';
  colorGradient?: boolean;
  invertColor?: boolean;
}

/** Default columns for the ranked options table. */
export const COLUMNS: ColumnDef[] = [
  { key: 'rank',        label: '#',           sortable: false, format: 'number' },
  { key: 'projectId',   label: 'Card',        sortable: true,  format: 'card' },
  { key: 'score',       label: 'Win Rate',    sortable: true,  format: 'percent', colorGradient: true },
  { key: 'cost',        label: 'Cost',        sortable: true,  format: 'coins' },
  { key: 'immediateEV', label: 'EV/Turn',     sortable: true,  format: 'decimal', colorGradient: true },
  { key: 'evPerRound',  label: 'EV/Round',    sortable: true,  format: 'decimal', colorGradient: true },
  { key: 'variance',    label: 'Variance',    sortable: true,  format: 'decimal', colorGradient: true, invertColor: true },
  { key: 'affordable',  label: 'Affordable',  sortable: false, format: 'boolean' },
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

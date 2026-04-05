/** Card display helpers — color classes, category icons. */

/** Tailwind text color class for a card's game color. */
export function cardTextClass(color?: string): string {
  switch (color) {
    case 'blau': return 'text-machi-blue';
    case 'rot': return 'text-machi-red';
    case 'grün': return 'text-machi-green';
    case 'lila': return 'text-machi-purple';
    case 'gelb': return 'text-machi-yellow';
    default: return 'text-machi-text';
  }
}

/** Small emoji icon for a card's category (synergy type). */
export function categoryIcon(category?: string): string {
  switch (category) {
    case 'food':       return '🌾';
    case 'animal':     return '🐄';
    case 'production': return '⛏';
    case 'cafe':       return '☕';
    case 'store':      return '🏪';
    case 'factory':    return '🏭';
    case 'market':     return '🛒';
    case 'office':     return '🏛';
    default:           return '';
  }
}

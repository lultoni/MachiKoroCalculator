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

/** PNG icon path for a card's category (synergy type). Returns empty string if unknown. */
export function categoryIconPath(category?: string): string {
  switch (category) {
    case 'food':       return '/icons/FOOD.png';
    case 'animal':     return '/icons/ANIMAL.png';
    case 'production': return '/icons/PRODUCTION.png';
    case 'cafe':       return '/icons/CAFE.png';
    case 'store':      return '/icons/STORE.png';
    case 'factory':    return '/icons/FACTORY.png';
    case 'market':     return '/icons/MARKET.png';
    case 'office':     return '/icons/OFFICE.png';
    default:           return '';
  }
}

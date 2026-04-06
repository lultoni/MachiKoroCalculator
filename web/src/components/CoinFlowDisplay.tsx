/** Coin flow display — Now / Roll / Buy columns with live updates. */

import { useLocale } from '../i18n/useLocale';
import { cardTextClass, categoryIconPath } from '../utils/cardDisplay';
import type { HoverCard } from '../hooks/useHover';

interface Props {
  coinsNow: number;
  coinDelta: number | null;       // from perRollDeltas for active player
  hovered: HoverCard | null;
  language: 'de' | 'en';
  projectName?: string;           // name of hovered card
  projectColor?: string;          // color of hovered card
  projectCategory?: string;       // category of hovered card
  recommendedName?: string;       // name of top recommended card (fallback)
  recommendedCost?: number;       // cost of top recommended card
  recommendedColor?: string;      // color of top recommended card
  recommendedCategory?: string;   // category of top recommended card
}

export function CoinFlowDisplay({ coinsNow, coinDelta, hovered, projectName, projectColor, projectCategory, recommendedName, recommendedCost, recommendedColor, recommendedCategory }: Props) {
  const { t } = useLocale();
  const coinsAfterRoll = coinDelta != null ? coinsNow + coinDelta : null;
  const coinsAfterBuy = coinsAfterRoll != null && hovered ? coinsAfterRoll - hovered.cost : null;

  // Fallback: show recommended card when nothing is hovered
  const showRecommended = !hovered && recommendedName && recommendedCost != null && coinsAfterRoll != null;
  const displayCost = showRecommended ? coinsAfterRoll! - recommendedCost! : coinsAfterBuy;
  const displayName = showRecommended ? recommendedName : projectName;
  const displayColor = showRecommended ? recommendedColor : projectColor;
  const displayCategory = showRecommended ? recommendedCategory : projectCategory;

  return (
    <div className="grid grid-cols-3 gap-4 text-center">
      {/* Now */}
      <div>
        <div className="text-xs text-machi-text-dim uppercase tracking-wider mb-1">{t('coins.now')}</div>
        <div className="text-2xl font-bold text-machi-yellow">{coinsNow}</div>
        <div className="h-4" />
      </div>

      {/* Roll */}
      <div>
        <div className="text-xs text-machi-text-dim uppercase tracking-wider mb-1">{t('coins.roll')}</div>
        {coinsAfterRoll != null ? (
          <div className="text-2xl font-bold">
            <span className={coinDelta! > 0 ? 'text-machi-green' : coinDelta! < 0 ? 'text-machi-red' : 'text-machi-text'}>
              {coinsAfterRoll}
            </span>
            <span className="text-xs ml-1 text-machi-text-dim">
              {coinDelta! > 0 ? `+${coinDelta}` : coinDelta! < 0 ? String(coinDelta) : '±0'}
            </span>
          </div>
        ) : (
          <div className="text-2xl font-bold text-machi-text-dim">—</div>
        )}
        <div className="h-4" />
      </div>

      {/* Buy */}
      <div>
        <div className="text-xs text-machi-text-dim uppercase tracking-wider mb-1">{t('coins.buy')}</div>
        {displayCost != null ? (
          <div className={`text-2xl font-bold ${
            showRecommended ? 'text-machi-text-dim/30' : displayCost < 0 ? 'text-machi-red' : 'text-machi-text'
          }`}>
            {displayCost}
          </div>
        ) : (
          <div className="text-2xl font-bold text-machi-text-dim">—</div>
        )}
        {/* Always-visible project name line (fixed height prevents jitter) */}
        <div className="text-xs mt-0.5 truncate h-4">
          {displayName ? (
            <span className={`inline-flex items-center ${showRecommended ? 'opacity-30' : ''} ${cardTextClass(displayColor)}`}>
              {categoryIconPath(displayCategory) && (
                <img src={categoryIconPath(displayCategory)} alt="" className="w-3 h-3 mr-0.5" />
              )}
              {displayName}
            </span>
          ) : (
            '\u00A0'
          )}
        </div>
      </div>
    </div>
  );
}

/** Coin flow display — Now / Roll / Buy columns with live updates. */

import { useLocale } from '../i18n/useLocale';
import type { HoverCard } from '../hooks/useHover';

interface Props {
  coinsNow: number;
  coinDelta: number | null;       // from perRollDeltas for active player
  hovered: HoverCard | null;
  language: 'de' | 'en';
  projectName?: string;           // name of hovered card
}

export function CoinFlowDisplay({ coinsNow, coinDelta, hovered, projectName }: Props) {
  const { t } = useLocale();
  const coinsAfterRoll = coinDelta != null ? coinsNow + coinDelta : null;
  const coinsAfterBuy = coinsAfterRoll != null && hovered ? coinsAfterRoll - hovered.cost : null;

  return (
    <div className="grid grid-cols-3 gap-4 text-center">
      {/* Now */}
      <div>
        <div className="text-xs text-machi-text-dim uppercase tracking-wider mb-1">{t('coins.now')}</div>
        <div className="text-2xl font-bold text-machi-yellow">{coinsNow}</div>
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
      </div>

      {/* Buy */}
      <div>
        <div className="text-xs text-machi-text-dim uppercase tracking-wider mb-1">{t('coins.buy')}</div>
        {coinsAfterBuy != null ? (
          <>
            <div className={`text-2xl font-bold ${coinsAfterBuy < 0 ? 'text-machi-red' : 'text-machi-text'}`}>
              {coinsAfterBuy}
            </div>
            {projectName && (
              <div className="text-xs text-machi-text-dim mt-0.5 truncate">{projectName}</div>
            )}
          </>
        ) : (
          <div className="text-2xl font-bold text-machi-text-dim">—</div>
        )}
      </div>
    </div>
  );
}

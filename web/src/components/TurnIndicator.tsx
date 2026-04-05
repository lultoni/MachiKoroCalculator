/** Turn indicator — shows active player, turn/round count, bonus turn badge. */

import { useLocale } from '../i18n/useLocale';
import type { SessionJson } from '../api/types';

interface Props {
  session: SessionJson;
  userPlayerIndex: number;
}

export function TurnIndicator({ session, userPlayerIndex }: Props) {
  const { t } = useLocale();
  const active = session.state.players[session.nextPlayerIndex];
  const isUserTurn = session.nextPlayerIndex === userPlayerIndex;
  const round = Math.ceil(session.effectiveTurnCount / session.state.players.length) || 1;

  return (
    <div className="flex items-center gap-3 px-4 py-2 bg-machi-surface/50 border-b border-machi-border">
      {/* Player badges */}
      <div className="flex items-center gap-2">
        {session.state.players.map((p, i) => (
          <span
            key={i}
            className={`px-3 py-1 rounded-full text-sm font-medium transition-all ${
              i === session.nextPlayerIndex
                ? 'bg-machi-accent text-machi-bg scale-105 shadow-lg shadow-machi-accent/20'
                : 'text-machi-text-dim hover:text-machi-text'
            }`}
          >
            {p.name}
            <span className="ml-1 text-xs opacity-75">{p.coins}c</span>
          </span>
        ))}
      </div>

      {/* Bonus turn badge */}
      {session.bonusTurnPending && (
        <span className="px-2 py-0.5 rounded bg-machi-yellow/20 text-machi-yellow text-xs font-bold animate-pulse">
          {t('turn.bonus')}
        </span>
      )}

      {/* Spacer */}
      <div className="flex-1" />

      {/* Round + Turn counters */}
      <div className="flex items-center gap-2 text-sm">
        <span className="px-2 py-0.5 rounded bg-machi-accent/10 text-machi-text font-medium">
          {t('turn.round', { n: round })}
        </span>
        <span className="text-machi-text-dim">
          {t('turn.count', { n: session.effectiveTurnCount })}
        </span>
      </div>

      {/* Active player label */}
      <span className="text-sm font-medium">
        {isUserTurn ? t('turn.your') : t('turn.opponent', { name: active.name })}
      </span>
    </div>
  );
}

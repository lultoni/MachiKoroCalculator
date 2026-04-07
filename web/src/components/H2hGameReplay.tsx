import { useState, useMemo } from 'react';
import { useLocale } from '../i18n/useLocale';
import type { H2hGameLog, H2hTurnLog, ProjectDef } from '../api/types';
import { cardTextClass, categoryIconPath } from '../utils/cardDisplay';
import { CardTooltip } from './CardTooltip';

interface Props {
  game: H2hGameLog;
  engines: string[];
  projects: { byId: (id: string) => ProjectDef | undefined; projects: ProjectDef[] };
  language: 'de' | 'en';
  onBack: () => void;
}

const LANDMARK_IDS = ['bahnhof', 'einkaufszentrum', 'freizeitpark', 'funkturm'];
const LANDMARK_ABBR_DE = ['B', 'E', 'F', 'F'];
const LANDMARK_ABBR_EN = ['T', 'S', 'A', 'R'];
const STARTER_CARDS = ['weizenfeld', 'bäckerei'];

/** Reconstruct per-player card inventories and coin totals up to each turn. */
function buildInventoryTimeline(game: H2hGameLog, playerCount: number) {
  // inventories[turnIdx][playerIdx] = cardId[]
  const inventories: string[][][] = [];
  // coins[turnIdx][playerIdx] = number
  const coinHistory: number[][] = [];

  const current: string[][] = Array.from({ length: playerCount }, () => [...STARTER_CARDS]);
  const coins = Array(playerCount).fill(3);

  for (const tn of game.turns) {
    // Apply income
    for (let i = 0; i < playerCount; i++) {
      coins[i] = Math.max(0, coins[i] + (tn.coinDeltas?.[i] ?? 0));
    }

    // Bürohaus swap
    if (tn.bürohausSwap) {
      const parts = tn.bürohausSwap.split('→');
      if (parts.length === 2) {
        const ownCardId = parts[0].trim();
        const oppCardId = parts[1].trim();
        const pi = tn.playerIndex;
        const oi = 1 - pi;
        // Remove ownCard from active player, add oppCard
        const ownIdx = current[pi].indexOf(ownCardId);
        if (ownIdx >= 0) current[pi].splice(ownIdx, 1);
        current[pi].push(oppCardId);
        // Remove oppCard from opponent, add ownCard
        const oppIdx = current[oi].indexOf(oppCardId);
        if (oppIdx >= 0) current[oi].splice(oppIdx, 1);
        current[oi].push(ownCardId);
      }
    }

    // Purchase
    if (tn.purchasedCardId) {
      current[tn.playerIndex].push(tn.purchasedCardId);
      coins[tn.playerIndex] = tn.coinsAfterPurchase;
    }

    inventories.push(current.map(arr => [...arr]));
    coinHistory.push([...coins]);
  }

  return { inventories, coinHistory };
}

/** Count occurrences of each card ID. */
function countCards(ids: string[]): [string, number][] {
  const map = new Map<string, number>();
  for (const id of ids) {
    map.set(id, (map.get(id) ?? 0) + 1);
  }
  return Array.from(map.entries());
}

/** Compute game-level insights. */
function computeInsights(game: H2hGameLog, playerCount: number) {
  const totalIncome = Array(playerCount).fill(0);
  const totalPurchases = Array(playerCount).fill(0);
  const saveTurns = Array(playerCount).fill(0);
  let doublesCount = 0;
  let funkturmCount = 0;
  let bürohausCount = 0;

  for (const tn of game.turns) {
    // Income
    for (let i = 0; i < playerCount; i++) {
      const d = tn.coinDeltas?.[i] ?? 0;
      if (d > 0) totalIncome[i] += d;
    }
    // Purchases
    if (tn.purchasedCardId) {
      totalPurchases[tn.playerIndex]++;
    } else {
      saveTurns[tn.playerIndex]++;
    }
    if (tn.isDoubles) doublesCount++;
    if (tn.funkturmRerolled) funkturmCount++;
    if (tn.bürohausSwap) bürohausCount++;
  }

  return { totalIncome, totalPurchases, saveTurns, doublesCount, funkturmCount, bürohausCount };
}

/** Background color class for card color. */
function cardBgClass(color?: string): string {
  switch (color) {
    case 'blau': return 'bg-machi-blue/15';
    case 'rot': return 'bg-machi-red/15';
    case 'grün': return 'bg-machi-green/15';
    case 'lila': return 'bg-machi-purple/15';
    case 'gelb': return 'bg-machi-yellow/15';
    default: return 'bg-machi-bg';
  }
}

export function H2hGameReplay({ game, engines, projects, language, onBack }: Props) {
  const { t } = useLocale();
  const [turnIdx, setTurnIdx] = useState(0);
  const nameKey = `name_${language}` as 'name_de' | 'name_en';
  const landmarkAbbr = language === 'en' ? LANDMARK_ABBR_EN : LANDMARK_ABBR_DE;

  const turn = game.turns[turnIdx] as H2hTurnLog | undefined;
  const totalTurns = game.turns.length;
  const playerCount = engines.length;

  const { inventories, coinHistory } = useMemo(
    () => buildInventoryTimeline(game, playerCount),
    [game, playerCount],
  );

  const insights = useMemo(
    () => computeInsights(game, playerCount),
    [game, playerCount],
  );

  const currentInv = inventories[turnIdx];

  /** Render a compact card inventory for one player. */
  const renderPlayerHand = (playerIdx: number) => {
    if (!currentInv) return null;
    const cards = currentInv[playerIdx];
    const landmarks = cards.filter(id => LANDMARK_IDS.includes(id));
    const nonLandmarks = cards.filter(id => !LANDMARK_IDS.includes(id));
    const counted = countCards(nonLandmarks);

    return (
      <div>
        {/* Landmarks */}
        <div className="flex gap-1 mb-1.5">
          {LANDMARK_IDS.map((lmId, i) => {
            const owned = landmarks.includes(lmId);
            const proj = projects.byId(lmId);
            return (
              <CardTooltip key={lmId} project={proj} language={language}>
                <span className={`inline-block w-6 h-6 rounded text-center text-xs font-bold leading-6 ${
                  owned ? 'bg-machi-yellow/30 text-machi-yellow' : 'bg-machi-bg text-machi-text-dim/30'
                }`}>
                  {landmarkAbbr[i]}
                </span>
              </CardTooltip>
            );
          })}
        </div>
        {/* Cards */}
        <div className="flex flex-wrap gap-1">
          {counted.map(([id, count]) => {
            const proj = projects.byId(id);
            const name = proj?.[nameKey] ?? proj?.name_de ?? id;
            return (
              <CardTooltip key={id} project={proj} language={language}>
                <span className={`inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] ${cardBgClass(proj?.color)} ${cardTextClass(proj?.color)}`}>
                  {categoryIconPath(proj?.category) && (
                    <img src={categoryIconPath(proj?.category)} alt="" className="w-3 h-3" />
                  )}
                  {name}{count > 1 && <span className="opacity-70">×{count}</span>}
                </span>
              </CardTooltip>
            );
          })}
        </div>
      </div>
    );
  };

  return (
    <div className="min-h-screen bg-machi-bg text-machi-text p-6">
      <div className="max-w-6xl mx-auto">
        {/* Header */}
        <div className="flex items-center gap-4 mb-4">
          <button
            onClick={onBack}
            className="text-machi-text-dim hover:text-machi-text transition"
          >
            ← {t('btn.back')}
          </button>
          <h1 className="text-xl font-bold">
            {t('h2h.game')} #{game.gameIndex + 1}
            {game.timeoutWin && <span className="ml-2 text-sm text-machi-text-dim">({t('h2h.timeout')})</span>}
          </h1>
          <span className="ml-auto text-sm text-machi-text-dim">
            P{game.winnerIndex + 1} {t('h2h.won')} · {game.totalTurns} {t('h2h.turns')}
          </span>
        </div>

        {/* Turn Navigation */}
        <div className="flex items-center gap-3 mb-4">
          <button
            onClick={() => setTurnIdx(0)}
            disabled={turnIdx === 0}
            className="px-3 py-1.5 bg-machi-surface border border-machi-border rounded-lg text-sm
                       disabled:opacity-30 hover:bg-machi-bg transition"
          >
            ⏮
          </button>
          <button
            onClick={() => setTurnIdx(i => Math.max(0, i - 1))}
            disabled={turnIdx === 0}
            className="px-3 py-1.5 bg-machi-surface border border-machi-border rounded-lg text-sm
                       disabled:opacity-30 hover:bg-machi-bg transition"
          >
            ◀
          </button>
          <span className="text-sm font-mono flex-1 text-center">
            {t('h2h.turnN', { n: String(turnIdx + 1) })} / {totalTurns}
          </span>
          <button
            onClick={() => setTurnIdx(i => Math.min(totalTurns - 1, i + 1))}
            disabled={turnIdx >= totalTurns - 1}
            className="px-3 py-1.5 bg-machi-surface border border-machi-border rounded-lg text-sm
                       disabled:opacity-30 hover:bg-machi-bg transition"
          >
            ▶
          </button>
          <button
            onClick={() => setTurnIdx(totalTurns - 1)}
            disabled={turnIdx >= totalTurns - 1}
            className="px-3 py-1.5 bg-machi-surface border border-machi-border rounded-lg text-sm
                       disabled:opacity-30 hover:bg-machi-bg transition"
          >
            ⏭
          </button>
        </div>

        {/* Main 3-column layout: P1 Hand | Turn Detail | P2 Hand */}
        <div className="grid grid-cols-1 lg:grid-cols-[1fr_2fr_1fr] gap-4 mb-4">
          {/* P1 Hand */}
          <div className="bg-machi-surface rounded-xl p-4 border border-machi-border">
            <div className="flex items-center gap-2 mb-2">
              <span className="inline-block w-2.5 h-2.5 rounded-full bg-machi-accent" />
              <span className="text-xs font-semibold">P1: {engines[0]}</span>
              <span className="ml-auto text-xs font-mono text-machi-yellow">{coinHistory[turnIdx]?.[0] ?? 3}$</span>
            </div>
            {renderPlayerHand(0)}
          </div>

          {/* Turn Detail (center) */}
          {turn && (() => {
            const isP1 = turn.playerIndex === 0;
            const gradientBg = isP1
              ? 'linear-gradient(to right, #38bdf8, #334155 33%, #334155)'
              : 'linear-gradient(to left, #E879F9, #334155 33%, #334155)';
            return (
            <div key={`turn-border-${turnIdx}`} className="rounded-xl p-[5px]" style={{ background: gradientBg }}>
            <div className="bg-machi-surface rounded-[7px] p-5">
              <div className="flex items-center gap-3 mb-3">
                <span className={`inline-block w-3 h-3 rounded-full ${turn.playerIndex === 0 ? 'bg-machi-accent' : 'bg-machi-purple'}`} />
                <span className="font-semibold">
                  P{turn.playerIndex + 1} ({engines[turn.playerIndex]})
                </span>
                <span className="text-machi-text-dim text-sm ml-auto">
                  {turn.evaluateTimeMs}ms
                </span>
              </div>

              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
                {/* Dice */}
                <div className="bg-machi-bg rounded-lg p-3">
                  <div className="text-machi-text-dim text-xs mb-1">{t('h2h.dice')}</div>
                  <div className="font-mono text-lg">
                    {turn.diceCount}d6 → {turn.roll}
                    {turn.isDoubles && <span className="ml-1 text-machi-yellow text-xs">D</span>}
                  </div>
                  {turn.funkturmRerolled && (
                    <div className="text-xs text-machi-accent mt-0.5">
                      {projects.byId('funkturm')?.[nameKey] ?? (language === 'en' ? 'Radio Tower' : 'Funkturm')} ↻
                    </div>
                  )}
                </div>

                {/* Income */}
                <div className="bg-machi-bg rounded-lg p-3">
                  <div className="text-machi-text-dim text-xs mb-1">{t('h2h.income')}</div>
                  <div className="font-mono">
                    {engines.map((_, i) => (
                      <span key={i} className={`mr-2 ${
                        (turn.coinDeltas?.[i] ?? 0) > 0 ? 'text-green-400' :
                        (turn.coinDeltas?.[i] ?? 0) < 0 ? 'text-red-400' : 'text-machi-text-dim'
                      }`}>
                        P{i + 1}: {(turn.coinDeltas?.[i] ?? 0) >= 0 ? '+' : ''}{turn.coinDeltas?.[i] ?? 0}
                      </span>
                    ))}
                  </div>
                </div>

                {/* Purchase */}
                <div className="bg-machi-bg rounded-lg p-3">
                  <div className="text-machi-text-dim text-xs mb-1">{t('h2h.purchase')}</div>
                  <div className="font-mono">
                    {turn.purchasedCardId ? (() => {
                      const proj = projects.byId(turn.purchasedCardId);
                      const name = proj?.[nameKey] ?? proj?.name_de ?? turn.purchasedCardId;
                      return (
                        <CardTooltip project={proj} language={language}>
                          <span className={`inline-flex items-center ${cardTextClass(proj?.color)}`}>
                            {categoryIconPath(proj?.category) && (
                              <img src={categoryIconPath(proj?.category)} alt="" className="w-3.5 h-3.5 mr-0.5" />
                            )}
                            {name}
                          </span>
                        </CardTooltip>
                      );
                    })() : t('h2h.save')}
                  </div>
                  <div className="text-xs text-machi-text-dim mt-0.5">
                    WR: {(turn.purchaseWinRate * 100).toFixed(1)}%
                  </div>
                </div>

                {/* Coins After */}
                <div className="bg-machi-bg rounded-lg p-3">
                  <div className="text-machi-text-dim text-xs mb-1">{t('h2h.coins')}</div>
                  <div className="font-mono">
                    {coinHistory[turnIdx]?.map((c: number, i: number) => (
                      <span key={i} className="mr-2">P{i + 1}: {c}</span>
                    ))}
                  </div>
                </div>
              </div>

              {(turn.bürohausSwap || turn.bürohausActivated) && (() => {
                const bürohausName = projects.byId('bürohaus')?.[nameKey] ?? (language === 'en' ? 'Business Center' : 'Bürohaus');
                if (turn.bürohausSwap) {
                  const parts = turn.bürohausSwap.split('→');
                  const ownId = parts[0]?.trim();
                  const oppId = parts[1]?.trim();
                  const ownProj = ownId ? projects.byId(ownId) : undefined;
                  const oppProj = oppId ? projects.byId(oppId) : undefined;
                  const ownName = ownProj?.[nameKey] ?? ownProj?.name_de ?? ownId;
                  const oppName = oppProj?.[nameKey] ?? oppProj?.name_de ?? oppId;
                  return (
                    <div className="mt-2 flex items-center gap-1.5 text-xs text-machi-purple">
                      <span className="font-semibold">{bürohausName}:</span>
                      <span className="inline-flex items-center gap-0.5">
                        {categoryIconPath(ownProj?.category) && (
                          <img src={categoryIconPath(ownProj?.category)} alt="" className="w-3 h-3" />
                        )}
                        <span className={cardTextClass(ownProj?.color)}>{ownName}</span>
                      </span>
                      <span className="text-machi-text-dim">→</span>
                      <span className="inline-flex items-center gap-0.5">
                        {categoryIconPath(oppProj?.category) && (
                          <img src={categoryIconPath(oppProj?.category)} alt="" className="w-3 h-3" />
                        )}
                        <span className={cardTextClass(oppProj?.color)}>{oppName}</span>
                      </span>
                    </div>
                  );
                }
                return (
                  <div className="mt-2 text-xs text-machi-purple/60">
                    {bürohausName}: {language === 'en' ? 'declined' : 'abgelehnt'}
                  </div>
                );
              })()}
            </div>
            </div>
          )})()}

          {/* P2 Hand */}
          <div className="bg-machi-surface rounded-xl p-4 border border-machi-border">
            <div className="flex items-center gap-2 mb-2">
              <span className="inline-block w-2.5 h-2.5 rounded-full bg-machi-purple" />
              <span className="text-xs font-semibold">P2: {engines[1]}</span>
              <span className="ml-auto text-xs font-mono text-machi-yellow">{coinHistory[turnIdx]?.[1] ?? 3}$</span>
            </div>
            {renderPlayerHand(1)}
          </div>
        </div>

        {/* Game Insights */}
        <div className="bg-machi-surface rounded-xl p-4 border border-machi-border mb-4">
          <h3 className="text-sm font-semibold mb-3">{t('h2h.gameInsights')}</h3>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-3 text-xs">
            {engines.map((eng, i) => (
              <div key={i} className="bg-machi-bg rounded-lg p-3">
                <div className="text-machi-text-dim mb-1">P{i + 1}: {eng}</div>
                <div className="space-y-0.5">
                  <div>{t('h2h.totalIncome')}: <span className="text-green-400 font-mono">{insights.totalIncome[i]}</span></div>
                  <div>{t('h2h.purchases')}: <span className="font-mono">{insights.totalPurchases[i]}</span></div>
                  <div>{t('h2h.saves')}: <span className="font-mono">{insights.saveTurns[i]}</span></div>
                </div>
              </div>
            ))}
            <div className="bg-machi-bg rounded-lg p-3">
              <div className="text-machi-text-dim mb-1">{t('h2h.gameEvents')}</div>
              <div className="space-y-0.5">
                <div>{t('dice.doubles')}: <span className="font-mono">{insights.doublesCount}</span></div>
                {insights.funkturmCount > 0 && (
                  <div>{projects.byId('funkturm')?.[nameKey] ?? (language === 'en' ? 'Radio Tower' : 'Funkturm')}: <span className="font-mono">{insights.funkturmCount}</span></div>
                )}
                {insights.bürohausCount > 0 && (
                  <div>{projects.byId('bürohaus')?.[nameKey] ?? (language === 'en' ? 'Business Center' : 'Bürohaus')}: <span className="font-mono">{insights.bürohausCount}</span></div>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* Final State */}
        <div className="bg-machi-surface rounded-xl p-4 border border-machi-border">
          <h3 className="text-sm font-semibold mb-2">{t('h2h.finalState')}</h3>
          <div className="grid grid-cols-2 gap-4 text-sm">
            {engines.map((eng, i) => (
              <div key={i} className={`rounded-lg p-3 ${i === game.winnerIndex ? 'bg-machi-accent/10 border border-machi-accent/30' : 'bg-machi-bg'}`}>
                <div className="font-semibold mb-1">
                  P{i + 1}: {eng}
                  {i === game.winnerIndex && <span className="ml-2 text-machi-accent text-xs">★</span>}
                </div>
                <div className="text-machi-text-dim">
                  {game.finalCoins?.[i]} {t('h2h.coins')} · {game.landmarkCounts?.[i]}/4 {t('h2h.landmarks')}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

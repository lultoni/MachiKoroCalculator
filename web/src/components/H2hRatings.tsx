import { useState, useEffect, useMemo } from 'react';
import { useLocale } from '../i18n/useLocale';
import * as api from '../api/client';
import type { EngineRating } from '../api/types';

interface Props {
  onBack: () => void;
}

export function H2hRatings({ onBack }: Props) {
  const { t } = useLocale();
  const [ratings, setRatings] = useState<Record<string, EngineRating>>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.h2hRatings()
      .then(r => setRatings(r.ratings))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const sorted = useMemo(() =>
    Object.entries(ratings).sort((a, b) => b[1].rating - a[1].rating),
    [ratings],
  );

  const bestRating = sorted.length > 0 ? sorted[0][1].rating : 0;

  return (
    <div className="min-h-screen bg-machi-bg text-machi-text p-6">
      <div className="max-w-4xl mx-auto">
        {/* Header */}
        <div className="flex items-center gap-4 mb-6">
          <button
            onClick={onBack}
            className="text-machi-text-dim hover:text-machi-text transition"
          >
            ← {t('btn.back')}
          </button>
          <h1 className="text-2xl font-bold">{t('h2h.ratingsTitle')}</h1>
        </div>

        <div className="bg-machi-surface rounded-xl p-6 border border-machi-border">
          {/* Legend */}
          <div className="mb-4 text-xs text-machi-text-dim space-y-1">
            <p>{t('h2h.ratingsInfo')}</p>
          </div>

          {loading ? (
            <p className="text-machi-text-dim text-sm">{t('h2h.ratingsLoading')}</p>
          ) : sorted.length === 0 ? (
            <p className="text-machi-text-dim text-sm">{t('h2h.noResults')}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-machi-text-dim border-b border-machi-border">
                    <th className="text-left py-2 px-2">#</th>
                    <th className="text-left py-2 px-2">{t('h2h.ratingsEngine')}</th>
                    <th
                      className="text-center py-2 px-2 cursor-help"
                      title={t('h2h.ratingsRatingTip')}
                    >
                      {t('h2h.ratingsRating')}
                    </th>
                    <th
                      className="text-center py-2 px-2 cursor-help"
                      title={t('h2h.ratingsRdTip')}
                    >
                      {t('h2h.ratingsRd')}
                    </th>
                    <th
                      className="text-center py-2 px-2 cursor-help"
                      title={t('h2h.ratingsMatchesTip')}
                    >
                      {t('h2h.ratingsMatches')}
                    </th>
                    <th
                      className="text-center py-2 px-2 cursor-help"
                      title={t('h2h.ratingsConfTip')}
                    >
                      {t('h2h.ratingsConf')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {sorted.map(([id, r], i) => {
                    const conf = confidenceLevel(r.rd);
                    return (
                      <tr
                        key={id}
                        className="border-b border-machi-border/50 hover:bg-machi-bg/50 transition"
                      >
                        <td className="py-2 px-2 text-machi-text-dim">{i + 1}</td>
                        <td className="py-2 px-2 font-mono text-xs">
                          {id}
                          {r.rating === bestRating && <span className="ml-1.5 text-machi-yellow">★</span>}
                        </td>
                        <td className="text-center py-2 px-2 font-semibold font-mono">
                          {Math.round(r.rating)}
                        </td>
                        <td className="text-center py-2 px-2 text-machi-text-dim font-mono">
                          ±{Math.round(r.rd)}
                        </td>
                        <td className="text-center py-2 px-2 text-machi-text-dim">
                          {r.matchCount}
                        </td>
                        <td className="text-center py-2 px-2">
                          <span className={`text-xs px-1.5 py-0.5 rounded ${conf.class}`}>
                            {conf.label}
                          </span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function confidenceLevel(rd: number): { label: string; class: string } {
  if (rd < 100) return { label: 'High', class: 'bg-machi-green/20 text-machi-green' };
  if (rd < 200) return { label: 'Mid', class: 'bg-machi-yellow/20 text-machi-yellow' };
  return { label: 'Low', class: 'bg-machi-red/20 text-machi-red' };
}

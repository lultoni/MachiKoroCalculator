/** Card detail popover — shows full card info on hover with 500ms delay. */

import { useState, useRef, useCallback, useEffect } from 'react';
import type { ProjectDef } from '../api/types';
import { cardTextClass, categoryIconPath } from '../utils/cardDisplay';

const DELAY_MS = 500;

/** Category type references in descriptions, mapped to their icon category. */
const CATEGORY_PATTERNS: { pattern: RegExp; category: string }[] = [
  { pattern: /Lebensmittelgebäude/g, category: 'food' },
  { pattern: /Tier-Gebäude/g, category: 'animal' },
  { pattern: /Rohstoff-Gebäude/g, category: 'production' },
  { pattern: /Café- und Geschäftsgebäude/g, category: 'cafe+store' },
  { pattern: /food establishment/gi, category: 'food' },
  { pattern: /animal establishment/gi, category: 'animal' },
  { pattern: /production establishment/gi, category: 'production' },
  { pattern: /Café and Store establishments/gi, category: 'cafe+store' },
];

interface Props {
  project: ProjectDef | undefined;
  language: 'de' | 'en';
  children: React.ReactNode;
}

/** Wraps children with a hover-triggered card detail popover (500ms delay). */
export function CardTooltip({ project, language, children }: Props) {
  const [visible, setVisible] = useState(false);
  const [above, setAbove] = useState(true);
  const triggerRef = useRef<HTMLSpanElement>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleEnter = useCallback(() => {
    timerRef.current = setTimeout(() => setVisible(true), DELAY_MS);
  }, []);

  const handleLeave = useCallback(() => {
    if (timerRef.current) { clearTimeout(timerRef.current); timerRef.current = null; }
    setVisible(false);
  }, []);

  // Cleanup on unmount
  useEffect(() => () => { if (timerRef.current) clearTimeout(timerRef.current); }, []);

  // Position: above or below depending on viewport space
  useEffect(() => {
    if (visible && triggerRef.current) {
      const rect = triggerRef.current.getBoundingClientRect();
      setAbove(rect.top > 200);
    }
  }, [visible]);

  if (!project) return <>{children}</>;

  const name = project[`name_${language}` as 'name_de' | 'name_en'] ?? project.name_de;
  const desc = project[`description_${language}` as 'description_de' | 'description_en'] ?? project.description_de ?? '';
  const icon = categoryIconPath(project.category);
  const dice = project.dice_activation.length > 0
    ? project.dice_activation.join(', ')
    : '—';

  // Replace category type text references with inline icons
  const descParts: (string | { text: string; cat: string })[] = [];
  let remaining = desc;
  while (remaining.length > 0) {
    let earliest: { idx: number; len: number; cat: string } | null = null;
    for (const { pattern, category } of CATEGORY_PATTERNS) {
      pattern.lastIndex = 0;
      const m = pattern.exec(remaining);
      if (m && (earliest === null || m.index < earliest.idx)) {
        earliest = { idx: m.index, len: m[0].length, cat: category };
      }
    }
    if (!earliest) { descParts.push(remaining); break; }
    if (earliest.idx > 0) descParts.push(remaining.slice(0, earliest.idx));
    descParts.push({ text: remaining.slice(earliest.idx, earliest.idx + earliest.len), cat: earliest.cat });
    remaining = remaining.slice(earliest.idx + earliest.len);
  }

  const colorLabel: Record<string, string> = {
    blau: language === 'de' ? 'Blau' : 'Blue',
    rot: language === 'de' ? 'Rot' : 'Red',
    grün: language === 'de' ? 'Grün' : 'Green',
    lila: language === 'de' ? 'Lila' : 'Purple',
    gelb: language === 'de' ? 'Gelb' : 'Yellow',
  };

  return (
    <span
      ref={triggerRef}
      className="relative inline-flex items-center"
      onMouseEnter={handleEnter}
      onMouseLeave={handleLeave}
    >
      {children}
      {visible && (
        <div
          className={`fixed z-[100] w-56 bg-machi-surface border border-machi-border rounded-lg shadow-xl p-3 space-y-2 text-xs pointer-events-none ${
            above ? '' : ''
          }`}
          style={(() => {
            if (!triggerRef.current) return {};
            const rect = triggerRef.current.getBoundingClientRect();
            const left = Math.min(rect.left, window.innerWidth - 240);
            if (above) return { bottom: window.innerHeight - rect.top + 8, left };
            return { top: rect.bottom + 8, left };
          })()}
        >
          {/* Header */}
          <div className="flex items-center gap-1.5">
            {icon && <img src={icon} alt="" className="w-4 h-4" />}
            <span className={`font-semibold text-sm ${cardTextClass(project.color)}`}>
              {name}
            </span>
          </div>

          {/* Stats row */}
          <div className="flex gap-2 text-machi-text-dim">
            <span className="px-1.5 py-0.5 rounded bg-machi-bg">
              {project.cost}c
            </span>
            <span className="px-1.5 py-0.5 rounded bg-machi-bg">
              {dice}
            </span>
            <span className={`px-1.5 py-0.5 rounded bg-machi-bg ${cardTextClass(project.color)}`}>
              {colorLabel[project.color] ?? project.color}
            </span>
          </div>

          {/* Description */}
          <p className="text-machi-text-dim leading-relaxed">
            {descParts.map((part, i) => {
              if (typeof part === 'string') return part;
              const cats = part.cat.split('+');
              return (
                <span key={i} className="inline-flex items-center gap-0.5 align-baseline">
                  {cats.map((c, j) => (
                    <img key={j} src={categoryIconPath(c)} alt={c} className="w-3.5 h-3.5 inline-block align-text-bottom" />
                  ))}
                </span>
              );
            })}
          </p>

          {/* Category */}
          {project.category && icon && (
            <div className="flex items-center gap-1 text-machi-text-dim/60">
              <img src={icon} alt={project.category} className="w-3.5 h-3.5" />
              <span className="capitalize">{project.category}</span>
            </div>
          )}
        </div>
      )}
    </span>
  );
}

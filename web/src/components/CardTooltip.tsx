/** Card detail popover — shows full card info on hover with 500ms delay. */

import { useState, useRef, useCallback, useEffect } from 'react';
import type { ProjectDef } from '../api/types';
import { cardTextClass, categoryIconPath } from '../utils/cardDisplay';

const DELAY_MS = 500;

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
          <p className="text-machi-text-dim leading-relaxed">{desc}</p>

          {/* Category */}
          {project.category && (
            <div className="text-machi-text-dim/60 capitalize">{project.category}</div>
          )}
        </div>
      )}
    </span>
  );
}

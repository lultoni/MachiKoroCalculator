/**
 * Maps a value within a [min, max] range to a CSS color on a gradient.
 * red (worst) → yellow (mid) → green (best).
 * If `invert` is true, lower values are better (green → yellow → red).
 */
export function metricColor(
  value: number,
  min: number,
  max: number,
  invert = false,
): string {
  if (max === min) return 'rgb(234, 179, 8)'; // yellow when no range

  let t = (value - min) / (max - min);
  t = Math.max(0, Math.min(1, t));
  if (invert) t = 1 - t;

  // Interpolate: red(0) → yellow(0.5) → green(1)
  if (t < 0.5) {
    const s = t * 2; // 0..1 within red→yellow
    const r = 239;
    const g = Math.round(68 + s * (179 - 68));
    const b = Math.round(68 + s * (8 - 68));
    return `rgb(${r}, ${g}, ${b})`;
  } else {
    const s = (t - 0.5) * 2; // 0..1 within yellow→green
    const r = Math.round(234 - s * (234 - 34));
    const g = Math.round(179 + s * (197 - 179));
    const b = Math.round(8 + s * (94 - 8));
    return `rgb(${r}, ${g}, ${b})`;
  }
}

/**
 * Returns a background color class at low opacity for the metric gradient.
 * Useful for table cell backgrounds.
 */
export function metricBgStyle(
  value: number,
  min: number,
  max: number,
  invert = false,
): React.CSSProperties {
  const color = metricColor(value, min, max, invert);
  // Parse RGB and apply 15% opacity
  const match = color.match(/rgb\((\d+),\s*(\d+),\s*(\d+)\)/);
  if (!match) return {};
  return { backgroundColor: `rgba(${match[1]}, ${match[2]}, ${match[3]}, 0.15)` };
}

/*
 * Tiny dependency-free SVG charts. Each function returns an HTML string; call hydrate-free, nothing runs.
 *
 * - No inline styles (the Content-Security-Policy forbids them): colours and text sizes come from CSS classes
 *   that use the theme variables, so every chart follows light and dark mode automatically.
 * - Every chart has axis titles, tick labels, a text alternative (aria-label) and <title> tooltips on the marks.
 * - Values are plain numbers; callers decide the unit text.
 */
import { esc } from './ui.js';

const W = 640;
const PAD = { top: 16, right: 16, bottom: 44, left: 48 };

/** A "nice" axis maximum and tick step for a value (1, 2, 5 times a power of ten). */
export function niceScale(max, targetTicks = 4) {
  if (!(max > 0)) {
    return { max: 1, step: 0.25 };
  }
  const raw = max / targetTicks;
  const magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
  const residual = raw / magnitude;
  const step = (residual <= 1 ? 1 : residual <= 2 ? 2 : residual <= 5 ? 5 : 10) * magnitude;
  return { max: Math.ceil(max / step) * step, step };
}

function fmtNumber(value) {
  return Number.isInteger(value) ? String(value) : String(Math.round(value * 100) / 100);
}

function shortDate(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

function frame(height, ariaLabel, body) {
  return '<div class="chart"><svg viewBox="0 0 ' + W + ' ' + height + '" role="img" aria-label="' + esc(ariaLabel)
    + '" preserveAspectRatio="xMidYMid meet">' + body + '</svg></div>';
}

function axes({ height, maxY, step, unit, yTitle, xTitle }) {
  const plotH = height - PAD.top - PAD.bottom;
  let out = '';
  for (let v = 0; v <= maxY + 1e-9; v += step) {
    const y = PAD.top + plotH - (v / maxY) * plotH;
    out += '<line class="chart__grid" x1="' + PAD.left + '" x2="' + (W - PAD.right) + '" y1="' + y + '" y2="' + y + '"/>'
      + '<text class="chart__tick" x="' + (PAD.left - 6) + '" y="' + (y + 4) + '" text-anchor="end">' + esc(fmtNumber(v)) + esc(unit) + '</text>';
  }
  out += '<line class="chart__axis" x1="' + PAD.left + '" x2="' + (W - PAD.right) + '" y1="' + (PAD.top + plotH)
    + '" y2="' + (PAD.top + plotH) + '"/>';
  if (yTitle) {
    out += '<text class="chart__title" transform="translate(12 ' + (PAD.top + plotH / 2) + ') rotate(-90)" text-anchor="middle">'
      + esc(yTitle) + '</text>';
  }
  if (xTitle) {
    out += '<text class="chart__title" x="' + (PAD.left + (W - PAD.left - PAD.right) / 2) + '" y="' + (height - 6)
      + '" text-anchor="middle">' + esc(xTitle) + '</text>';
  }
  return out;
}

/**
 * Line + area chart over dates.
 * @param {{date: string, value: number|null, note?: string}[]} points  one per day, in order; null leaves a gap
 * @param {{yTitle?: string, xTitle?: string, unit?: string, max?: number, height?: number, ariaLabel: string}} options
 */
export function lineChart(points, { yTitle = '', xTitle = 'Date', unit = '', max = null, height = 240, ariaLabel }) {
  const values = points.map((p) => p.value).filter((v) => v !== null && v !== undefined);
  const scale = max !== null ? { max, step: max / 4 } : niceScale(values.length ? Math.max(...values) : 0);
  const plotW = W - PAD.left - PAD.right;
  const plotH = height - PAD.top - PAD.bottom;
  const n = points.length;
  const xAt = (i) => PAD.left + (n === 1 ? plotW / 2 : (i / (n - 1)) * plotW);
  const yAt = (v) => PAD.top + plotH - (v / scale.max) * plotH;

  let body = axes({ height, maxY: scale.max, step: scale.step, unit, yTitle, xTitle });

  // x tick labels: at most about 7, always including the first and last day
  const every = Math.max(1, Math.ceil(n / 7));
  points.forEach((p, i) => {
    if (i % every === 0 || i === n - 1) {
      body += '<text class="chart__tick" x="' + xAt(i) + '" y="' + (PAD.top + plotH + 16) + '" text-anchor="middle">'
        + esc(shortDate(p.date)) + '</text>';
    }
  });

  // segments of consecutive non-null values
  let segment = [];
  const segments = [];
  points.forEach((p, i) => {
    if (p.value === null || p.value === undefined) {
      if (segment.length) { segments.push(segment); segment = []; }
    } else {
      segment.push([xAt(i), yAt(p.value)]);
    }
  });
  if (segment.length) { segments.push(segment); }
  const baseline = PAD.top + plotH;
  segments.forEach((seg) => {
    if (seg.length > 1) {
      body += '<polygon class="chart__area" points="' + seg.map((pt) => pt.join(',')).join(' ') + ' '
        + seg[seg.length - 1][0] + ',' + baseline + ' ' + seg[0][0] + ',' + baseline + '"/>'
        + '<polyline class="chart__line" points="' + seg.map((pt) => pt.join(',')).join(' ') + '"/>';
    }
  });
  points.forEach((p, i) => {
    if (p.value !== null && p.value !== undefined) {
      body += '<circle class="chart__dot" cx="' + xAt(i) + '" cy="' + yAt(p.value) + '" r="' + (n > 45 ? 2 : 3.5) + '"><title>'
        + esc(shortDate(p.date) + ': ' + fmtNumber(p.value) + unit + (p.note ? ' (' + p.note + ')' : '')) + '</title></circle>';
    }
  });
  return frame(height, ariaLabel, body);
}

/**
 * Vertical bar chart for a few categories.
 * @param {{label: string, value: number, key: string}[]} items  key picks the colour class chart__bar--{key}
 */
export function barChart(items, { yTitle = '', unit = '', height = 230, ariaLabel }) {
  const scale = niceScale(Math.max(0, ...items.map((i) => i.value)));
  const plotW = W - PAD.left - PAD.right;
  const plotH = height - PAD.top - PAD.bottom;
  const slot = plotW / Math.max(items.length, 1);
  const barW = Math.min(72, slot * 0.6);
  let body = axes({ height, maxY: scale.max, step: scale.step, unit, yTitle, xTitle: '' });
  items.forEach((item, i) => {
    const h = (item.value / scale.max) * plotH;
    const x = PAD.left + slot * i + (slot - barW) / 2;
    const y = PAD.top + plotH - h;
    body += '<rect class="chart__bar chart__bar--' + esc(item.key) + '" x="' + x + '" y="' + y + '" width="' + barW
      + '" height="' + Math.max(h, item.value > 0 ? 1 : 0) + '" rx="3"><title>' + esc(item.label + ': ' + fmtNumber(item.value) + unit) + '</title></rect>'
      + '<text class="chart__value" x="' + (x + barW / 2) + '" y="' + (y - 5) + '" text-anchor="middle">' + esc(fmtNumber(item.value)) + '</text>'
      + '<text class="chart__tick" x="' + (x + barW / 2) + '" y="' + (PAD.top + plotH + 16) + '" text-anchor="middle">' + esc(item.label) + '</text>';
  });
  return frame(height, ariaLabel, body);
}

/** Legend for a bar chart: colour swatch, label and count (the colour is never the only carrier of meaning). */
export function legend(items) {
  return '<div class="legend">' + items.map((i) => '<span class="legend__item"><span class="legend__dot legend__dot--'
    + esc(i.key) + '"></span>' + esc(i.label) + ' ' + esc(String(i.value)) + '</span>').join('') + '</div>';
}

/*
 * Small UI toolkit shared by every page: escaping, icons, dates, chips, avatars,
 * toasts, dialogs, pager, and form-error helpers. No framework.
 *
 * Rule: every piece of server data that goes into an HTML string passes through esc().
 */

export function esc(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/* ---------- Icons (24px grid, stroke style) ---------- */
const ICONS = {
  grid: '<rect x="3" y="3" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="2"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="2"/>',
  users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6"/><circle cx="17.5" cy="9" r="2.5"/><path d="M17 14.2c2.6.3 4.5 2.2 4.5 5.3"/>',
  calendar: '<rect x="3" y="4.5" width="18" height="16" rx="2.5"/><path d="M3 9.5h18M8 3v3M16 3v3"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.2 3.6-7 8-7s8 2.8 8 7"/>',
  logout: '<path d="M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4"/><path d="M15 8l4 4-4 4M19 12H9"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  moon: '<path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  x: '<path d="M6 6l12 12M18 6L6 18"/>',
  edit: '<path d="M4 20h4L19 9l-4-4L4 16v4z"/><path d="M13.5 6.5l4 4"/>',
  ban: '<circle cx="12" cy="12" r="8.5"/><path d="M6 6l12 12"/>',
  eye: '<path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z"/><circle cx="12" cy="12" r="2.8"/>',
  eyeOff: '<path d="M3 3l18 18"/><path d="M10.6 6A9.6 9.6 0 0 1 12 5.5c6.4 0 10 6.5 10 6.5a17 17 0 0 1-3.2 3.9M6.4 7.5C3.8 9.2 2 12 2 12s3.6 6.5 10 6.5c1.5 0 2.8-.3 4-.9"/><path d="M9.9 10a3 3 0 0 0 4.1 4.1"/>',
  menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  left: '<path d="M14.5 6l-6 6 6 6"/>',
  right: '<path d="M9.5 6l6 6-6 6"/>',
  alert: '<path d="M12 3.5l9.5 16.5h-19L12 3.5z"/><path d="M12 10v4.5M12 17.2v.1"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.6v.1"/>',
  dice: '<rect x="3.5" y="3.5" width="17" height="17" rx="4"/><path d="M8.5 8.5v.1M15.5 8.5v.1M12 12v.1M8.5 15.5v.1M15.5 15.5v.1"/>',
  clipboard: '<rect x="5" y="4.5" width="14" height="16.5" rx="2.5"/><path d="M9 4.5v-1h6v1M8.5 11h7M8.5 15h5"/>',
  trash: '<path d="M4 7h16M9 7V4.5h6V7M6.5 7l1 13h9l1-13M10 11v6M14 11v6"/>',
  download: '<path d="M12 4v11M7.5 10.5L12 15l4.5-4.5M4.5 19.5h15"/>',
  mail: '<rect x="3" y="5.5" width="18" height="13" rx="2.5"/><path d="M3.5 7.5l8.5 6 8.5-6"/>',
  shield: '<path d="M12 3l7.5 3v5.5c0 4.6-3.1 8-7.5 9.5-4.4-1.5-7.5-4.9-7.5-9.5V6L12 3z"/><path d="M9 12l2.2 2.2L15.5 10"/>',
  inbox: '<path d="M3 13l2.5-8h13L21 13v6a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19v-6z"/><path d="M3 13h5l1.2 2.5h5.6L16 13h5"/>'
};

export function icon(name, extraClass = '') {
  return '<svg class="icon ' + extraClass + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
    + 'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">'
    + (ICONS[name] || '') + '</svg>';
}

export const BRAND_MARK =
  '<svg class="brand__mark" viewBox="0 0 32 32" aria-hidden="true" focusable="false">'
  + '<rect width="32" height="32" rx="9" fill="#ffc53d"/>'
  + '<path d="M11 8v3.5M21 8v3.5" stroke="#0d4f4c" stroke-width="2.4" stroke-linecap="round"/>'
  + '<path d="M8.5 14.5h15" stroke="#0d4f4c" stroke-width="2.2" stroke-linecap="round"/>'
  + '<path d="M11.5 20.5l3 3 6-6.5" stroke="#0d4f4c" stroke-width="2.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>'
  + '</svg>';

/* ---------- Dates ---------- */
export function parseDate(isoDate) {
  const [year, month, day] = isoDate.split('-').map(Number);
  return new Date(year, month - 1, day);
}

export function toIsoDate(date) {
  const pad = (n) => String(n).padStart(2, '0');
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate());
}

const dateFormat = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
const dayMonthFormat = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' });
const weekdayFormat = new Intl.DateTimeFormat(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
const dateTimeFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

export function formatDate(isoDate) {
  return isoDate ? dateFormat.format(parseDate(isoDate)) : '—';
}

export function formatLongDay(isoDate) {
  return weekdayFormat.format(parseDate(isoDate));
}

export function formatDateTime(instant) {
  return instant ? dateTimeFormat.format(new Date(instant)) : '—';
}

export function formatRange(start, end) {
  if (start === end) {
    return formatDate(start);
  }
  return dayMonthFormat.format(parseDate(start)) + ' – ' + dateFormat.format(parseDate(end));
}

export function plural(count, singular, pluralForm) {
  return count + ' ' + (count === 1 ? singular : (pluralForm || singular + 's'));
}

/** Weekdays (Mon-Fri) between two ISO dates, inclusive. Mirrors the server rule; the server is the authority. */
export function countWorkingDays(startIso, endIso) {
  if (!startIso || !endIso || endIso < startIso) {
    return 0;
  }
  let days = 0;
  const end = parseDate(endIso);
  for (let day = parseDate(startIso); day <= end; day.setDate(day.getDate() + 1)) {
    const dow = day.getDay();
    if (dow !== 0 && dow !== 6) {
      days += 1;
    }
  }
  return days;
}

/* ---------- People, chips, tags ---------- */
export function initials(name) {
  const parts = String(name || '?').trim().split(/\s+/).filter(Boolean);
  const first = parts[0] ? parts[0][0] : '?';
  const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (first + last).toUpperCase();
}

function hashName(name) {
  let hash = 0;
  for (const ch of String(name)) {
    hash = (hash * 31 + ch.codePointAt(0)) % 8;
  }
  return hash;
}

export function avatar(name, size = '') {
  return '<span class="avatar avatar--' + hashName(name) + (size ? ' avatar--' + size : '') + '" aria-hidden="true">'
    + esc(initials(name)) + '</span>';
}

const STATUS_LABEL = { PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected', CANCELLED: 'Cancelled' };
const STATUS_ICON = { PENDING: 'clock', APPROVED: 'check', REJECTED: 'x', CANCELLED: 'ban' };
const TYPE_LABEL = { CASUAL: 'Casual', SICK: 'Sick', EARNED: 'Earned' };

export function statusLabel(status) {
  return STATUS_LABEL[status] || status;
}

export function statusChip(status) {
  return '<span class="chip chip--' + esc(String(status).toLowerCase()) + '">'
    + icon(STATUS_ICON[status] || 'info') + esc(statusLabel(status)) + '</span>';
}

export function typeLabel(type) {
  return TYPE_LABEL[type] || type;
}

export function typeTag(type) {
  return '<span class="type-tag type-tag--' + esc(String(type).toLowerCase()) + '">' + esc(typeLabel(type)) + '</span>';
}

export function employmentChip(status) {
  const label = status === 'ACTIVE' ? 'Active' : 'Inactive';
  return '<span class="chip chip--' + esc(String(status).toLowerCase()) + '">' + label + '</span>';
}

export function roleChip(role) {
  const label = role === 'ADMIN' ? 'Admin' : 'Employee';
  return '<span class="chip chip--' + esc(String(role).toLowerCase()) + '">' + label + '</span>';
}

export function person(name, subtitle) {
  return '<div class="person">' + avatar(name)
    + '<div class="person__text"><span class="person__name">' + esc(name) + '</span>'
    + (subtitle ? '<span class="person__sub">' + esc(subtitle) + '</span>' : '')
    + '</div></div>';
}

/* ---------- Dynamic sizes without inline styles (the CSP forbids style="..." attributes) ---------- */
export function hydrate(root = document) {
  root.querySelectorAll('[data-w]').forEach((el) => el.style.setProperty('--w', el.dataset.w + '%'));
  root.querySelectorAll('[data-n]').forEach((el) => el.style.setProperty('--n', el.dataset.n));
}

/* ---------- Loading and empty states ---------- */
export function skeletonBlock(rows = 4) {
  let html = '<div class="panel__body" aria-busy="true" aria-label="Loading">';
  for (let i = 0; i < rows; i += 1) {
    html += '<span class="skeleton"></span>';
  }
  return html + '</div>';
}

export function emptyState({ title, text, actionHtml = '' }) {
  return '<div class="empty">'
    + '<svg class="empty__art" viewBox="0 0 96 96" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
    + '<rect x="12" y="18" width="72" height="64" rx="12"/><path d="M12 38h72M32 10v14M64 10v14"/>'
    + '<path d="M34 62h28" opacity=".6"/><path d="M42 52h12" opacity=".6"/></svg>'
    + '<h3>' + esc(title) + '</h3><p>' + esc(text) + '</p>' + actionHtml + '</div>';
}

export function errorState(message, retryLabel = 'Try again') {
  return '<div class="empty">' + icon('alert', 'empty__art')
    + '<h3>We couldn’t load this</h3><p>' + esc(message) + '</p>'
    + '<button type="button" class="btn btn--ghost" data-retry>' + esc(retryLabel) + '</button></div>';
}

/* ---------- Buttons ---------- */
export function setBusy(button, busy) {
  button.classList.toggle('is-loading', busy);
  button.disabled = busy;
  button.setAttribute('aria-busy', busy ? 'true' : 'false');
}

export function debounce(fn, ms = 300) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}

/* ---------- Toasts ---------- */
function toastHost() {
  let host = document.getElementById('toasts');
  if (!host) {
    host = document.createElement('div');
    host.id = 'toasts';
    host.className = 'toasts';
    host.setAttribute('role', 'region');
    host.setAttribute('aria-label', 'Notifications');
    host.setAttribute('aria-live', 'polite');
    document.body.appendChild(host);
  }
  return host;
}

export function toast(message, type = 'success') {
  const node = document.createElement('div');
  node.className = 'toast toast--' + type;
  node.innerHTML = icon(type === 'error' ? 'alert' : 'check')
    + '<span class="toast__text">' + esc(message) + '</span>'
    + '<button type="button" class="toast__close" aria-label="Dismiss notification">' + icon('x') + '</button>';
  const remove = () => node.remove();
  node.querySelector('button').addEventListener('click', remove);
  toastHost().appendChild(node);
  setTimeout(remove, type === 'error' ? 8000 : 4500);
}

/* ---------- Dialogs (native <dialog>: focus trap and Esc come for free) ---------- */
export function createDialog(innerHtml, labelledBy) {
  const dialog = document.createElement('dialog');
  dialog.className = 'dialog';
  dialog.setAttribute('aria-labelledby', labelledBy);
  dialog.innerHTML = innerHtml;
  document.body.appendChild(dialog);
  dialog.addEventListener('close', () => dialog.remove());
  dialog.addEventListener('mousedown', (event) => {
    if (event.target === dialog) {
      dialog.close();
    }
  });
  dialog.showModal();
  return dialog;
}

export function confirmDialog({ title, message, confirmLabel = 'Confirm', cancelLabel = 'Cancel', danger = false }) {
  return new Promise((resolve) => {
    const dialog = createDialog(
      '<div class="dialog__head"><h2 id="confirm-title">' + esc(title) + '</h2><p>' + esc(message) + '</p></div>'
      + '<div class="dialog__foot">'
      + '<button type="button" class="btn btn--ghost" data-act="cancel" autofocus>' + esc(cancelLabel) + '</button>'
      + '<button type="button" class="btn' + (danger ? ' btn--danger' : '') + '" data-act="ok">' + esc(confirmLabel) + '</button>'
      + '</div>',
      'confirm-title');
    let confirmed = false;
    dialog.addEventListener('click', (event) => {
      const action = event.target.closest('[data-act]');
      if (!action) {
        return;
      }
      confirmed = action.dataset.act === 'ok';
      dialog.close();
    });
    dialog.addEventListener('close', () => resolve(confirmed));
  });
}

/* ---------- Form errors (server fieldErrors map to inputs by name) ---------- */
export function clearFieldErrors(form) {
  form.querySelectorAll('.field').forEach((field) => {
    field.classList.remove('has-error');
    const message = field.querySelector('.field__error');
    if (message) {
      message.textContent = '';
    }
    const control = field.querySelector('input, select, textarea');
    if (control) {
      control.removeAttribute('aria-invalid');
    }
  });
}

export function showFieldError(form, name, message) {
  const field = form.querySelector('[data-field="' + name + '"]');
  if (!field) {
    return false;
  }
  field.classList.add('has-error');
  const holder = field.querySelector('.field__error');
  if (holder) {
    holder.textContent = message;
  }
  const control = field.querySelector('input, select, textarea');
  if (control) {
    control.setAttribute('aria-invalid', 'true');
  }
  return true;
}

export function focusFirstError(form) {
  const control = form.querySelector('.has-error input, .has-error select, .has-error textarea');
  if (control) {
    control.focus();
  }
}

/* ---------- Pager ---------- */
export function renderPager(element, page, { onPage, onSize, sizes = [10, 25, 50] }) {
  if (!page || page.totalElements === 0) {
    element.innerHTML = '';
    element.hidden = true;
    return;
  }
  element.hidden = false;
  const from = page.page * page.size + 1;
  const to = Math.min(from + page.content.length - 1, page.totalElements);
  const options = sizes
    .map((n) => '<option value="' + n + '"' + (n === page.size ? ' selected' : '') + '>' + n + ' per page</option>')
    .join('');
  element.innerHTML =
    '<span>Showing ' + from + '–' + to + ' of ' + page.totalElements + '</span>'
    + '<div class="pager__controls">'
    + '<label class="sr-only" for="page-size">Rows per page</label>'
    + '<select class="select" id="page-size">' + options + '</select>'
    + '<button type="button" class="btn btn--ghost btn--sm btn--icon" data-dir="-1" aria-label="Previous page"'
    + (page.first ? ' disabled' : '') + '>' + icon('left') + '</button>'
    + '<span aria-live="polite">Page ' + (page.page + 1) + ' of ' + Math.max(page.totalPages, 1) + '</span>'
    + '<button type="button" class="btn btn--ghost btn--sm btn--icon" data-dir="1" aria-label="Next page"'
    + (page.last ? ' disabled' : '') + '>' + icon('right') + '</button>'
    + '</div>';
  element.querySelector('#page-size').addEventListener('change', (e) => onSize(Number(e.target.value)));
  element.querySelectorAll('[data-dir]').forEach((button) => {
    button.addEventListener('click', () => onPage(page.page + Number(button.dataset.dir)));
  });
}

/* ---------- Theme toggle ---------- */
export function wireThemeToggle(button) {
  const render = () => {
    const dark = document.documentElement.getAttribute('data-theme') === 'dark';
    button.innerHTML = icon(dark ? 'sun' : 'moon');
    button.setAttribute('aria-pressed', dark ? 'true' : 'false');
    button.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
    button.title = dark ? 'Switch to light theme' : 'Switch to dark theme';
  };
  button.addEventListener('click', () => {
    const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try {
      localStorage.setItem('wt-theme', next);
    } catch (e) {
      /* storage unavailable: the choice just won't persist */
    }
    render();
  });
  render();
}

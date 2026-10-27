/*
 * Helpers for showing server validation results in forms.
 *
 * The API answers a failed validation with
 *   { "message": "...", "fieldErrors": { "email": "Enter a valid email address" } }
 * Each fieldErrors key matches a field wrapper marked data-field="<name>".
 */
import { clearFieldErrors, esc, focusFirstError, icon, showFieldError, toast } from './ui.js';

/** Puts an error banner into (or clears) a container element. */
export function setBanner(holder, message, kind = 'error') {
  if (!holder) {
    return;
  }
  holder.innerHTML = message
    ? '<div class="banner banner--' + kind + '">' + icon(kind === 'error' ? 'alert' : 'info')
      + '<span>' + esc(message) + '</span></div>'
    : '';
}

/**
 * Shows an ApiError on a form: field-level messages next to their inputs and, when there are
 * none (or some do not match an input), the general message in the banner.
 */
export function applyServerErrors(form, error, bannerHolder) {
  clearFieldErrors(form);
  setBanner(bannerHolder, '');
  let matched = 0;
  Object.entries(error.fieldErrors || {}).forEach(([name, message]) => {
    if (showFieldError(form, name, message)) {
      matched += 1;
    }
  });
  if (matched === 0) {
    setBanner(bannerHolder, error.message);
  } else {
    focusFirstError(form);
  }
}

/** Wires a password input and its show/hide button. One click toggles; the text and caret are kept. */
export function wirePasswordToggle(input, button) {
  button.type = 'button'; // never submits the form
  button.innerHTML = icon('eye');
  button.setAttribute('aria-label', 'Show password');
  button.setAttribute('aria-pressed', 'false');
  // Keep the caret in the password box while the button is pressed (so it is not lost on click).
  button.addEventListener('mousedown', (event) => event.preventDefault());
  button.addEventListener('click', () => {
    const hadFocus = document.activeElement === input;
    const start = input.selectionStart;
    const end = input.selectionEnd;
    const show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    button.innerHTML = icon(show ? 'eyeOff' : 'eye');
    button.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
    button.setAttribute('aria-pressed', show ? 'true' : 'false');
    if (hadFocus && start !== null) {
      input.setSelectionRange(start, end);
    }
  });
}

/** A random 14-character password that always satisfies the server rule (letter + digit, 8-72 chars). */
export function generatePassword(length = 14) {
  const letters = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
  const digits = '23456789';
  const symbols = '!@#$%&*?';
  const all = letters + digits + symbols;
  const pick = (set, bytes, i) => set[bytes[i] % set.length];
  const bytes = new Uint8Array(length + 3);
  crypto.getRandomValues(bytes);
  const chars = [pick(letters, bytes, 0), pick(digits, bytes, 1), pick(symbols, bytes, 2)];
  for (let i = 0; i < length - 3; i += 1) {
    chars.push(pick(all, bytes, i + 3));
  }
  // Fisher-Yates shuffle driven by fresh random bytes so the guaranteed characters are not always first
  const shuffle = new Uint8Array(chars.length);
  crypto.getRandomValues(shuffle);
  for (let i = chars.length - 1; i > 0; i -= 1) {
    const j = shuffle[i] % (i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}

/** Remember a message across a page change (the toast would otherwise vanish with the old page). */
export function flashNextPage(message) {
  try {
    sessionStorage.setItem('wt-flash', message);
  } catch (error) {
    /* storage unavailable: the message is simply skipped */
  }
}

/** Show (once) a message left by flashNextPage on the previous page. */
export function showPendingFlash() {
  try {
    const message = sessionStorage.getItem('wt-flash');
    if (message) {
      sessionStorage.removeItem('wt-flash');
      toast(message);
    }
  } catch (error) {
    /* ignore */
  }
}

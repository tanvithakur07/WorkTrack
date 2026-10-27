import { ApiError, api, post } from '../api.js';
import { applyServerErrors, setBanner, wirePasswordToggle } from '../forms.js';
import { clearFieldErrors, setBusy, showFieldError, focusFirstError, wireThemeToggle } from '../ui.js';

wireThemeToggle(document.getElementById('theme-toggle'));
wirePasswordToggle(document.getElementById('password'), document.getElementById('toggle-password'));
buildTapestry();

const form = document.getElementById('login-form');
const banner = document.getElementById('login-banner');

if (new URLSearchParams(location.search).get('expired') === '1') {
  setBanner(banner, 'Your session ended. Sign in again to continue.', 'info');
}

// Already signed in? Skip the form.
api('/api/auth/me', { redirectOn401: false })
  .then(() => location.replace('/dashboard.html'))
  .catch(() => { /* not signed in: stay on this page */ });

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearFieldErrors(form);
  setBanner(banner, '');

  const email = form.elements.email.value.trim();
  const password = form.elements.password.value;
  let invalid = false;
  if (!email) { showFieldError(form, 'email', 'Enter your work email'); invalid = true; }
  if (!password) { showFieldError(form, 'password', 'Enter your password'); invalid = true; }
  if (invalid) {
    focusFirstError(form);
    return;
  }

  const submit = document.getElementById('login-submit');
  setBusy(submit, true);
  try {
    await post('/api/auth/login', { email, password });
    try {
      sessionStorage.setItem('wt-signed-in', '1'); // only a flag, used to word the "session ended" message
    } catch (storageError) {
      /* ignore */
    }
    location.replace('/dashboard.html');
  } catch (error) {
    setBusy(submit, false);
    if (error instanceof ApiError) {
      applyServerErrors(form, error, banner);
      form.elements.password.value = '';
      form.elements.password.focus();
    } else {
      setBanner(banner, 'Something went wrong. Please try again.');
    }
  }
});

/** The decorative month on the right-hand side: weekdays are filled, weekends stay quiet. */
function buildTapestry() {
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth();
  const label = new Intl.DateTimeFormat(undefined, { month: 'long' }).format(now);
  document.getElementById('tapestry-month').innerHTML = label + ' <span>' + year + '</span>';

  const grid = document.getElementById('tapestry-grid');
  ['M', 'T', 'W', 'T', 'F', 'S', 'S'].forEach((letter) => {
    const cell = document.createElement('div');
    cell.className = 'tapestry__dow';
    cell.textContent = letter;
    grid.appendChild(cell);
  });

  const lead = (new Date(year, month, 1).getDay() + 6) % 7; // Monday first
  const days = new Date(year, month + 1, 0).getDate();
  const palette = ['teal', 'off', 'violet', 'coral', 'teal', 'off'];
  let index = 0;
  for (let i = 0; i < lead + days; i += 1) {
    const cell = document.createElement('div');
    cell.className = 'tapestry__cell';
    cell.style.setProperty('--i', String(index));
    index += 1;
    if (i >= lead) {
      const column = i % 7;
      const day = i - lead + 1;
      if (column >= 5) {
        cell.classList.add('tapestry__cell--weekend');
      } else if ((day * 7 + month) % 5 < 2) {
        cell.classList.add('tapestry__cell--' + palette[(day + month) % palette.length]);
      }
    } else {
      cell.style.visibility = 'hidden';
    }
    grid.appendChild(cell);
  }
}

import { ApiError, post } from '../api.js';
import { applyServerErrors, setBanner, wirePasswordToggle } from '../forms.js';
import { clearFieldErrors, setBusy, showFieldError, focusFirstError, wireThemeToggle } from '../ui.js';

wireThemeToggle(document.getElementById('theme-toggle'));
wirePasswordToggle(document.getElementById('password'), document.getElementById('toggle-password'));

const form = document.getElementById('setup-form');
const banner = document.getElementById('setup-banner');

// The secret travels after "#": browsers never send it to the server in the request line or in a Referer.
const token = new URLSearchParams(location.hash.replace(/^#/, '')).get('token');
// Remove it from the address bar and history as soon as it is read.
if (location.hash) {
  history.replaceState(null, '', location.pathname);
}

if (!token) {
  setBanner(banner, 'This link is incomplete. Open the link from your invitation e-mail again, or ask your administrator to send a new invitation.');
  form.querySelectorAll('input, button[type="submit"]').forEach((el) => { el.disabled = true; });
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearFieldErrors(form);
  setBanner(banner, '');
  const password = form.elements.password.value;
  const confirm = form.elements.confirm.value;
  let invalid = false;
  if (!password) { showFieldError(form, 'password', 'Choose a password'); invalid = true; }
  if (password && confirm !== password) { showFieldError(form, 'confirm', 'The two passwords do not match'); invalid = true; }
  if (invalid) {
    focusFirstError(form);
    return;
  }
  const submit = document.getElementById('setup-submit');
  setBusy(submit, true);
  try {
    await post('/api/auth/setup-password', { token, password });
    form.hidden = true;
    setBanner(banner, 'Your password is saved. Taking you to sign in…', 'info');
    setTimeout(() => location.replace('/login.html'), 1500);
  } catch (error) {
    setBusy(submit, false);
    if (error instanceof ApiError) {
      applyServerErrors(form, error, banner);
    } else {
      setBanner(banner, 'Something went wrong. Please try again.');
    }
  }
});

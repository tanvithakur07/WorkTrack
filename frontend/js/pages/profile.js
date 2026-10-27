import { ApiError, post } from '../api.js';
import { mountShell } from '../shell.js';
import { applyServerErrors, setBanner, wirePasswordToggle } from '../forms.js';
import {
  avatar, clearFieldErrors, employmentChip, esc, focusFirstError, formatDate, formatDateTime, roleChip, setBusy,
  showFieldError, toast
} from '../ui.js';

const shell = await mountShell({ page: 'profile', title: 'My profile', subtitle: 'Your details and password.' });

if (shell) {
  const { user, main } = shell;

  main.innerHTML =
    '<section class="panel" aria-label="Your details">'
    + '<div class="profile-card">' + avatar(user.fullName, 'lg')
    + '<div><h2>' + esc(user.fullName) + '</h2><p class="muted">' + esc(user.jobTitle) + ' · ' + esc(user.department) + '</p></div>'
    + '<div class="toolbar__spacer"></div><div>' + employmentChip(user.status) + ' ' + roleChip(user.role) + '</div></div>'
    + '<div class="panel__body"><dl class="facts">'
    + '<div><dt>Employee ID</dt><dd>' + esc(user.employeeCode) + '</dd></div>'
    + '<div><dt>Email</dt><dd>' + esc(user.email) + '</dd></div>'
    + '<div><dt>Department</dt><dd>' + esc(user.department) + '</dd></div>'
    + '<div><dt>Joined</dt><dd>' + esc(formatDate(user.joiningDate)) + '</dd></div>'
    + '<div><dt>Added to WorkTrack</dt><dd>' + esc(formatDateTime(user.createdAt)) + '</dd></div>'
    + '</dl><p class="field__hint">To change your name, email or department, ask an administrator.</p></div></section>'

    + '<section class="panel" aria-labelledby="pw-title"><div class="panel__head"><div><h2 id="pw-title">Change password</h2>'
    + '<p>You’ll stay signed in on this device.</p></div></div>'
    + '<form id="password-form" novalidate><div class="panel__body"><div id="pw-banner" role="alert"></div>'
    + '<div class="form-grid">'
    + passwordField('currentPassword', 'Current password', 'current-password', 'span-2')
    + passwordField('newPassword', 'New password', 'new-password', '', '8 to 72 characters with at least one letter and one number.')
    + passwordField('confirmPassword', 'Confirm new password', 'new-password')
    + '</div></div><div class="panel__body form-actions"><button type="submit" class="btn" id="pw-save">Change password</button></div></form>'
    + '</section>';

  const form = document.getElementById('password-form');
  const banner = document.getElementById('pw-banner');
  form.querySelectorAll('.input-group').forEach((group) => {
    wirePasswordToggle(group.querySelector('input'), group.querySelector('button'));
  });

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearFieldErrors(form);
    setBanner(banner, '');
    const currentPassword = form.elements.currentPassword.value;
    const newPassword = form.elements.newPassword.value;
    const confirmPassword = form.elements.confirmPassword.value;

    let invalid = false;
    if (!currentPassword) { showFieldError(form, 'currentPassword', 'Enter your current password'); invalid = true; }
    if (!newPassword) { showFieldError(form, 'newPassword', 'Enter a new password'); invalid = true; }
    if (newPassword && confirmPassword !== newPassword) {
      showFieldError(form, 'confirmPassword', 'The two passwords don’t match');
      invalid = true;
    }
    if (invalid) {
      focusFirstError(form);
      return;
    }

    const save = document.getElementById('pw-save');
    setBusy(save, true);
    try {
      await post('/api/auth/change-password', { currentPassword, newPassword });
      form.reset();
      toast('Password changed.');
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.status === 400 && !Object.keys(error.fieldErrors).length && /current password/i.test(error.message)) {
          showFieldError(form, 'currentPassword', error.message);
          focusFirstError(form);
        } else {
          applyServerErrors(form, error, banner);
        }
      } else {
        setBanner(banner, 'Something went wrong. Please try again.');
      }
    } finally {
      setBusy(save, false);
    }
  });

  function passwordField(name, label, autocomplete, extraClass = '', hint = '') {
    return '<div class="field ' + extraClass + '" data-field="' + name + '"><label for="' + name + '">' + label + '</label>'
      + '<div class="input-group"><input class="input" id="' + name + '" name="' + name + '" type="password" maxlength="72" autocomplete="' + autocomplete + '">'
      + '<button type="button" class="btn btn--ghost btn--icon btn--sm input-group__btn"></button></div>'
      + (hint ? '<p class="field__hint">' + hint + '</p>' : '') + '<p class="field__error"></p></div>';
  }
}

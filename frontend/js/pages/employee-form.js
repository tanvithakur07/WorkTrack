import { ApiError, get, post, put } from '../api.js';
import { mountShell } from '../shell.js';
import { applyServerErrors, flashNextPage, generatePassword, setBanner, wirePasswordToggle } from '../forms.js';
import {
  clearFieldErrors, errorState, focusFirstError, icon, setBusy, showFieldError, skeletonBlock, toast, toIsoDate
} from '../ui.js';

const editingId = new URLSearchParams(location.search).get('id');
const editing = editingId !== null && /^\d+$/.test(editingId);

const shell = await mountShell({
  page: 'employees',
  title: editing ? 'Edit employee' : 'Add employee',
  subtitle: editing ? 'Update their details, role or status.' : 'Create an account they can sign in with.',
  requires: 'ADMIN'
});

if (shell) {
  const { user, main } = shell;

  async function start() {
    main.innerHTML = '<div class="panel">' + skeletonBlock(6) + '</div>';
    let person = null;
    try {
      if (editing) {
        person = await get('/api/employees/' + editingId);
      }
    } catch (error) {
      main.innerHTML = '<div class="panel">' + errorState(
        error.status === 404 ? 'That employee doesn’t exist any more.' : error.message) + '</div>';
      const retry = main.querySelector('[data-retry]');
      if (retry) {
        retry.addEventListener('click', start);
      }
      return;
    }
    render(person);
  }

  function render(person) {
    const isSelf = person !== null && person.id === user.id;
    main.innerHTML =
      '<section class="panel"><form id="employee-form" novalidate>'
      + '<div class="panel__body"><div id="form-banner" role="alert"></div>'
      + '<div class="form-grid">'
      + field('fullName', 'Full name', '<input class="input" id="fullName" name="fullName" maxlength="120" autocomplete="off" required>')
      + field('email', 'Work email', '<input class="input" id="email" name="email" type="email" maxlength="180" autocomplete="off" required>')
      + field('department', 'Department',
        '<input class="input" id="department" name="department" maxlength="80" list="department-list" autocomplete="off" required>'
        + '<datalist id="department-list"></datalist>')
      + field('jobTitle', 'Job title', '<input class="input" id="jobTitle" name="jobTitle" maxlength="100" autocomplete="off" required>')
      + field('joiningDate', 'Joining date', '<input class="input" id="joiningDate" name="joiningDate" type="date" required>')
      + field('role', 'Role',
        '<select class="select" id="role" name="role"><option value="EMPLOYEE">Employee</option><option value="ADMIN">Administrator</option></select>',
        isSelf ? 'You can’t change your own role.' : 'Administrators can manage employees and decide leave requests.')
      + field('status', 'Status',
        '<select class="select" id="status" name="status"><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option></select>',
        isSelf ? 'You can’t deactivate your own account.' : 'Inactive people can’t sign in.')
      + '<div class="field span-2" data-field="password"><label for="password">'
      + (person ? 'Reset password (optional)' : 'Temporary password') + '</label>'
      + '<div class="input-group"><input class="input" id="password" name="password" type="password" autocomplete="new-password" maxlength="72"'
      + (person ? '' : ' required') + '>'
      + '<button type="button" class="btn btn--ghost btn--icon btn--sm input-group__btn" id="toggle-password"></button></div>'
      + '<p class="field__hint">8 to 72 characters with at least one letter and one number. '
      + (person ? 'Leave blank to keep the current password.'
        : 'An invitation e-mail with a one-time link to choose their own password is sent automatically. The temporary password is never e-mailed; share it only if you need to, and securely.')
      + '</p><p class="field__error"></p>'
      + '<div><button type="button" class="btn btn--ghost btn--sm" id="generate-password">' + icon('dice') + '<span>Generate a strong password</span></button></div></div>'
      + '</div></div>'
      + '<div class="panel__body form-actions">'
      + '<a class="btn btn--ghost" href="/employees.html">Cancel</a>'
      + (person && person.status === 'ACTIVE' && !isSelf
        ? '<button type="button" class="btn btn--ghost" id="resend-invitation">' + icon('inbox') + '<span>Resend invitation</span></button>' : '')
      + '<button type="submit" class="btn" id="save-btn">' + (person ? 'Save changes' : 'Add employee') + '</button>'
      + '</div></form></section>';

    const form = document.getElementById('employee-form');
    const banner = document.getElementById('form-banner');
    const passwordInput = form.elements.password;
    wirePasswordToggle(passwordInput, document.getElementById('toggle-password'));

    if (person) {
      form.elements.fullName.value = person.fullName;
      form.elements.email.value = person.email;
      form.elements.department.value = person.department;
      form.elements.jobTitle.value = person.jobTitle;
      form.elements.joiningDate.value = person.joiningDate;
      form.elements.role.value = person.role;
      form.elements.status.value = person.status;
    } else {
      form.elements.joiningDate.value = toIsoDate(new Date());
    }
    if (isSelf) {
      form.elements.role.disabled = true;
      form.elements.status.disabled = true;
    }

    get('/api/employees/departments').then((departments) => {
      const list = document.getElementById('department-list');
      departments.forEach((name) => {
        const option = document.createElement('option');
        option.value = name;
        list.appendChild(option);
      });
    }).catch(() => { /* suggestions are optional */ });

    document.getElementById('generate-password').addEventListener('click', () => {
      passwordInput.value = generatePassword();
      if (passwordInput.type === 'password') {
        document.getElementById('toggle-password').click(); // reveal it so it can be copied
      }
      passwordInput.focus();
      passwordInput.select();
    });

    const resend = document.getElementById('resend-invitation');
    if (resend) {
      resend.addEventListener('click', async () => {
        setBusy(resend, true);
        try {
          const result = await post('/api/employees/' + person.id + '/invitation');
          toast(invitationMessage(result && result.invitation, person.email), result && result.invitation === 'QUEUED' ? 'success' : 'error');
        } catch (error) {
          toast(error.message || 'Could not queue the invitation.', 'error');
        } finally {
          setBusy(resend, false);
        }
      });
    }

    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      clearFieldErrors(form);
      setBanner(banner, '');

      const body = {
        fullName: form.elements.fullName.value.trim(),
        email: form.elements.email.value.trim(),
        department: form.elements.department.value.trim(),
        jobTitle: form.elements.jobTitle.value.trim(),
        joiningDate: form.elements.joiningDate.value,
        role: form.elements.role.value,
        status: form.elements.status.value
      };
      const password = passwordInput.value;
      if (password) {
        body.password = password;
      }

      const required = [
        ['fullName', 'Enter their full name'], ['email', 'Enter their work email'],
        ['department', 'Enter a department'], ['jobTitle', 'Enter a job title'], ['joiningDate', 'Pick the joining date']
      ];
      let invalid = false;
      required.forEach(([name, message]) => {
        if (!body[name]) {
          showFieldError(form, name, message);
          invalid = true;
        }
      });
      if (!person && !password) {
        showFieldError(form, 'password', 'Set a temporary password');
        invalid = true;
      }
      if (invalid) {
        focusFirstError(form);
        return;
      }

      const save = document.getElementById('save-btn');
      setBusy(save, true);
      try {
        if (person) {
          await put('/api/employees/' + person.id, body);
          flashNextPage(body.fullName + ' is updated.');
        } else {
          const created = await post('/api/employees', body);
          flashNextPage(body.fullName + ' is added. ' + invitationMessage(created && created.invitation, body.email));
        }
        location.assign('/employees.html');
      } catch (error) {
        setBusy(save, false);
        if (error instanceof ApiError) {
          if (error.status === 409 && !Object.keys(error.fieldErrors).length && /email/i.test(error.message)) {
            showFieldError(form, 'email', error.message);
            focusFirstError(form);
          } else {
            applyServerErrors(form, error, banner);
          }
        } else {
          setBanner(banner, 'Something went wrong. Please try again.');
        }
      }
    });
  }

  /** What the server did with the invitation. "Queued" means waiting to be sent, not delivered. */
  function invitationMessage(status, email) {
    switch (status) {
      case 'QUEUED': return 'An invitation to ' + email + ' is queued. See Email queue for its delivery status.';
      case 'ALREADY_QUEUED': return 'An invitation to ' + email + ' is already waiting to be sent.';
      case 'SKIPPED_INVALID_EMAIL': return 'No invitation was queued: the e-mail address looks invalid.';
      case 'SKIPPED_INACTIVE_ACCOUNT': return 'No invitation was queued: the account is inactive.';
      default: return 'No invitation was queued.';
    }
  }

  function field(name, label, control, hint = '') {
    return '<div class="field" data-field="' + name + '"><label for="' + name + '">' + label + '</label>'
      + control + (hint ? '<p class="field__hint">' + hint + '</p>' : '') + '<p class="field__error"></p></div>';
  }

  start();
}

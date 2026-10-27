/*
 * Everything a person can do to a leave request, shared by the dashboard and the leave page:
 * send a request, look at one, approve, reject, cancel.
 *
 * The buttons shown here are a convenience. The server decides who may do what and answers
 * 403 / 409 when a rule is broken; those messages are shown to the person as they come.
 */
import { ApiError, patch, post } from './api.js';
import { applyServerErrors, setBanner } from './forms.js';
import {
  clearFieldErrors, confirmDialog, countWorkingDays, createDialog, esc, formatDateTime,
  formatRange, icon, person, plural, setBusy, showFieldError, focusFirstError, statusChip, toast,
  toIsoDate, typeLabel, typeTag
} from './ui.js';

const TYPES = ['CASUAL', 'SICK', 'EARNED'];
const SICK_BACKDATE_DAYS = 7;

function addDays(date, days) {
  const copy = new Date(date);
  copy.setDate(copy.getDate() + days);
  return copy;
}

function bindClose(dialog) {
  dialog.querySelectorAll('[data-act="close"]').forEach((button) => {
    button.addEventListener('click', () => dialog.close());
  });
}

/** After a failed action: tell the person why, and say whether the list should be refreshed. */
function reportFailure(error) {
  const message = error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
  toast(message, 'error');
  // 404 / 409 mean the request changed under us (someone else decided it, it was cancelled...).
  return error instanceof ApiError && (error.status === 404 || error.status === 409);
}

/* ---------------------------------------------------------------------------
   Send a request
   --------------------------------------------------------------------------- */
export function openLeaveForm() {
  return new Promise((resolve) => {
    const dialog = createDialog(
      '<form id="leave-form" novalidate>'
      + '<div class="dialog__head"><h2 id="leave-form-title">Request leave</h2>'
      + '<p>Saturdays and Sundays don’t use up leave days.</p></div>'
      + '<div class="dialog__body">'
      + '<div id="leave-form-banner" role="alert"></div>'
      + '<div class="field" data-field="leaveType"><label for="lf-type">Type of leave</label>'
      + '<select class="select" id="lf-type" name="leaveType">'
      + TYPES.map((type) => '<option value="' + type + '">' + esc(typeLabel(type)) + '</option>').join('')
      + '</select><p class="field__hint" id="lf-type-hint"></p><p class="field__error"></p></div>'
      + '<div class="form-grid">'
      + '<div class="field" data-field="startDate"><label for="lf-start">First day</label>'
      + '<input class="input" type="date" id="lf-start" name="startDate" required><p class="field__error"></p></div>'
      + '<div class="field" data-field="endDate"><label for="lf-end">Last day</label>'
      + '<input class="input" type="date" id="lf-end" name="endDate" required><p class="field__error"></p></div>'
      + '</div>'
      + '<div class="day-preview" id="lf-preview" aria-live="polite"></div>'
      + '<div class="field" data-field="reason"><label for="lf-reason">Reason</label>'
      + '<textarea class="textarea" id="lf-reason" name="reason" maxlength="500" required></textarea>'
      + '<p class="field__hint">Administrators can see this. 5 to 500 characters.</p><p class="field__error"></p></div>'
      + '</div>'
      + '<div class="dialog__foot">'
      + '<button type="button" class="btn btn--ghost" data-act="close">Cancel</button>'
      + '<button type="submit" class="btn">Send request</button></div>'
      + '</form>',
      'leave-form-title');

    const form = dialog.querySelector('#leave-form');
    const banner = dialog.querySelector('#leave-form-banner');
    const typeSelect = form.elements.leaveType;
    const start = form.elements.startDate;
    const end = form.elements.endDate;
    const preview = dialog.querySelector('#lf-preview');
    const hint = dialog.querySelector('#lf-type-hint');
    const today = new Date();
    let saved = false;

    function refreshRules() {
      const sick = typeSelect.value === 'SICK';
      start.min = toIsoDate(sick ? addDays(today, -SICK_BACKDATE_DAYS) : today);
      end.min = start.value || start.min;
      hint.textContent = sick
        ? 'Sick leave can start up to ' + SICK_BACKDATE_DAYS + ' days ago.'
        : 'This type of leave must start today or later.';
    }

    function refreshPreview() {
      if (!start.value || !end.value) {
        preview.innerHTML = icon('calendar') + '<span>Pick your dates to see how many days this uses.</span>';
        return;
      }
      if (end.value < start.value) {
        preview.innerHTML = icon('alert') + '<span>The last day is before the first day.</span>';
        return;
      }
      const days = countWorkingDays(start.value, end.value);
      preview.innerHTML = days === 0
        ? icon('info') + '<span>These dates are all on a weekend. Pick at least one weekday.</span>'
        : icon('calendar') + '<span><strong>' + days + '</strong> '
          + (days === 1 ? 'working day' : 'working days') + ' will be used.</span>';
    }

    typeSelect.addEventListener('change', () => { refreshRules(); refreshPreview(); });
    start.addEventListener('change', () => {
      end.min = start.value || start.min;
      if (end.value && start.value && end.value < start.value) {
        end.value = start.value;
      }
      refreshPreview();
    });
    end.addEventListener('change', refreshPreview);
    refreshRules();
    refreshPreview();
    bindClose(dialog);

    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      clearFieldErrors(form);
      setBanner(banner, '');

      const body = {
        leaveType: typeSelect.value,
        startDate: start.value,
        endDate: end.value,
        reason: form.elements.reason.value.trim()
      };
      let invalid = false;
      if (!body.startDate) { showFieldError(form, 'startDate', 'Pick the first day'); invalid = true; }
      if (!body.endDate) { showFieldError(form, 'endDate', 'Pick the last day'); invalid = true; }
      if (body.reason.length < 5) { showFieldError(form, 'reason', 'Tell us why in at least 5 characters'); invalid = true; }
      if (invalid) {
        focusFirstError(form);
        return;
      }

      const submit = form.querySelector('[type="submit"]');
      setBusy(submit, true);
      try {
        await post('/api/leaves', body);
        saved = true;
        toast('Request sent. You’ll see the decision here.');
        dialog.close();
      } catch (error) {
        if (error instanceof ApiError) {
          applyServerErrors(form, error, banner);
        } else {
          setBanner(banner, 'Something went wrong. Please try again.');
        }
      } finally {
        setBusy(submit, false);
      }
    });

    dialog.addEventListener('close', () => resolve(saved));
  });
}

/* ---------------------------------------------------------------------------
   Decisions
   --------------------------------------------------------------------------- */
/** @returns {Promise<boolean>} true when the list should be refreshed */
export async function approveLeave(leave) {
  const confirmed = await confirmDialog({
    title: 'Approve this request?',
    message: leave.employeeName + ' will be marked as away ' + formatRange(leave.startDate, leave.endDate)
      + ' (' + plural(leave.days, 'working day') + ').',
    confirmLabel: 'Approve'
  });
  if (!confirmed) {
    return false;
  }
  try {
    await patch('/api/leaves/' + leave.id + '/approve');
    toast('Approved. ' + leave.employeeName + ' is away ' + formatRange(leave.startDate, leave.endDate) + '.');
    return true;
  } catch (error) {
    return reportFailure(error);
  }
}

export function rejectLeave(leave) {
  return new Promise((resolve) => {
    const dialog = createDialog(
      '<form id="reject-form" novalidate>'
      + '<div class="dialog__head"><h2 id="reject-title">Reject this request?</h2>'
      + '<p>' + esc(leave.employeeName) + ' will see your reason next to the request.</p></div>'
      + '<div class="dialog__body"><div id="reject-banner" role="alert"></div>'
      + '<div class="field" data-field="reason"><label for="rj-reason">Reason for rejecting</label>'
      + '<textarea class="textarea" id="rj-reason" name="reason" maxlength="500" required></textarea>'
      + '<p class="field__hint">3 to 500 characters.</p><p class="field__error"></p></div></div>'
      + '<div class="dialog__foot">'
      + '<button type="button" class="btn btn--ghost" data-act="close">Cancel</button>'
      + '<button type="submit" class="btn btn--danger">Reject</button></div>'
      + '</form>',
      'reject-title');
    const form = dialog.querySelector('#reject-form');
    const banner = dialog.querySelector('#reject-banner');
    let changed = false;
    bindClose(dialog);

    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      clearFieldErrors(form);
      const reason = form.elements.reason.value.trim();
      if (reason.length < 3) {
        showFieldError(form, 'reason', 'Add a reason of at least 3 characters');
        focusFirstError(form);
        return;
      }
      const submit = form.querySelector('[type="submit"]');
      setBusy(submit, true);
      try {
        await patch('/api/leaves/' + leave.id + '/reject', { reason });
        changed = true;
        toast('Rejected. ' + leave.employeeName + ' can see your reason.');
        dialog.close();
      } catch (error) {
        if (error instanceof ApiError && error.status === 400) {
          applyServerErrors(form, error, banner);
        } else {
          changed = reportFailure(error);
          if (changed) {
            dialog.close();
          }
        }
      } finally {
        setBusy(submit, false);
      }
    });

    dialog.addEventListener('close', () => resolve(changed));
  });
}

export async function cancelLeave(leave) {
  const confirmed = await confirmDialog({
    title: 'Cancel this request?',
    message: 'Your request for ' + formatRange(leave.startDate, leave.endDate)
      + ' will be withdrawn. You can send a new one any time.',
    confirmLabel: 'Cancel request',
    cancelLabel: 'Keep request',
    danger: true
  });
  if (!confirmed) {
    return false;
  }
  try {
    await patch('/api/leaves/' + leave.id + '/cancel');
    toast('Request cancelled.');
    return true;
  } catch (error) {
    return reportFailure(error);
  }
}

/* ---------------------------------------------------------------------------
   Details
   --------------------------------------------------------------------------- */
export function leavePermissions(leave, user) {
  const isOwner = leave.employeeId === user.id;
  const pending = leave.status === 'PENDING';
  return {
    isOwner,
    canReview: user.role === 'ADMIN' && pending && !isOwner,
    canCancel: isOwner && pending,
    reviewBlockedBySelf: user.role === 'ADMIN' && pending && isOwner
  };
}

export function openLeaveDetail(leave, { user, onChanged }) {
  const { canReview, canCancel, reviewBlockedBySelf } = leavePermissions(leave, user);
  const isAdmin = user.role === 'ADMIN';

  const reviewedLine = leave.reviewedAt
    ? '<div><dt>' + (leave.status === 'APPROVED' ? 'Approved by' : 'Decided by') + '</dt><dd>'
      + esc(leave.reviewedByName || '—') + ' on ' + esc(formatDateTime(leave.reviewedAt)) + '</dd></div>'
    : '';

  const dialog = createDialog(
    '<div class="dialog__head"><h2 id="leave-detail-title">Leave request</h2></div>'
    + '<div class="dialog__body">'
    + (isAdmin ? person(leave.employeeName, leave.department + ' · ' + leave.employeeEmail) : '')
    + '<div>' + statusChip(leave.status) + '</div>'
    + '<dl class="facts">'
    + '<div><dt>Type</dt><dd>' + typeTag(leave.leaveType) + '</dd></div>'
    + '<div><dt>Dates</dt><dd>' + esc(formatRange(leave.startDate, leave.endDate)) + '</dd></div>'
    + '<div><dt>Working days</dt><dd>' + leave.days + '</dd></div>'
    + '<div><dt>Sent</dt><dd>' + esc(formatDateTime(leave.createdAt)) + '</dd></div>'
    + reviewedLine
    + '</dl>'
    + '<div><p class="muted">Reason</p><p>' + esc(leave.reason) + '</p></div>'
    + (leave.status === 'REJECTED' && leave.rejectionReason
      ? '<div class="banner banner--error">' + icon('x') + '<span><strong>Rejected:</strong> '
        + esc(leave.rejectionReason) + '</span></div>'
      : '')
    + (reviewBlockedBySelf
      ? '<div class="banner banner--info">' + icon('info')
        + '<span>You can’t decide your own request. Another administrator needs to review it.</span></div>'
      : '')
    + '</div>'
    + '<div class="dialog__foot">'
    + '<button type="button" class="btn btn--ghost" data-act="close">Close</button>'
    + (canCancel ? '<button type="button" class="btn btn--ghost" data-act="cancel">Cancel request</button>' : '')
    + (canReview ? '<button type="button" class="btn btn--danger" data-act="reject">Reject</button>'
      + '<button type="button" class="btn" data-act="approve">Approve</button>' : '')
    + '</div>',
    'leave-detail-title');

  bindClose(dialog);
  const run = (fn) => async () => {
    const refresh = await fn(leave);
    if (refresh) {
      dialog.close();
      if (onChanged) {
        onChanged();
      }
    }
  };
  const approve = dialog.querySelector('[data-act="approve"]');
  const reject = dialog.querySelector('[data-act="reject"]');
  const cancel = dialog.querySelector('[data-act="cancel"]');
  if (approve) { approve.addEventListener('click', run(approveLeave)); }
  if (reject) { reject.addEventListener('click', run(rejectLeave)); }
  if (cancel) { cancel.addEventListener('click', run(cancelLeave)); }
  return dialog;
}

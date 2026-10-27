/*
 * Everything a person can do to a daily work entry, shared by the work page and the dashboard:
 * write or edit an entry, read one, delete a draft.
 *
 * The buttons shown here are a convenience. The server decides who may do what (owner only for
 * edit/delete, drafts only for delete) and answers 400 / 403 / 409 with a message that is shown as it comes.
 */
import { ApiError, del, post, put } from './api.js';
import { applyServerErrors, setBanner } from './forms.js';
import {
  clearFieldErrors, confirmDialog, createDialog, esc, focusFirstError, formatDate, formatDateTime, icon, person,
  setBusy, showFieldError, toast, toIsoDate
} from './ui.js';

export const WORK_STATUSES = ['DRAFT', 'IN_PROGRESS', 'COMPLETED', 'BLOCKED'];
const STATUS_LABEL = { DRAFT: 'Draft', IN_PROGRESS: 'In progress', COMPLETED: 'Completed', BLOCKED: 'Blocked' };
const STATUS_ICON = { DRAFT: 'edit', IN_PROGRESS: 'clock', COMPLETED: 'check', BLOCKED: 'alert' };
const MAX_BACKDATE_DAYS = 30;

export const workStatusLabel = (status) => STATUS_LABEL[status] || status;

export function workChip(status) {
  return '<span class="chip chip--' + esc(String(status).toLowerCase()) + '">'
    + icon(STATUS_ICON[status] || 'info') + esc(workStatusLabel(status)) + '</span>';
}

/** 7.5 -> "7.5 h", 8.00 -> "8 h" */
export function formatHours(value) {
  const number = Number(value || 0);
  return (Math.round(number * 100) / 100) + ' h';
}

function bindClose(dialog) {
  dialog.querySelectorAll('[data-act="close"]').forEach((button) => {
    button.addEventListener('click', () => dialog.close());
  });
}

function reportFailure(error) {
  toast(error instanceof ApiError ? error.message : 'Something went wrong. Please try again.', 'error');
  return error instanceof ApiError && (error.status === 404 || error.status === 409);
}

/* ---------------------------------------------------------------------------
   Write or edit an entry. Pass { log } to edit. Resolves to true when something was saved.
   There is no employee field: the server takes the author from the signed-in session.
   --------------------------------------------------------------------------- */
export function openWorkForm({ log = null, date = null } = {}) {
  return new Promise((resolve) => {
    const editing = Boolean(log);
    const today = new Date();
    const earliest = new Date(today);
    earliest.setDate(earliest.getDate() - MAX_BACKDATE_DAYS);

    const dialog = createDialog(
      '<form id="work-form" novalidate>'
      + '<div class="dialog__head"><h2 id="work-form-title">' + (editing ? 'Edit work entry' : 'Log your work') + '</h2>'
      + '<p>One entry per task. You can add several for the same day.</p></div>'
      + '<div class="dialog__body">'
      + '<div id="work-form-banner" role="alert"></div>'
      + '<div class="form-grid">'
      + '<div class="field" data-field="workDate"><label for="wf-date">Date</label>'
      + '<input class="input" type="date" id="wf-date" name="workDate" required min="' + toIsoDate(earliest)
      + '" max="' + toIsoDate(today) + '"><p class="field__error"></p></div>'
      + '<div class="field" data-field="hoursSpent"><label for="wf-hours">Hours spent</label>'
      + '<input class="input" type="number" id="wf-hours" name="hoursSpent" required min="0.25" max="24" step="0.25" inputmode="decimal">'
      + '<p class="field__error"></p></div></div>'
      + '<div class="field" data-field="taskTitle"><label for="wf-title">Task</label>'
      + '<input class="input" type="text" id="wf-title" name="taskTitle" maxlength="150" required autocomplete="off">'
      + '<p class="field__error"></p></div>'
      + '<div class="field" data-field="status"><label for="wf-status">Status</label>'
      + '<select class="select" id="wf-status" name="status">'
      + WORK_STATUSES.map((s) => '<option value="' + s + '">' + esc(workStatusLabel(s)) + '</option>').join('')
      + '</select><p class="field__hint">Drafts stay private and are not counted until you change the status.</p>'
      + '<p class="field__error"></p></div>'
      + '<div class="field" data-field="description"><label for="wf-description">What did you do?</label>'
      + '<textarea class="textarea" id="wf-description" name="description" maxlength="2000" rows="5" required></textarea>'
      + '<p class="field__hint" id="wf-count" aria-live="polite"></p><p class="field__error"></p></div>'
      + '</div>'
      + '<div class="dialog__foot">'
      + '<button type="button" class="btn btn--ghost" data-act="close">Cancel</button>'
      + '<button type="submit" class="btn">' + (editing ? 'Save changes' : 'Save entry') + '</button></div>'
      + '</form>',
      'work-form-title');

    const form = dialog.querySelector('#work-form');
    const banner = dialog.querySelector('#work-form-banner');
    const counter = dialog.querySelector('#wf-count');
    let saved = false;

    form.elements.workDate.value = editing ? log.workDate : (date || toIsoDate(today));
    form.elements.status.value = editing ? log.status : 'COMPLETED';
    if (editing) {
      form.elements.taskTitle.value = log.taskTitle;
      form.elements.hoursSpent.value = Number(log.hoursSpent);
      form.elements.description.value = log.description;
      // An old entry keeps its date unless the person changes it, so do not block it with the 30-day window.
      if (log.workDate < toIsoDate(earliest)) {
        form.elements.workDate.removeAttribute('min');
      }
    }
    const refreshCount = () => {
      counter.textContent = form.elements.description.value.length + ' / 2000 characters';
    };
    form.elements.description.addEventListener('input', refreshCount);
    refreshCount();
    bindClose(dialog);

    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      clearFieldErrors(form);
      setBanner(banner, '');

      const body = {
        workDate: form.elements.workDate.value,
        taskTitle: form.elements.taskTitle.value.trim(),
        description: form.elements.description.value.trim(),
        hoursSpent: form.elements.hoursSpent.value === '' ? null : Number(form.elements.hoursSpent.value),
        status: form.elements.status.value
      };
      let invalid = false;
      const fail = (name, message) => { showFieldError(form, name, message); invalid = true; };
      if (!body.workDate) { fail('workDate', 'Pick the date you worked'); }
      else if (body.workDate > toIsoDate(today)) { fail('workDate', 'The date can’t be in the future'); }
      if (body.taskTitle.length < 3) { fail('taskTitle', 'Name the task in at least 3 characters'); }
      if (body.hoursSpent === null || Number.isNaN(body.hoursSpent)) { fail('hoursSpent', 'Enter the hours you spent'); }
      else if (body.hoursSpent < 0.25 || body.hoursSpent > 24) { fail('hoursSpent', 'Hours must be between 0.25 and 24'); }
      else if (Math.round(body.hoursSpent * 100) !== body.hoursSpent * 100) { fail('hoursSpent', 'Use at most 2 decimal places'); }
      if (body.description.length < 5) { fail('description', 'Describe what you did in at least 5 characters'); }
      if (invalid) {
        focusFirstError(form);
        return;
      }

      const submit = form.querySelector('[type="submit"]');
      setBusy(submit, true);
      try {
        if (editing) {
          await put('/api/work-logs/' + log.id, body);
          toast('Entry updated.');
        } else {
          await post('/api/work-logs', body);
          toast(body.status === 'DRAFT' ? 'Draft saved.' : 'Entry saved.');
        }
        saved = true;
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
   Delete a draft
   --------------------------------------------------------------------------- */
/** @returns {Promise<boolean>} true when the list should be refreshed */
export async function deleteWorkLog(log) {
  const ok = await confirmDialog({
    title: 'Delete this draft?',
    message: '“' + log.taskTitle + '” will be removed for good. Only drafts can be deleted.',
    confirmLabel: 'Delete draft',
    danger: true
  });
  if (!ok) {
    return false;
  }
  try {
    await del('/api/work-logs/' + log.id);
    toast('Draft deleted.');
    return true;
  } catch (error) {
    return reportFailure(error);
  }
}

export function workPermissions(log, user) {
  const own = log.employeeId === user.id;
  return { canEdit: own, canDelete: own && log.status === 'DRAFT' };
}

/* ---------------------------------------------------------------------------
   Read one entry in full
   --------------------------------------------------------------------------- */
export function openWorkDetail(log, { user, onChanged }) {
  const { canEdit, canDelete } = workPermissions(log, user);
  const showPerson = log.employeeId !== user.id;

  const dialog = createDialog(
    '<div class="dialog__head"><h2 id="work-detail-title">' + esc(log.taskTitle) + '</h2></div>'
    + '<div class="dialog__body">'
    + (showPerson ? person(log.employeeName, log.department + ' · ' + log.employeeEmail) : '')
    + '<div>' + workChip(log.status) + '</div>'
    + '<dl class="facts">'
    + '<div><dt>Date</dt><dd>' + esc(formatDate(log.workDate)) + '</dd></div>'
    + '<div><dt>Hours</dt><dd>' + esc(formatHours(log.hoursSpent)) + '</dd></div>'
    + '<div><dt>Added</dt><dd>' + esc(formatDateTime(log.createdAt)) + '</dd></div>'
    + (log.updatedAt !== log.createdAt ? '<div><dt>Last edited</dt><dd>' + esc(formatDateTime(log.updatedAt)) + '</dd></div>' : '')
    + '</dl>'
    + '<div><p class="muted">What was done</p><p class="prewrap">' + esc(log.description) + '</p></div>'
    + '</div>'
    + '<div class="dialog__foot">'
    + '<button type="button" class="btn btn--ghost" data-act="close">Close</button>'
    + (canDelete ? '<button type="button" class="btn btn--danger" data-act="delete">Delete draft</button>' : '')
    + (canEdit ? '<button type="button" class="btn" data-act="edit">Edit</button>' : '')
    + '</div>',
    'work-detail-title');

  bindClose(dialog);
  const finish = () => { dialog.close(); if (onChanged) { onChanged(); } };
  const edit = dialog.querySelector('[data-act="edit"]');
  const remove = dialog.querySelector('[data-act="delete"]');
  if (edit) {
    edit.addEventListener('click', async () => {
      dialog.close();
      if (await openWorkForm({ log }) && onChanged) {
        onChanged();
      }
    });
  }
  if (remove) {
    remove.addEventListener('click', async () => {
      if (await deleteWorkLog(log)) {
        finish();
      }
    });
  }
  return dialog;
}

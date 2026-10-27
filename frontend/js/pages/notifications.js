import { get, post } from '../api.js';
import { mountShell } from '../shell.js';
import {
  confirmDialog, emptyState, errorState, esc, formatDateTime, icon, renderPager, setBusy, skeletonBlock, toast
} from '../ui.js';

const shell = await mountShell({ page: 'notifications', title: 'Email queue', requires: 'ADMIN' });

if (shell && shell.user.role !== 'ADMIN') {
  shell.main.innerHTML = '<div class="panel">' + emptyState({ title: 'Administrators only', text: 'This page is only available to administrators.' }) + '</div>';
} else if (shell) {
  const { main, setTitle } = shell;
  setTitle('Email queue', 'Leave e-mails waiting to be sent, sent, or failed. “Sent” means the mail server accepted the message; it does not prove the person read it.');

  const state = { status: '', page: 0, size: 10 };
  const TYPE = {
    LEAVE_SUBMITTED: 'Leave submitted', LEAVE_APPROVED: 'Leave approved', LEAVE_REJECTED: 'Leave rejected',
    LEAVE_CANCELLED: 'Leave cancelled', EMPLOYEE_INVITED: 'Employee invitation', EMPLOYEE_UPDATED: 'Profile updated',
    EMPLOYEE_DEACTIVATED: 'Account deactivated', EMPLOYEE_REACTIVATED: 'Account reactivated', ACCOUNT_SECURITY: 'Security notice'
  };
  const chip = (status) => '<span class="chip chip--' + esc(status.toLowerCase()) + '">' + esc(status.charAt(0) + status.slice(1).toLowerCase()) + '</span>';

  main.innerHTML = '<div id="mail-mode"></div><div id="counts"></div>'
    + '<section class="panel" aria-label="E-mail queue"><div class="toolbar">'
    + '<div class="field"><label for="f-status">Status</label><select class="select" id="f-status">'
    + '<option value="">All</option><option value="PENDING">Pending</option><option value="PROCESSING">Processing</option>'
    + '<option value="SENT">Sent</option><option value="FAILED">Failed</option></select></div>'
    + '<div class="toolbar__spacer"></div><button type="button" class="btn btn--ghost" id="reload">Refresh</button></div>'
    + '<div id="region" aria-live="polite"></div><div class="pager" id="pager" hidden></div></section>';

  const region = document.getElementById('region');
  const pager = document.getElementById('pager');
  const counts = document.getElementById('counts');
  const modeBox = document.getElementById('mail-mode');

  /** Says plainly whether real e-mail is switched on; LOG mode is the usual reason "nothing arrives". */
  async function loadMode() {
    try {
      const m = await get('/api/notifications/status');
      const items = m.warnings.map((w) => '<li>' + esc(w) + '</li>').join('');
      modeBox.innerHTML = '<div class="banner banner--' + (m.realDelivery ? 'info' : 'error') + '">' + icon(m.realDelivery ? 'info' : 'alert')
        + '<div><strong>' + (m.realDelivery ? 'Real e-mail is ON (SMTP ' + esc(m.host) + ':' + m.port + ', sender ' + esc(m.from) + ')'
          : 'E-mail is in LOG mode: nothing is sent to anyone') + '</strong>'
        + (items ? '<ul>' + items + '</ul>' : '') + '</div></div>';
    } catch (error) {
      modeBox.innerHTML = '';
    }
  }

  async function load() {
    region.innerHTML = skeletonBlock(5);
    try {
      const [summary, page] = await Promise.all([
        get('/api/notifications/summary'),
        get('/api/notifications', { status: state.status, page: state.page, size: state.size })
      ]);
      counts.innerHTML = '<div class="ledger">'
        + [['Pending', summary.pending], ['Processing', summary.processing], ['Sent', summary.sent], ['Failed', summary.failed]]
          .map(([label, value]) => '<div class="ledger__item' + (label === 'Failed' && value > 0 ? ' is-attention' : '') + '"><span class="ledger__value">'
            + value + '</span><span class="ledger__label">' + label + '</span></div>').join('') + '</div>';
      render(page);
    } catch (error) {
      region.innerHTML = errorState(error.message);
      region.querySelector('[data-retry]').addEventListener('click', load);
      renderPager(pager, null, {});
    }
  }

  function render(page) {
    if (page.content.length === 0) {
      region.innerHTML = emptyState({ title: 'Nothing here', text: state.status ? 'No notifications have this status.' : 'E-mails appear here after leave requests are submitted or decided.' });
      renderPager(pager, null, {});
      return;
    }
    region.innerHTML = '<div class="table-wrap"><table class="table table--compact"><thead><tr>'
      + '<th scope="col">#</th><th scope="col">E-mail</th><th scope="col">To</th><th scope="col">Status</th><th scope="col">Attempts</th>'
      + '<th scope="col">Created</th><th scope="col">Last attempt / next try</th><th scope="col">Last error</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead><tbody>'
      + page.content.map((n) => '<tr><td>' + n.id + '</td><td>' + esc(TYPE[n.type] || n.type)
        + (n.leaveRequestId ? '<br><span class="muted">leave #' + esc(String(n.leaveRequestId)) + '</span>' : '') + '</td><td>' + esc(n.recipientEmail) + '</td><td>' + chip(n.status)
        + (n.channel === 'LOG' ? '<br><span class="muted">log only, not delivered</span>' : '')
        + (n.status === 'PENDING' && n.attempts > 0 ? '<br><span class="muted">will retry</span>' : '') + '</td><td>' + n.attempts + '</td><td>' + esc(formatDateTime(n.createdAt)) + '</td>'
        + '<td>' + (n.status === 'PENDING' && n.nextAttemptAt ? 'next ' + esc(formatDateTime(n.nextAttemptAt)) : n.lastAttemptAt ? esc(formatDateTime(n.lastAttemptAt)) : '–') + '</td>'
        + '<td class="cell-error">' + (n.lastError ? esc(n.lastError) : '–') + '</td>'
        + '<td>' + (n.status === 'FAILED' ? '<button type="button" class="btn btn--ghost btn--sm" data-retry-id="' + n.id + '">' + icon('clock') + '<span>Retry</span></button>' : '') + '</td></tr>').join('')
      + '</tbody></table></div>';
    renderPager(pager, page, {
      onPage: (n) => { state.page = n; load(); },
      onSize: (n) => { state.size = n; state.page = 0; load(); }
    });
  }

  region.addEventListener('click', async (event) => {
    const button = event.target.closest('[data-retry-id]');
    if (!button) { return; }
    const ok = await confirmDialog({
      title: 'Retry this e-mail?', message: 'It goes back into the queue with a fresh set of attempts. The retry is recorded in the audit log.',
      confirmLabel: 'Retry'
    });
    if (!ok) { return; }
    setBusy(button, true);
    try {
      await post('/api/notifications/' + button.dataset.retryId + '/retry');
      toast('Queued for another attempt');
      load();
    } catch (error) {
      toast(error.message, 'error');
      setBusy(button, false);
    }
  });
  document.getElementById('f-status').addEventListener('change', (e) => { state.status = e.target.value; state.page = 0; load(); });
  document.getElementById('reload').addEventListener('click', load);
  loadMode();
  load();
}

import { get } from '../api.js';
import { mountShell } from '../shell.js';
import { debounce, emptyState, errorState, esc, formatDateTime, renderPager, skeletonBlock } from '../ui.js';

const shell = await mountShell({ page: 'audit', title: 'Audit log', requires: 'ADMIN' });

if (shell && shell.user.role !== 'ADMIN') {
  shell.main.innerHTML = '<div class="panel">' + emptyState({ title: 'Administrators only', text: 'The audit log is only available to administrators.' }) + '</div>';
} else if (shell) {
  const { main, setTitle } = shell;
  setTitle('Audit log', 'Who did what, and when. Lines are written by the server and cannot be edited here.');

  const state = { from: '', to: '', action: '', outcome: '', entityType: '', actorId: '', page: 0, size: 25 };

  main.innerHTML = '<section class="panel" aria-label="Audit log"><div class="toolbar" id="filters">'
    + '<div class="field"><label for="f-from">From</label><input class="input" id="f-from" type="date"></div>'
    + '<div class="field"><label for="f-to">To</label><input class="input" id="f-to" type="date"></div>'
    + '<div class="field"><label for="f-action">Action</label><select class="select" id="f-action"><option value="">All actions</option></select></div>'
    + '<div class="field"><label for="f-outcome">Outcome</label><select class="select" id="f-outcome"><option value="">All</option>'
    + '<option value="SUCCESS">Success</option><option value="FAILURE">Failure</option><option value="DENIED">Denied</option></select></div>'
    + '<div class="field"><label for="f-entity">Entity</label><select class="select" id="f-entity"><option value="">All</option>'
    + '<option value="User">User</option><option value="LeaveRequest">Leave request</option><option value="DailyWorkLog">Work report</option>'
    + '<option value="Notification">Notification</option></select></div>'
    + '<div class="field"><label for="f-actor">Actor ID</label><input class="input" id="f-actor" type="number" min="1" inputmode="numeric" placeholder="User id"></div>'
    + '<button type="button" class="btn btn--ghost" id="clear">Clear filters</button></div>'
    + '<div id="region" aria-live="polite"></div><div class="pager" id="pager" hidden></div></section>'
    + '<p class="chart-note">The audit log records important actions. It is append-only for the application, but it is not tamper-proof: '
    + 'someone with direct access to the database could still change it. Times are shown in your local time zone.</p>';

  const region = document.getElementById('region');
  const pager = document.getElementById('pager');
  const $ = (id) => document.getElementById(id);

  get('/api/audit/actions').then((actions) => actions.forEach((a) => {
    const option = document.createElement('option');
    option.value = a;
    option.textContent = a.toLowerCase().replace(/_/g, ' ');
    $('f-action').appendChild(option);
  })).catch(() => { /* the filter stays on "All actions" */ });

  async function load() {
    if (state.from && state.to && state.to < state.from) {
      region.innerHTML = '<div class="panel__body"><p class="banner banner--error" role="alert">The end date is before the start date.</p></div>';
      renderPager(pager, null, {});
      return;
    }
    region.innerHTML = skeletonBlock(6);
    try {
      const page = await get('/api/audit', {
        from: state.from, to: state.to, action: state.action, outcome: state.outcome, entityType: state.entityType,
        actorId: state.actorId, page: state.page, size: state.size
      });
      render(page);
    } catch (error) {
      region.innerHTML = errorState(error.message);
      region.querySelector('[data-retry]').addEventListener('click', load);
      renderPager(pager, null, {});
    }
  }

  function render(page) {
    if (page.content.length === 0) {
      region.innerHTML = emptyState({ title: 'No audit entries match', text: 'Try a wider date range or clear the filters.' });
      renderPager(pager, null, {});
      return;
    }
    region.innerHTML = '<div class="table-wrap"><table class="table table--compact"><thead><tr>'
      + '<th scope="col">When</th><th scope="col">Who</th><th scope="col">Action</th><th scope="col">Entity</th>'
      + '<th scope="col">Outcome</th><th scope="col">Details</th><th scope="col">Request id</th></tr></thead><tbody>'
      + page.content.map((e) => '<tr><td>' + esc(formatDateTime(e.occurredAt)) + '</td>'
        + '<td>' + (e.actorLabel ? esc(e.actorLabel) : '<span class="muted">not signed in</span>')
        + (e.actorRole ? '<br><span class="muted">' + esc(e.actorRole.toLowerCase()) + ' #' + esc(String(e.actorUserId)) + '</span>' : '')
        + (e.clientIp ? '<br><span class="muted">' + esc(e.clientIp) + '</span>' : '') + '</td>'
        + '<td>' + esc(e.action.toLowerCase().replace(/_/g, ' ')) + '</td>'
        + '<td>' + (e.entityType ? esc(e.entityType) + (e.entityId != null ? ' #' + esc(String(e.entityId)) : '') : '–') + '</td>'
        + '<td><span class="chip chip--' + esc(e.outcome.toLowerCase()) + '">' + esc(e.outcome.charAt(0) + e.outcome.slice(1).toLowerCase()) + '</span></td>'
        + '<td class="cell-error">' + (e.summary ? esc(e.summary) : '–') + '</td>'
        + '<td class="cell-error"><code>' + (e.correlationId ? esc(e.correlationId.slice(0, 8)) : '–') + '</code></td></tr>').join('')
      + '</tbody></table></div>';
    renderPager(pager, page, {
      onPage: (n) => { state.page = n; load(); },
      onSize: (n) => { state.size = n; state.page = 0; load(); },
      sizes: [25, 50, 100]
    });
  }

  const refresh = () => { state.page = 0; load(); };
  $('f-from').addEventListener('change', (e) => { state.from = e.target.value; refresh(); });
  $('f-to').addEventListener('change', (e) => { state.to = e.target.value; refresh(); });
  $('f-action').addEventListener('change', (e) => { state.action = e.target.value; refresh(); });
  $('f-outcome').addEventListener('change', (e) => { state.outcome = e.target.value; refresh(); });
  $('f-entity').addEventListener('change', (e) => { state.entityType = e.target.value; refresh(); });
  $('f-actor').addEventListener('input', debounce((e) => { state.actorId = e.target.value.trim(); refresh(); }, 400));
  $('clear').addEventListener('click', () => {
    Object.assign(state, { from: '', to: '', action: '', outcome: '', entityType: '', actorId: '' });
    ['f-from', 'f-to', 'f-action', 'f-outcome', 'f-entity', 'f-actor'].forEach((id) => { $(id).value = ''; });
    refresh();
  });
  load();
}

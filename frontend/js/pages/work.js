import { download, get } from '../api.js';
import { mountShell } from '../shell.js';
import {
  WORK_STATUSES, deleteWorkLog, formatHours, openWorkDetail, openWorkForm, workChip, workPermissions,
  workStatusLabel
} from '../work-actions.js';
import {
  debounce, emptyState, errorState, esc, formatDate, formatLongDay, hydrate, icon, parseDate, person, plural,
  renderPager, setBusy, skeletonBlock, toast, toIsoDate
} from '../ui.js';

const shell = await mountShell({ page: 'work', title: 'Daily work', subtitle: '' });

if (shell) {
  const { user, main, setTitle } = shell;
  const isAdmin = user.role === 'ADMIN';
  const query = new URLSearchParams(location.search);

  setTitle(isAdmin ? 'Work reports' : 'Daily work',
    isAdmin ? 'See what the team worked on and who hasn’t reported.' : 'Log what you worked on and see your hours add up.');

  /* ---------------- Dates ---------------- */
  const today = new Date();
  const addDays = (date, n) => { const d = new Date(date); d.setDate(d.getDate() + n); return d; };
  const monday = addDays(today, -((today.getDay() + 6) % 7));
  const PRESETS = {
    today: () => [toIsoDate(today), toIsoDate(today)],
    week: () => [toIsoDate(monday), toIsoDate(today)],
    month: () => [toIsoDate(new Date(today.getFullYear(), today.getMonth(), 1)), toIsoDate(today)],
    last30: () => [toIsoDate(addDays(today, -29)), toIsoDate(today)]
  };

  const state = {
    scope: isAdmin ? 'team' : 'mine', // team | missing | mine (admins); mine only for employees
    preset: 'week',
    from: PRESETS.week()[0],
    to: PRESETS.week()[1],
    status: WORK_STATUSES.includes(query.get('status')) ? query.get('status') : '',
    employeeId: '',
    q: '',
    page: 0,
    size: 10,
    sortBy: 'workDate',
    direction: 'desc'
  };
  if (isAdmin && query.get('view') === 'missing') {
    state.scope = 'missing';
    state.preset = '';
    state.from = toIsoDate(addDays(today, -7));
    state.to = toIsoDate(addDays(today, -1));
  }
  let logs = new Map();
  let counter = 0;

  const team = () => state.scope === 'team';
  const missing = () => state.scope === 'missing';

  /* ---------------- Frame ---------------- */
  main.innerHTML =
    '<section class="panel" aria-label="Work reports">'
    + '<div class="toolbar">'
    + (isAdmin ? '<div class="segmented" role="group" aria-label="What to show">'
      + '<button type="button" class="segmented__btn" data-scope="team" aria-pressed="true">Team reports</button>'
      + '<button type="button" class="segmented__btn" data-scope="missing" aria-pressed="false">Missing reports</button>'
      + '<button type="button" class="segmented__btn" data-scope="mine" aria-pressed="false">My entries</button></div>' : '')
    + '<div class="segmented" role="group" aria-label="Date range">'
    + '<button type="button" class="segmented__btn" data-preset="today" aria-pressed="false">Today</button>'
    + '<button type="button" class="segmented__btn" data-preset="week" aria-pressed="true">This week</button>'
    + '<button type="button" class="segmented__btn" data-preset="month" aria-pressed="false">This month</button>'
    + '<button type="button" class="segmented__btn" data-preset="last30" aria-pressed="false">Last 30 days</button></div>'
    + '<div class="toolbar__spacer"></div>'
    + '<span id="export-actions"><button type="button" class="btn btn--ghost" data-export="csv">' + icon('download') + '<span>CSV</span></button> '
    + '<button type="button" class="btn btn--ghost" data-export="xlsx">' + icon('download') + '<span>Excel</span></button></span>'
    + '<button type="button" class="btn btn--accent" id="new-entry">' + icon('plus') + '<span>Log work</span></button>'
    + '</div>'
    + '<div class="toolbar" id="filters">'
    + '<div class="field"><label for="f-from">From</label><input class="input" id="f-from" type="date"></div>'
    + '<div class="field"><label for="f-to">To</label><input class="input" id="f-to" type="date"></div>'
    + '<div class="field" id="status-field"><label for="f-status">Status</label><select class="select" id="f-status">'
    + '<option value="">All statuses</option>'
    + WORK_STATUSES.map((s) => '<option value="' + s + '">' + esc(workStatusLabel(s)) + '</option>').join('') + '</select></div>'
    + (isAdmin ? '<div class="field" id="employee-field"><label for="f-employee">Employee</label>'
      + '<select class="select" id="f-employee"><option value="">Everyone</option></select></div>' : '')
    + '<div class="field field--grow" id="search-field"><label for="f-q">Search</label>'
    + '<input class="input" id="f-q" type="search" maxlength="100" autocomplete="off" placeholder="'
    + (isAdmin ? 'Task or employee name' : 'Task title') + '"></div>'
    + '<button type="button" class="btn btn--ghost" id="clear-filters">Clear filters</button>'
    + '</div></section>'
    + '<div id="summary"></div>'
    + '<section class="panel" aria-label="Entries"><div id="region" aria-live="polite"></div>'
    + '<div class="pager" id="pager" hidden></div></section>';

  const region = document.getElementById('region');
  const pager = document.getElementById('pager');
  const summary = document.getElementById('summary');
  const fromInput = document.getElementById('f-from');
  const toInput = document.getElementById('f-to');
  const statusSelect = document.getElementById('f-status');
  const employeeSelect = document.getElementById('f-employee');
  const searchInput = document.getElementById('f-q');
  statusSelect.value = state.status;

  if (isAdmin) {
    get('/api/employees', { size: 100, sortBy: 'fullName', direction: 'asc', status: 'ACTIVE' }).then((page) => {
      page.content.forEach((p) => {
        const option = document.createElement('option');
        option.value = p.id;
        option.textContent = p.fullName;
        employeeSelect.appendChild(option);
      });
    }).catch(() => { /* the filter stays on "Everyone" */ });
  }

  function syncToolbar() {
    main.querySelectorAll('[data-scope]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.scope === state.scope)));
    main.querySelectorAll('[data-preset]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.preset === state.preset)));
    fromInput.value = state.from;
    toInput.value = state.to;
    document.getElementById('employee-field')?.toggleAttribute('hidden', !(team() || missing()));
    document.getElementById('status-field').hidden = missing();
    // Drafts are private to their author, so the team list never contains them.
    statusSelect.querySelector('option[value="DRAFT"]').hidden = team();
    if (team() && state.status === 'DRAFT') {
      state.status = '';
      statusSelect.value = '';
    }
    document.getElementById('search-field').hidden = missing();
    document.getElementById('export-actions').hidden = missing();
    region.closest('.panel').hidden = false;
  }

  const refresh = () => { state.page = 0; load(); };

  main.addEventListener('click', async (event) => {
    const scope = event.target.closest('[data-scope]');
    if (scope) {
      state.scope = scope.dataset.scope;
      state.employeeId = '';
      if (employeeSelect) { employeeSelect.value = ''; }
      if (missing()) {
        // The missing-report check needs a past range: default to the last 7 days ending yesterday.
        state.preset = '';
        state.from = toIsoDate(addDays(today, -7));
        state.to = toIsoDate(addDays(today, -1));
      }
      refresh();
      return;
    }
    const preset = event.target.closest('[data-preset]');
    if (preset) {
      state.preset = preset.dataset.preset;
      [state.from, state.to] = PRESETS[state.preset]();
      refresh();
      return;
    }
    if (event.target.closest('#new-entry')) {
      if (await openWorkForm()) {
        if (missing()) { state.scope = isAdmin ? 'mine' : state.scope; }
        refresh();
      }
      return;
    }
    const sort = event.target.closest('[data-sort]');
    if (sort) {
      const field = sort.dataset.sort;
      state.direction = state.sortBy === field && state.direction === 'asc' ? 'desc' : 'asc';
      state.sortBy = field;
      refresh();
      return;
    }
    const act = event.target.closest('[data-work-act]');
    if (act) {
      const log = logs.get(Number(act.dataset.workId));
      if (!log) { return; }
      const kind = act.dataset.workAct;
      if (kind === 'view') { openWorkDetail(log, { user, onChanged: load }); return; }
      if (kind === 'edit' && await openWorkForm({ log })) { load(); return; }
      if (kind === 'delete' && await deleteWorkLog(log)) { load(); }
      return;
    }
    const pick = event.target.closest('[data-pick-employee]');
    if (pick) {
      state.scope = 'team';
      state.employeeId = pick.dataset.pickEmployee;
      if (employeeSelect) { employeeSelect.value = state.employeeId; }
      refresh();
    }
  });

  /* Export uses the filters currently on screen. The server decides what you may export:
     administrators get everyone's submitted reports (never drafts), everyone else only their own. */
  main.querySelector('#export-actions').addEventListener('click', async (event) => {
    const button = event.target.closest('[data-export]');
    if (!button) { return; }
    const problem = rangeProblem();
    if (problem) { toast(problem, 'error'); return; }
    setBusy(button, true);
    try {
      const filename = await download(team() ? '/api/work-logs/export' : '/api/work-logs/my/export', {
        format: button.dataset.export, status: state.status, from: state.from, to: state.to, q: state.q,
        ...(team() ? { employeeId: state.employeeId } : {})
      });
      toast('Downloaded ' + filename);
    } catch (error) {
      toast(error.message, 'error');
    } finally {
      setBusy(button, false);
    }
  });

  const customRange = () => { state.preset = ''; refresh(); };
  fromInput.addEventListener('change', () => { state.from = fromInput.value; customRange(); });
  toInput.addEventListener('change', () => { state.to = toInput.value; customRange(); });
  statusSelect.addEventListener('change', () => { state.status = statusSelect.value; refresh(); });
  if (employeeSelect) {
    employeeSelect.addEventListener('change', () => { state.employeeId = employeeSelect.value; refresh(); });
  }
  searchInput.addEventListener('input', debounce(() => { state.q = searchInput.value.trim(); refresh(); }, 350));
  document.getElementById('clear-filters').addEventListener('click', () => {
    Object.assign(state, { status: '', employeeId: '', q: '', preset: 'week' });
    [state.from, state.to] = PRESETS.week();
    statusSelect.value = '';
    searchInput.value = '';
    if (employeeSelect) { employeeSelect.value = ''; }
    refresh();
  });

  /* ---------------- Loading ---------------- */
  function rangeProblem() {
    if (!state.from || !state.to) { return 'Choose both a start and an end date.'; }
    if (state.to < state.from) { return 'The end date is before the start date.'; }
    return '';
  }

  async function load() {
    syncToolbar();
    const ticket = ++counter;
    const problem = rangeProblem();
    if (problem) {
      summary.innerHTML = '';
      region.innerHTML = '<div class="panel__body"><div class="banner banner--error">' + icon('alert') + '<span>' + esc(problem) + '</span></div></div>';
      renderPager(pager, null, {});
      return;
    }
    region.innerHTML = skeletonBlock(6);
    try {
      if (missing()) {
        const [data] = await Promise.all([
          get('/api/work-logs/missing', { from: state.from, to: state.to, employeeId: state.employeeId })
        ]);
        if (ticket !== counter) { return; }
        summary.innerHTML = '';
        renderMissing(data);
        renderPager(pager, null, {});
        return;
      }
      const base = { from: state.from, to: state.to };
      const listParams = {
        ...base, status: state.status, q: state.q, page: state.page, size: state.size,
        sortBy: state.sortBy, direction: state.direction,
        ...(team() ? { employeeId: state.employeeId } : {})
      };
      const [page, totals] = await Promise.all([
        get(team() ? '/api/work-logs' : '/api/work-logs/my', listParams),
        get(team() ? '/api/work-logs/summary' : '/api/work-logs/my/summary',
          team() ? { ...base, employeeId: state.employeeId } : base)
      ]);
      if (ticket !== counter) { return; }
      logs = new Map(page.content.map((l) => [l.id, l]));
      summary.innerHTML = team() ? teamSummary(totals) : mySummary(totals);
      hydrate(summary);
      renderList(page);
    } catch (error) {
      if (ticket !== counter) { return; }
      summary.innerHTML = '';
      region.innerHTML = errorState(error.message);
      region.querySelector('[data-retry]').addEventListener('click', load);
      renderPager(pager, null, {});
    }
  }

  /* ---------------- Summary cards ---------------- */
  function ledgerHtml(items) {
    return '<div class="ledger">' + items.map((item) => '<div class="ledger__item' + (item.attention ? ' is-attention' : '') + '">'
      + '<span class="ledger__value">' + esc(item.value) + '</span><span class="ledger__label">' + esc(item.label) + '</span></div>').join('') + '</div>';
  }

  function mySummary(s) {
    const max = Math.max(1, ...s.days.map((d) => Number(d.hours)));
    return ledgerHtml([
      { value: formatHours(s.totalHours), label: 'Hours reported' },
      { value: s.entryCount, label: 'Entries' },
      { value: s.daysReported, label: (s.daysReported === 1 ? 'Day' : 'Days') + ' reported' },
      { value: s.statusCounts.COMPLETED, label: 'Completed' },
      { value: s.statusCounts.BLOCKED, label: 'Blocked', attention: s.statusCounts.BLOCKED > 0 },
      { value: s.draftCount, label: 'Drafts (not counted)' }
    ])
      + '<section class="panel" aria-labelledby="days-title"><div class="panel__head"><div><h2 id="days-title">Hours per day</h2>'
      + '<p>' + esc(formatDate(s.from)) + ' to ' + esc(formatDate(s.to)) + '. Drafts are not included.</p></div></div>'
      + (s.days.length === 0
        ? '<div class="panel__body"><p class="muted">Nothing reported in this range yet.</p></div>'
        : '<ul class="bars">' + s.days.map((d) => '<li><div class="bar__row"><span>' + esc(formatLongDay(d.date))
          + '</span><strong>' + esc(formatHours(d.hours)) + '</strong></div><div class="bar__track"><div class="bar__fill" data-w="'
          + Math.round((Number(d.hours) / max) * 100) + '"></div></div></li>').join('') + '</ul>')
      + '</section>';
  }

  function teamSummary(s) {
    const max = Math.max(1, ...s.employees.map((e) => Number(e.hours)));
    return ledgerHtml([
      { value: formatHours(s.totalHours), label: 'Hours reported' },
      { value: s.entryCount, label: 'Entries' },
      { value: s.employeesReporting, label: 'Reporting (of ' + s.activeEmployees + ' active)' },
      { value: s.statusCounts.COMPLETED, label: 'Completed' },
      { value: s.statusCounts.IN_PROGRESS, label: 'In progress' },
      { value: s.statusCounts.BLOCKED, label: 'Blocked', attention: s.statusCounts.BLOCKED > 0 }
    ])
      + '<section class="panel" aria-labelledby="people-title"><div class="panel__head"><div><h2 id="people-title">Hours by employee</h2>'
      + '<p>' + esc(formatDate(s.from)) + ' to ' + esc(formatDate(s.to)) + '. Drafts are not included.</p></div></div>'
      + (s.employees.length === 0
        ? '<div class="panel__body"><p class="muted">No reports in this range.</p></div>'
        : '<ul class="bars">' + s.employees.map((e) => '<li><div class="bar__row"><button type="button" class="link-btn" data-pick-employee="'
          + e.employeeId + '">' + esc(e.employeeName) + '</button><strong>' + esc(formatHours(e.hours)) + ' · '
          + plural(e.entries, 'entry', 'entries') + '</strong></div><div class="bar__track"><div class="bar__fill" data-w="'
          + Math.round((Number(e.hours) / max) * 100) + '"></div></div></li>').join('') + '</ul>')
      + '</section>';
  }

  /* ---------------- List ---------------- */
  function sortHeader(label, field) {
    const active = state.sortBy === field;
    const aria = active ? (state.direction === 'asc' ? 'ascending' : 'descending') : 'none';
    return '<th scope="col" aria-sort="' + aria + '"><button type="button" class="sort-btn" data-sort="' + field + '">'
      + esc(label) + '</button></th>';
  }

  function renderList(page) {
    if (page.content.length === 0) {
      const filtered = state.status || state.q || state.employeeId;
      region.innerHTML = emptyState(filtered
        ? { title: 'No entries match those filters', text: 'Try a wider date range or clear the filters.' }
        : { title: team() ? 'No work reports in this range' : 'Nothing logged in this range',
          text: team() ? 'Reports appear here as people submit them.' : 'Log what you worked on today and it will show up here.',
          actionHtml: team() ? '' : '<button type="button" class="btn" id="empty-entry">Log work</button>' });
      document.getElementById('empty-entry')?.addEventListener('click', () => document.getElementById('new-entry').click());
      renderPager(pager, null, {});
      return;
    }
    const who = team();
    region.innerHTML = '<div class="table-wrap"><table class="table"><thead><tr>'
      + sortHeader('Date', 'workDate')
      + (who ? '<th scope="col">Employee</th>' : '')
      + sortHeader('Task', 'taskTitle') + sortHeader('Hours', 'hoursSpent') + sortHeader('Status', 'status')
      + '<th scope="col"><span class="sr-only">Actions</span></th></tr></thead><tbody>'
      + page.content.map((l) => row(l, who)).join('') + '</tbody></table></div>';
    renderPager(pager, page, {
      onPage: (n) => { state.page = n; load(); },
      onSize: (n) => { state.size = n; state.page = 0; load(); }
    });
  }

  function row(l, who) {
    const perms = workPermissions(l, user);
    const id = ' data-work-id="' + l.id + '"';
    const excerpt = l.description.length > 110 ? l.description.slice(0, 110) + '…' : l.description;
    return '<tr>'
      + '<td class="cell-primary num" data-label="Date"><strong>' + esc(formatDate(l.workDate)) + '</strong></td>'
      + (who ? '<td data-label="Employee">' + person(l.employeeName, l.department) + '</td>' : '')
      + '<td data-label="Task"><div class="task-cell"><strong>' + esc(l.taskTitle) + '</strong><br><span class="muted clamp">' + esc(excerpt) + '</span></div></td>'
      + '<td class="num" data-label="Hours">' + esc(formatHours(l.hoursSpent)) + '</td>'
      + '<td data-label="Status">' + workChip(l.status) + '</td>'
      + '<td class="actions">'
      + '<button type="button" class="btn btn--ghost btn--sm btn--icon" data-work-act="view"' + id + ' title="Read the full entry">'
      + icon('eye') + '<span class="btn-label">View</span></button>'
      + (perms.canEdit ? '<button type="button" class="btn btn--ghost btn--sm" data-work-act="edit"' + id + '>' + icon('edit') + '<span>Edit</span></button>' : '')
      + (perms.canDelete ? '<button type="button" class="btn btn--ghost btn--sm" data-work-act="delete"' + id + '>' + icon('trash') + '<span>Delete</span></button>' : '')
      + '</td></tr>';
  }

  /* ---------------- Missing reports ---------------- */
  function renderMissing(list) {
    const note = '<p class="muted note">Weekends, days on approved leave and days before someone joined are never counted as missing. '
      + 'Today is not counted until it has passed. Public holidays are not modelled, so a company holiday will show as missing. '
      + 'Drafts do not count as a submitted report.</p>';
    if (list.length === 0) {
      region.innerHTML = '<div class="panel__body">' + note + '</div>'
        + emptyState({ title: 'Everyone has reported', text: 'No missing reports between ' + formatDate(state.from) + ' and ' + formatDate(state.to) + '.' });
      return;
    }
    region.innerHTML = '<div class="panel__body">' + note + '</div><ul class="list">' + list.map((m) => '<li class="list__item">'
      + '<div>' + person(m.employeeName, m.department + ' · ' + m.employeeEmail) + '</div>'
      + '<div class="list__meta"><strong>' + plural(m.missingCount, 'missing day', 'missing days') + '</strong>'
      + '<div class="date-chips">' + m.missingDates.map((d) => '<span class="date-chip">' + esc(formatDate(d)) + '</span>').join('') + '</div></div>'
      + '<div class="actions"><button type="button" class="btn btn--ghost btn--sm" data-pick-employee="' + m.employeeId + '">See their reports</button></div>'
      + '</li>').join('') + '</ul>';
  }

  load();
}

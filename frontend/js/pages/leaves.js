import { get } from '../api.js';
import { mountShell } from '../shell.js';
import {
  approveLeave, cancelLeave, leavePermissions, openLeaveDetail, openLeaveForm, rejectLeave
} from '../leave-actions.js';
import {
  avatar, emptyState, errorState, esc, formatDate, formatLongDay, formatRange, icon, parseDate, person,
  plural, renderPager, skeletonBlock, statusChip, toIsoDate, typeLabel, typeTag
} from '../ui.js';

const STATUSES = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'];
const STATUS_NAMES = { PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected', CANCELLED: 'Cancelled' };

const shell = await mountShell({ page: 'leaves', title: 'Leave', subtitle: '' });

if (shell) {
  const { user, main, setTitle } = shell;
  const isAdmin = user.role === 'ADMIN';
  const query = new URLSearchParams(location.search);

  setTitle(isAdmin ? 'Leave requests' : 'My leave',
    isAdmin ? 'Review requests and see who is away.' : 'Send requests and follow their decisions.');

  const state = {
    scope: 'all', // admins can switch to 'mine'
    view: 'list',
    status: STATUSES.includes(query.get('status')) ? query.get('status') : '',
    employeeId: '',
    from: '',
    to: '',
    page: 0,
    size: 10,
    sortBy: 'createdAt',
    direction: 'desc',
    month: new Date(new Date().getFullYear(), new Date().getMonth(), 1),
    selectedDay: toIsoDate(new Date())
  };
  let leaves = new Map();
  let requestCounter = 0;

  const showEveryone = () => isAdmin && state.scope === 'all';
  const endpoint = () => (showEveryone() ? '/api/leaves' : '/api/leaves/my');

  /* ---------------- Page frame ---------------- */
  main.innerHTML =
    '<section class="panel" aria-label="Leave requests">'
    + '<div class="toolbar">'
    + (isAdmin ? '<div class="segmented" role="group" aria-label="Whose requests">'
      + '<button type="button" class="segmented__btn" data-scope="all" aria-pressed="true">Everyone</button>'
      + '<button type="button" class="segmented__btn" data-scope="mine" aria-pressed="false">Mine</button></div>' : '')
    + '<div class="segmented" role="group" aria-label="How to show requests">'
    + '<button type="button" class="segmented__btn" data-view="list" aria-pressed="true">List</button>'
    + '<button type="button" class="segmented__btn" data-view="calendar" aria-pressed="false">Calendar</button></div>'
    + '<div class="toolbar__spacer"></div>'
    + '<button type="button" class="btn btn--accent" id="new-request">' + icon('plus') + '<span>Request leave</span></button>'
    + '</div>'
    + '<div class="toolbar" id="filters">'
    + '<div class="field"><label for="f-status">Status</label><select class="select" id="f-status"><option value="">All statuses</option>'
    + STATUSES.map((s) => '<option value="' + s + '">' + STATUS_NAMES[s] + '</option>').join('') + '</select></div>'
    + (isAdmin ? '<div class="field" id="employee-field"><label for="f-employee">Employee</label>'
      + '<select class="select" id="f-employee"><option value="">Everyone</option></select></div>' : '')
    + '<div class="field"><label for="f-from">From</label><input class="input" id="f-from" type="date"></div>'
    + '<div class="field"><label for="f-to">To</label><input class="input" id="f-to" type="date"></div>'
    + '<button type="button" class="btn btn--ghost" id="clear-filters">Clear filters</button>'
    + '</div>'
    + '<div id="region" aria-live="polite"></div>'
    + '<div class="pager" id="pager" hidden></div>'
    + '</section>';

  const region = document.getElementById('region');
  const pager = document.getElementById('pager');
  const statusSelect = document.getElementById('f-status');
  const employeeSelect = document.getElementById('f-employee');
  const fromInput = document.getElementById('f-from');
  const toInput = document.getElementById('f-to');
  statusSelect.value = state.status;

  if (isAdmin) {
    get('/api/employees', { size: 100, sortBy: 'fullName', direction: 'asc' }).then((page) => {
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
    main.querySelectorAll('[data-view]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.view === state.view)));
    const employeeField = document.getElementById('employee-field');
    if (employeeField) {
      employeeField.hidden = !showEveryone();
    }
    // Dates make no sense as filters on the calendar: the month already is the date range.
    fromInput.closest('.field').hidden = state.view === 'calendar';
    toInput.closest('.field').hidden = state.view === 'calendar';
  }

  const refresh = () => { state.page = 0; load(); };
  statusSelect.addEventListener('change', () => { state.status = statusSelect.value; refresh(); });
  if (employeeSelect) {
    employeeSelect.addEventListener('change', () => { state.employeeId = employeeSelect.value; refresh(); });
  }
  fromInput.addEventListener('change', () => { state.from = fromInput.value; refresh(); });
  toInput.addEventListener('change', () => { state.to = toInput.value; refresh(); });
  document.getElementById('clear-filters').addEventListener('click', () => {
    Object.assign(state, { status: '', employeeId: '', from: '', to: '' });
    statusSelect.value = '';
    fromInput.value = '';
    toInput.value = '';
    if (employeeSelect) {
      employeeSelect.value = '';
    }
    refresh();
  });

  main.addEventListener('click', async (event) => {
    const scope = event.target.closest('[data-scope]');
    if (scope) {
      state.scope = scope.dataset.scope;
      state.employeeId = '';
      if (employeeSelect) {
        employeeSelect.value = '';
      }
      refresh();
      return;
    }
    const view = event.target.closest('[data-view]');
    if (view) {
      state.view = view.dataset.view;
      refresh();
      return;
    }
    if (event.target.closest('#new-request')) {
      if (await openLeaveForm()) {
        state.scope = isAdmin ? 'mine' : state.scope; // an admin's own new request lives under "Mine"
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
    const nav = event.target.closest('[data-month]');
    if (nav) {
      const step = Number(nav.dataset.month);
      state.month = step === 0
        ? new Date(new Date().getFullYear(), new Date().getMonth(), 1)
        : new Date(state.month.getFullYear(), state.month.getMonth() + step, 1);
      state.selectedDay = '';
      load();
      return;
    }
    const day = event.target.closest('[data-day]');
    if (day) {
      state.selectedDay = day.dataset.day;
      renderCalendar(calendarLeaves, calendarTruncated);
      const selected = region.querySelector('[data-day="' + state.selectedDay + '"]');
      if (selected) {
        selected.focus();
      }
      return;
    }
    const action = event.target.closest('[data-leave-act]');
    if (action) {
      const leave = leaves.get(Number(action.dataset.leaveId));
      if (!leave) {
        return;
      }
      const kind = action.dataset.leaveAct;
      let changed = false;
      if (kind === 'view') {
        openLeaveDetail(leave, { user, onChanged: load });
        return;
      }
      if (kind === 'approve') { changed = await approveLeave(leave); }
      if (kind === 'reject') { changed = await rejectLeave(leave); }
      if (kind === 'cancel') { changed = await cancelLeave(leave); }
      if (changed) {
        load();
      }
    }
  });

  /* ---------------- Loading ---------------- */
  function filterParams() {
    return {
      status: state.status,
      employeeId: showEveryone() ? state.employeeId : '',
      from: state.view === 'list' ? state.from : '',
      to: state.view === 'list' ? state.to : ''
    };
  }

  let calendarLeaves = [];
  let calendarTruncated = false;

  async function load() {
    syncToolbar();
    const ticket = ++requestCounter;
    region.innerHTML = skeletonBlock(6);
    try {
      if (state.view === 'calendar') {
        const first = new Date(state.month.getFullYear(), state.month.getMonth(), 1);
        const last = new Date(state.month.getFullYear(), state.month.getMonth() + 1, 0);
        const page = await get(endpoint(), {
          ...filterParams(), from: toIsoDate(first), to: toIsoDate(last),
          page: 0, size: 100, sortBy: 'startDate', direction: 'asc'
        });
        if (ticket !== requestCounter) { return; }
        calendarLeaves = page.content;
        leaves = new Map(page.content.map((l) => [l.id, l]));
        calendarTruncated = page.totalElements > page.content.length;
        renderCalendar(calendarLeaves, calendarTruncated);
        renderPager(pager, null, {});
        return;
      }

      const page = await get(endpoint(), {
        ...filterParams(), page: state.page, size: state.size, sortBy: state.sortBy, direction: state.direction
      });
      if (ticket !== requestCounter) { return; }
      leaves = new Map(page.content.map((l) => [l.id, l]));
      renderList(page);
    } catch (error) {
      if (ticket !== requestCounter) { return; }
      region.innerHTML = errorState(error.message);
      region.querySelector('[data-retry]').addEventListener('click', load);
      renderPager(pager, null, {});
    }
  }

  /* ---------------- List view ---------------- */
  function sortHeader(label, field) {
    const active = state.sortBy === field;
    const aria = active ? (state.direction === 'asc' ? 'ascending' : 'descending') : 'none';
    return '<th scope="col" aria-sort="' + aria + '"><button type="button" class="sort-btn" data-sort="' + field + '">'
      + esc(label) + '</button></th>';
  }

  function renderList(page) {
    if (page.content.length === 0) {
      const filtered = state.status || state.employeeId || state.from || state.to;
      region.innerHTML = emptyState(filtered
        ? { title: 'No requests match those filters', text: 'Try different dates or clear the filters.' }
        : { title: showEveryone() ? 'No leave requests yet' : 'You haven’t asked for leave yet',
          text: showEveryone() ? 'Requests will appear here as people send them.' : 'When you need time off, send a request and follow the decision here.',
          actionHtml: showEveryone() ? '' : '<button type="button" class="btn" id="empty-request">Request leave</button>' });
      const emptyButton = document.getElementById('empty-request');
      if (emptyButton) {
        emptyButton.addEventListener('click', () => document.getElementById('new-request').click());
      }
      renderPager(pager, null, {});
      return;
    }
    const who = showEveryone();
    region.innerHTML = '<div class="table-wrap"><table class="table"><thead><tr>'
      + (who ? '<th scope="col">Employee</th>' : '')
      + sortHeader('Dates', 'startDate') + sortHeader('Type', 'leaveType') + sortHeader('Days', 'days')
      + sortHeader('Status', 'status') + sortHeader('Sent', 'createdAt')
      + '<th scope="col"><span class="sr-only">Actions</span></th></tr></thead><tbody>'
      + page.content.map((l) => row(l, who)).join('') + '</tbody></table></div>';
    renderPager(pager, page, {
      onPage: (n) => { state.page = n; load(); },
      onSize: (n) => { state.size = n; state.page = 0; load(); }
    });
  }

  function row(l, who) {
    const perms = leavePermissions(l, user);
    const id = ' data-leave-id="' + l.id + '"';
    return '<tr>'
      + (who ? '<td class="cell-primary">' + person(l.employeeName, l.department) + '</td>' : '')
      + '<td' + (who ? '' : ' class="cell-primary"') + ' data-label="Dates"><strong>' + esc(formatRange(l.startDate, l.endDate)) + '</strong></td>'
      + '<td data-label="Type">' + typeTag(l.leaveType) + '</td>'
      + '<td class="num" data-label="Days">' + l.days + '</td>'
      + '<td data-label="Status">' + statusChip(l.status) + '</td>'
      + '<td class="num" data-label="Sent">' + esc(formatDate(l.createdAt.slice(0, 10))) + '</td>'
      + '<td class="actions">'
      + '<button type="button" class="btn btn--ghost btn--sm btn--icon" data-leave-act="view"' + id + ' title="View details">' + icon('eye') + '<span class="btn-label">View</span></button>'
      + (perms.canReview ? '<button type="button" class="btn btn--sm" data-leave-act="approve"' + id + '>' + icon('check') + '<span>Approve</span></button>'
        + '<button type="button" class="btn btn--ghost btn--sm" data-leave-act="reject"' + id + '>' + icon('x') + '<span>Reject</span></button>' : '')
      + (perms.canCancel ? '<button type="button" class="btn btn--ghost btn--sm" data-leave-act="cancel"' + id + '>' + icon('ban') + '<span>Cancel</span></button>' : '')
      + '</td></tr>';
  }

  /* ---------------- Calendar view ---------------- */
  function renderCalendar(list, truncated = false) {
    const year = state.month.getFullYear();
    const month = state.month.getMonth();
    const daysInMonth = new Date(year, month + 1, 0).getDate();
    const lead = (new Date(year, month, 1).getDay() + 6) % 7; // weeks start on Monday
    const todayIso = toIsoDate(new Date());
    const shown = state.status ? list : list.filter((l) => l.status === 'PENDING' || l.status === 'APPROVED');

    // day (ISO) -> requests that use a working day on it
    const byDay = new Map();
    shown.forEach((l) => {
      for (let d = parseDate(l.startDate); toIsoDate(d) <= l.endDate; d.setDate(d.getDate() + 1)) {
        if (d.getMonth() !== month || d.getDay() === 0 || d.getDay() === 6) {
          continue;
        }
        const key = toIsoDate(d);
        if (!byDay.has(key)) { byDay.set(key, []); }
        byDay.get(key).push(l);
      }
    });

    const monthLabel = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' }).format(state.month);
    let cells = ['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d) => '<div class="cal__dow" aria-hidden="true">' + d + '</div>').join('');
    for (let i = 0; i < lead; i += 1) {
      cells += '<div class="cal__pad"></div>';
    }
    for (let day = 1; day <= daysInMonth; day += 1) {
      const date = new Date(year, month, day);
      const iso = toIsoDate(date);
      const weekend = date.getDay() === 0 || date.getDay() === 6;
      const away = byDay.get(iso) || [];
      const approved = away.filter((l) => l.status === 'APPROVED');
      const others = away.filter((l) => l.status !== 'APPROVED');
      const visible = [...approved, ...others].slice(0, 3);
      const label = formatLongDay(iso) + (away.length ? ', ' + plural(away.length, 'person', 'people') + ' away' : '');
      cells += '<button type="button" class="cal__day' + (weekend ? ' is-weekend' : '') + (iso === todayIso ? ' is-today' : '')
        + '" data-day="' + iso + '" aria-pressed="' + (iso === state.selectedDay) + '" aria-label="' + esc(label) + '">'
        + '<span class="cal__num">' + day + '</span>'
        + '<span class="cal__people">' + visible.map((l) => avatar(l.employeeName, 'sm')).join('')
        + (away.length > 3 ? '<span class="cal__more">+' + (away.length - 3) + '</span>' : '') + '</span></button>';
    }

    const selected = state.selectedDay ? (byDay.get(state.selectedDay) || []) : null;
    let detail;
    if (selected === null) {
      detail = '<p class="muted">Select a day to see who is away.</p>';
    } else if (selected.length === 0) {
      detail = '<h3>' + esc(formatLongDay(state.selectedDay)) + '</h3><p class="muted">Nobody is away this day.</p>';
    } else {
      detail = '<h3>' + esc(formatLongDay(state.selectedDay)) + '</h3><ul class="list">'
        + selected.map((l) => '<li class="list__item">' + person(showEveryone() ? l.employeeName : user.fullName, typeLabel(l.leaveType) + ' · ' + formatRange(l.startDate, l.endDate))
          + '<div>' + statusChip(l.status) + ' <button type="button" class="btn btn--ghost btn--sm" data-leave-act="view" data-leave-id="' + l.id + '">View</button></div></li>').join('')
        + '</ul>';
    }

    region.innerHTML =
      '<div class="panel__head"><div class="cal__bar">'
      + '<button type="button" class="btn btn--ghost btn--icon btn--sm" data-month="-1" aria-label="Previous month">' + icon('left') + '</button>'
      + '<button type="button" class="btn btn--ghost btn--icon btn--sm" data-month="1" aria-label="Next month">' + icon('right') + '</button>'
      + '<button type="button" class="btn btn--ghost btn--sm" data-month="0">This month</button>'
      + '<h2 class="cal__title" aria-live="polite">' + esc(monthLabel) + '</h2></div>'
      + '<div class="legend"><span class="legend__item"><span class="legend__dot legend__dot--approved"></span>Approved</span>'
      + '<span class="legend__item"><span class="legend__dot legend__dot--pending"></span>Pending (faded)</span></div></div>'
      + '<div class="cal__grid">' + cells + '</div>'
      + (truncated ? '<p class="banner banner--info">' + icon('info') + '<span>This month has more than 100 requests. Narrow the filters to see them all.</span></p>' : '')
      + '<div class="cal__detail" aria-live="polite">' + detail + '</div>';
  }

  syncToolbar();
  load();
}

import { get } from '../api.js';
import { barChart, legend, lineChart } from '../charts.js';
import { mountShell } from '../shell.js';
import { approveLeave, openLeaveDetail, openLeaveForm, rejectLeave } from '../leave-actions.js';
import { formatHours, openWorkForm } from '../work-actions.js';
import {
  avatar, emptyState, errorState, esc, formatDate, formatLongDay, formatRange, hydrate, icon, person, plural,
  skeletonBlock, statusChip, toIsoDate, typeTag
} from '../ui.js';
import { workChip } from '../work-actions.js';

function greeting(name) {
  const hour = new Date().getHours();
  const part = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';
  return part + ', ' + name.split(' ')[0];
}

const shell = await mountShell({
  page: 'dashboard',
  title: 'Dashboard',
  subtitle: formatLongDay(toIsoDate(new Date()))
});

if (shell) {
  const { user, main, setTitle } = shell;
  setTitle(greeting(user.fullName), formatLongDay(toIsoDate(new Date())));
  const isAdmin = user.role === 'ADMIN';
  let leavesById = new Map();

  const load = async () => {
    main.innerHTML = '<div class="panel">' + skeletonBlock(5) + '</div>';
    try {
      const data = await get(isAdmin ? '/api/dashboard/admin' : '/api/dashboard/employee');
      main.innerHTML = (isAdmin ? renderAdmin(data) : renderEmployee(data))
        + '<section class="panel" id="work-widget" aria-labelledby="work-widget-title" aria-busy="true">'
        + '<div class="panel__head"><div><h2 id="work-widget-title">' + (isAdmin ? 'Team work reports' : 'Your work this week') + '</h2>'
        + '<p>Last 7 days</p></div></div><div class="panel__body"><span class="skeleton"></span></div></section>'
        + '<section class="panel" id="analytics" aria-labelledby="analytics-title" aria-busy="true">'
        + '<div class="panel__head"><div><h2 id="analytics-title">' + (isAdmin ? 'Team productivity' : 'Your productivity') + '</h2>'
        + '<p>Reported work hours and tasks</p></div></div><div class="panel__body"><span class="skeleton"></span></div></section>';
      hydrate(main);
      loadWorkWidget();
      loadAnalytics();
    } catch (error) {
      main.innerHTML = '<div class="panel">' + errorState(error.message) + '</div>';
      main.querySelector('[data-retry]').addEventListener('click', load);
    }
  };

  /* The work card loads on its own: if it fails, the rest of the dashboard still works. */
  async function loadWorkWidget() {
    const widget = document.getElementById('work-widget');
    if (!widget) {
      return;
    }
    const head = (actions) => '<div class="panel__head"><div><h2 id="work-widget-title">'
      + (isAdmin ? 'Team work reports' : 'Your work this week') + '</h2><p>Last 7 days</p></div>'
      + '<div class="actions">' + actions + '</div></div>';
    try {
      if (isAdmin) {
        const [summary, missingList] = await Promise.all([get('/api/work-logs/summary'), get('/api/work-logs/missing')]);
        const missingDays = missingList.reduce((sum, m) => sum + m.missingCount, 0);
        widget.innerHTML = head('<a class="btn btn--ghost btn--sm" href="/work.html">Open work reports</a>')
          + '<div class="panel__body"><dl class="facts">'
          + '<div><dt>Hours reported</dt><dd>' + esc(formatHours(summary.totalHours)) + '</dd></div>'
          + '<div><dt>Employees reporting</dt><dd>' + summary.employeesReporting + ' of ' + summary.activeEmployees + '</dd></div>'
          + '<div><dt>Blocked tasks</dt><dd>' + summary.statusCounts.BLOCKED + '</dd></div>'
          + '<div><dt>Missing reports</dt><dd>' + (missingList.length === 0 ? 'None'
            : plural(missingDays, 'day', 'days') + ' · ' + plural(missingList.length, 'person', 'people')) + '</dd></div>'
          + '</dl>' + (missingList.length > 0
            ? '<p><a href="/work.html?view=missing">See who is missing a report</a></p>' : '') + '</div>';
      } else {
        const summary = await get('/api/work-logs/my/summary');
        widget.innerHTML = head('<button type="button" class="btn btn--sm" data-log-work>' + icon('plus') + '<span>Log work</span></button> '
          + '<a class="btn btn--ghost btn--sm" href="/work.html">Open daily work</a>')
          + '<div class="panel__body"><dl class="facts">'
          + '<div><dt>Hours reported</dt><dd>' + esc(formatHours(summary.totalHours)) + '</dd></div>'
          + '<div><dt>Entries</dt><dd>' + summary.entryCount + '</dd></div>'
          + '<div><dt>Days reported</dt><dd>' + summary.daysReported + '</dd></div>'
          + '<div><dt>Drafts</dt><dd>' + summary.draftCount + '</dd></div></dl></div>';
      }
    } catch (error) {
      widget.innerHTML = head('') + '<div class="panel__body"><p class="muted">Work reports couldn’t be loaded right now. '
        + '<a href="/work.html">Open the page</a> to try again.</p></div>';
    }
    widget.setAttribute('aria-busy', 'false');
  }

  /* ---------------- Charts and productivity summaries ---------------- */
  const range = { days: 30, day: toIsoDate(new Date()) };
  const STATUS_LABEL = { DRAFT: 'Draft', IN_PROGRESS: 'In progress', COMPLETED: 'Completed', BLOCKED: 'Blocked' };
  const statusItems = (counts, withDraft) => Object.keys(STATUS_LABEL)
    .filter((key) => withDraft || key !== 'DRAFT')
    .map((key) => ({ key: key.toLowerCase(), label: STATUS_LABEL[key], value: counts[key] || 0 }));
  const hoursLabel = (value) => esc(formatHours(value));

  function analyticsControls() {
    const button = (days, label) => '<button type="button" class="segmented__btn" data-range="' + days + '" aria-pressed="'
      + (range.days === days) + '">' + label + '</button>';
    return '<div class="toolbar"><div class="segmented" role="group" aria-label="Period">'
      + button(7, 'Last 7 days') + button(30, 'Last 30 days') + button(90, 'Last 90 days') + '</div>'
      + (isAdmin ? '<div class="field"><label for="a-day">Reports submitted on</label>'
        + '<input class="input" id="a-day" type="date" value="' + esc(range.day) + '" max="' + esc(toIsoDate(new Date())) + '"></div>' : '')
      + '</div>';
  }

  async function loadAnalytics() {
    const panel = document.getElementById('analytics');
    if (!panel) {
      return;
    }
    panel.setAttribute('aria-busy', 'true');
    const today = new Date();
    const from = new Date(today);
    from.setDate(from.getDate() - (range.days - 1));
    const params = { from: toIsoDate(from), to: toIsoDate(today) };
    const head = '<div class="panel__head"><div><h2 id="analytics-title">' + (isAdmin ? 'Team productivity' : 'Your productivity')
      + '</h2><p>' + esc(formatDate(params.from)) + ' to ' + esc(formatDate(params.to))
      + ' · hours are what employees reported, not verified measurements</p></div></div>';
    try {
      const html = isAdmin
        ? renderAdminAnalytics(await get('/api/dashboard/admin/analytics', { ...params, day: range.day }))
        : renderEmployeeAnalytics(await get('/api/dashboard/employee/analytics', params));
      panel.innerHTML = head + analyticsControls() + html;
    } catch (error) {
      panel.innerHTML = head + analyticsControls() + '<div class="panel__body"><p class="banner banner--error" role="alert">'
        + esc(error.message) + ' <button type="button" class="btn btn--ghost btn--sm" data-retry-analytics>Try again</button></p></div>';
    }
    panel.setAttribute('aria-busy', 'false');
  }

  document.addEventListener('click', (event) => {
    const presetButton = event.target.closest('#analytics [data-range]');
    if (presetButton) {
      range.days = Number(presetButton.dataset.range);
      loadAnalytics();
    } else if (event.target.closest('[data-retry-analytics]')) {
      loadAnalytics();
    }
  });
  document.addEventListener('change', (event) => {
    if (event.target.id === 'a-day' && event.target.value) {
      range.day = event.target.value;
      loadAnalytics();
    }
  });

  function renderEmployeeAnalytics(d) {
    const days = d.hoursByDay.map((p) => ({ date: p.date, value: Number(p.hours), note: plural(p.entries, 'entry', 'entries') }));
    const byDate = new Map(days.map((p) => [p.date, p]));
    const series = [];
    for (let cursor = new Date(d.from + 'T00:00:00'); toIsoDate(cursor) <= d.to; cursor.setDate(cursor.getDate() + 1)) {
      const iso = toIsoDate(cursor);
      series.push(byDate.get(iso) || { date: iso, value: 0 });
    }
    const items = statusItems(d.tasksByStatus, true);
    return ledger([
      { value: hoursLabel(d.reportedHours), label: 'Reported hours' },
      { value: d.completedTasks, label: 'Completed tasks' },
      { value: d.inProgressTasks, label: 'In-progress tasks' },
      { value: d.daysReported, label: 'Days reported' }
    ])
      + '<div class="chart-grid">'
      + '<section aria-labelledby="c1"><h3 id="c1" class="chart-note">Reported hours per day</h3>'
      + (d.entryCount === 0 ? emptyState({ title: 'No hours reported in this period', text: 'Log work and your daily hours will chart here.' })
        : lineChart(series, { yTitle: 'Reported hours', unit: 'h', xTitle: 'Work date',
          ariaLabel: 'Line chart of reported hours per day: ' + formatHours(d.reportedHours) + ' in total over ' + plural(d.daysReported, 'day') }))
      + '</section>'
      + '<section aria-labelledby="c2"><h3 id="c2" class="chart-note">My tasks by status</h3>'
      + (items.every((i) => i.value === 0) ? emptyState({ title: 'No tasks in this period', text: 'Entries you log appear here by status.' })
        : barChart(items, { yTitle: 'Tasks', ariaLabel: 'Bar chart of my tasks by status: ' + items.map((i) => i.value + ' ' + i.label.toLowerCase()).join(', ') })
          + legend(items))
      + '</section></div>'
      + '<section aria-labelledby="c3"><h3 id="c3" class="chart-note">Recent daily work entries</h3>'
      + (d.recentEntries.length === 0 ? emptyState({ title: 'No entries yet', text: 'Your latest entries show up here.' })
        : '<ul class="list">' + d.recentEntries.map((e) => '<li class="list__item"><div><strong>' + esc(e.taskTitle) + '</strong><br><span class="muted">'
          + esc(formatDate(e.workDate)) + ' · ' + esc(formatHours(e.hoursSpent)) + '</span></div><div>' + workChip(e.status) + '</div></li>').join('') + '</ul>'
        + '<p class="chart-note"><a href="/work.html">Open all daily work</a></p>')
      + '</section>';
  }

  function renderAdminAnalytics(d) {
    const hours = d.trend.map((p) => ({ date: p.date, value: Number(p.hours), note: plural(p.employeesReporting, 'person', 'people') + ' reporting' }));
    const coverage = d.trend.map((p) => ({ date: p.date, value: p.coveragePercent, note: p.weekend ? 'weekend' : undefined }));
    const items = statusItems(d.tasksByStatus, false);
    const s = d.selectedDay;
    const dayValue = s.weekend ? 'Weekend' : s.employeesReporting + ' of ' + s.expectedReporters;
    return ledger([
      { value: d.activeEmployees, label: 'Active employees', href: '/employees.html?status=ACTIVE' },
      { value: d.pendingLeaveRequests, label: 'Leave waiting for a decision', href: '/leaves.html?status=PENDING', attention: d.pendingLeaveRequests > 0 },
      { value: dayValue, label: 'Reporting on ' + formatDate(s.date) + (s.coveragePercent === null ? '' : ' (' + s.coveragePercent + '%)') },
      { value: hoursLabel(d.reportedHours), label: 'Reported hours in period' },
      { value: d.averageCoveragePercent === null ? '–' : d.averageCoveragePercent + '%', label: 'Average coverage (weekdays)' }
    ])
      + '<p class="chart-note">' + plural(s.entries, 'report') + ' (' + esc(formatHours(s.hours)) + ') submitted on '
      + esc(formatDate(s.date)) + '. Coverage uses today’s active headcount and does not subtract leave or holidays; '
      + '<a href="/work.html?view=missing">the missing-reports list</a> does account for leave.</p>'
      + '<div class="chart-grid">'
      + '<section class="span-2" aria-labelledby="c1"><h3 id="c1" class="chart-note">Reported hours per day (whole team)</h3>'
      + (d.entryCount === 0 ? emptyState({ title: 'No reports in this period', text: 'Reports appear here as people submit them.' })
        : lineChart(hours, { yTitle: 'Reported hours', unit: 'h', xTitle: 'Work date',
          ariaLabel: 'Line chart of team reported hours per day, ' + formatHours(d.reportedHours) + ' in total' }))
      + '</section>'
      + '<section aria-labelledby="c2"><h3 id="c2" class="chart-note">Report submission coverage</h3>'
      + (d.averageCoveragePercent === null ? emptyState({ title: 'No coverage data', text: 'There are no weekdays with active employees in this period yet.' })
        : lineChart(coverage, { yTitle: 'Employees who reported', unit: '%', xTitle: 'Work date', max: 100,
          ariaLabel: 'Line chart of the share of employees who submitted a report each weekday, averaging ' + d.averageCoveragePercent + ' percent' }))
      + '</section>'
      + '<section aria-labelledby="c3"><h3 id="c3" class="chart-note">Tasks by status</h3>'
      + (items.every((i) => i.value === 0) ? emptyState({ title: 'No tasks in this period', text: 'Submitted entries are counted here.' })
        : barChart(items, { yTitle: 'Tasks', ariaLabel: 'Bar chart of tasks by status: ' + items.map((i) => i.value + ' ' + i.label.toLowerCase()).join(', ') })
          + legend(items))
      + '</section></div>'
      + '<div class="chart-grid">'
      + '<section aria-labelledby="c4"><h3 id="c4" class="chart-note">Recent work reports</h3>'
      + (d.recentReports.length === 0 ? emptyState({ title: 'No reports yet', text: 'Submitted reports show up here.' })
        : '<ul class="list">' + d.recentReports.map((e) => '<li class="list__item"><div>' + person(e.employeeName, e.taskTitle)
          + '</div><div class="list__meta">' + esc(formatDate(e.workDate)) + '<br>' + esc(formatHours(e.hoursSpent)) + '</div></li>').join('') + '</ul>')
      + '</section>'
      + '<section aria-labelledby="c5"><h3 id="c5" class="chart-note">Recent leave activity</h3>'
      + (d.recentLeaveActivity.length === 0 ? emptyState({ title: 'No leave activity yet', text: 'Requests and decisions show up here.' })
        : '<ul class="list">' + d.recentLeaveActivity.map((l) => '<li class="list__item"><div>' + person(l.employeeName, formatRange(l.startDate, l.endDate))
          + '</div><div>' + statusChip(l.status) + '</div></li>').join('') + '</ul>')
      + '</section></div>';
  }

  main.addEventListener('click', async (event) => {
    if (event.target.closest('[data-log-work]')) {
      if (await openWorkForm()) {
        loadWorkWidget();
      }
      return;
    }
    const requestButton = event.target.closest('[data-new-request]');
    if (requestButton) {
      if (await openLeaveForm()) {
        load();
      }
      return;
    }
    const action = event.target.closest('[data-leave-act]');
    if (!action) {
      return;
    }
    const leave = leavesById.get(Number(action.dataset.leaveId));
    if (!leave) {
      return;
    }
    const kind = action.dataset.leaveAct;
    if (kind === 'view') {
      openLeaveDetail(leave, { user, onChanged: load });
    } else {
      const refresh = kind === 'approve' ? await approveLeave(leave) : await rejectLeave(leave);
      if (refresh) {
        load();
      }
    }
  });

  function remember(list) {
    list.forEach((leave) => leavesById.set(leave.id, leave));
  }

  function ribbon(counts) {
    const total = counts.reduce((sum, item) => sum + item.value, 0);
    if (total === 0) {
      return '';
    }
    return '<div class="ribbon-wrap"><div class="ribbon" role="img" aria-label="'
      + esc(counts.map((item) => item.value + ' ' + item.label.toLowerCase()).join(', ')) + '">'
      + counts.filter((item) => item.value > 0)
        .map((item) => '<span class="ribbon__seg ribbon__seg--' + item.key + '" data-n="' + item.value + '"></span>').join('')
      + '</div><div class="legend">'
      + counts.map((item) => '<span class="legend__item"><span class="legend__dot legend__dot--' + item.key
        + '"></span>' + esc(item.label) + ' ' + item.value + '</span>').join('')
      + '</div></div>';
  }

  function ledger(items) {
    return '<div class="ledger">' + items.map((item) => {
      const tag = item.href ? 'a' : 'div';
      return '<' + tag + ' class="ledger__item' + (item.attention ? ' is-attention' : '') + '"'
        + (item.href ? ' href="' + item.href + '"' : '') + '>'
        + '<span class="ledger__value">' + item.value + '</span>'
        + '<span class="ledger__label">' + esc(item.label) + '</span></' + tag + '>';
    }).join('') + '</div>';
  }

  /* ---------------- Admin ---------------- */
  function renderAdmin(d) {
    leavesById = new Map();
    remember(d.pendingQueue);
    remember(d.outToday);

    const maxDept = Math.max(1, ...d.departments.map((row) => row.count));

    return ledger([
      { value: d.totalEmployees, label: 'Employees', href: '/employees.html' },
      { value: d.activeEmployees, label: 'Active', href: '/employees.html?status=ACTIVE' },
      { value: d.pendingLeaves, label: 'Waiting for a decision', href: '/leaves.html?status=PENDING', attention: d.pendingLeaves > 0 },
      { value: d.approvedLeaves, label: 'Approved', href: '/leaves.html?status=APPROVED' },
      { value: d.rejectedLeaves, label: 'Rejected', href: '/leaves.html?status=REJECTED' },
      { value: d.onLeaveToday, label: 'Away today' }
    ])
      + '<div class="grid-main">'
      + '<div class="stack">'
      + '<section class="panel" aria-labelledby="queue-title"><div class="panel__head"><div>'
      + '<h2 id="queue-title">Waiting for a decision</h2>'
      + '<p>' + (d.pendingLeaves > 5 ? 'Newest 5 of ' + d.pendingLeaves : plural(d.pendingLeaves, 'request')) + '</p></div>'
      + (d.pendingLeaves > 0 ? '<a class="btn btn--ghost btn--sm" href="/leaves.html?status=PENDING">See all</a>' : '')
      + '</div>'
      + (d.pendingQueue.length === 0
        ? emptyState({ title: 'You’re all caught up', text: 'New leave requests will show up here as soon as someone sends one.' })
        : '<ul class="list">' + d.pendingQueue.map((leave) => queueItem(leave, user)).join('') + '</ul>')
      + '</section>'
      + '<section class="panel" aria-labelledby="outcomes-title"><div class="panel__head"><h2 id="outcomes-title">All requests so far</h2></div>'
      + ribbon([
        { key: 'pending', label: 'Pending', value: d.pendingLeaves },
        { key: 'approved', label: 'Approved', value: d.approvedLeaves },
        { key: 'rejected', label: 'Rejected', value: d.rejectedLeaves },
        { key: 'cancelled', label: 'Cancelled', value: d.cancelledLeaves }
      ]) + '</section>'
      + '</div>'
      + '<div class="stack">'
      + '<section class="panel" aria-labelledby="away-title"><div class="panel__head"><h2 id="away-title">Away today</h2></div>'
      + (d.outToday.length === 0
        ? emptyState({ title: 'Everyone’s in', text: 'Nobody has approved leave today.' })
        : '<ul class="list">' + d.outToday.map((leave) => '<li class="list__item">'
          + person(leave.employeeName, leave.department)
          + '<span class="list__meta">until ' + esc(formatDate(leave.endDate)) + '</span></li>').join('') + '</ul>')
      + '</section>'
      + '<section class="panel" aria-labelledby="dept-title"><div class="panel__head"><div><h2 id="dept-title">Active people by department</h2></div></div>'
      + (d.departments.length === 0
        ? emptyState({ title: 'No departments yet', text: 'Add your first employee to see the team take shape.' })
        : '<ul class="bars">' + d.departments.map((row) => '<li><div class="bar__row"><span>' + esc(row.department)
          + '</span><strong>' + row.count + '</strong></div><div class="bar__track"><div class="bar__fill" data-w="'
          + Math.round((row.count / maxDept) * 100) + '"></div></div></li>').join('') + '</ul>')
      + '</section>'
      + '</div></div>';
  }

  function queueItem(leave, me) {
    const own = leave.employeeId === me.id;
    return '<li class="list__item"><div>' + person(leave.employeeName, leave.department) + '</div>'
      + '<div class="list__meta">' + typeTag(leave.leaveType) + '<br>' + esc(formatRange(leave.startDate, leave.endDate))
      + ' · ' + plural(leave.days, 'day') + '</div>'
      + '<div class="actions">'
      + '<button type="button" class="btn btn--ghost btn--sm" data-leave-act="view" data-leave-id="' + leave.id + '">View</button> '
      + (own ? '' : '<button type="button" class="btn btn--sm" data-leave-act="approve" data-leave-id="' + leave.id + '">Approve</button> '
        + '<button type="button" class="btn btn--ghost btn--sm" data-leave-act="reject" data-leave-id="' + leave.id + '">Reject</button>')
      + '</div></li>';
  }

  /* ---------------- Employee ---------------- */
  function renderEmployee(d) {
    leavesById = new Map();
    remember(d.recentRequests);
    const p = d.profile;

    return '<section class="panel" aria-label="Your profile"><div class="profile-card">'
      + avatar(p.fullName, 'lg')
      + '<div><h2>' + esc(p.fullName) + '</h2><p class="muted">' + esc(p.jobTitle) + ' · ' + esc(p.department) + '</p></div>'
      + '<div class="toolbar__spacer"></div>'
      + '<button type="button" class="btn btn--accent" data-new-request>' + icon('plus') + '<span>Request leave</span></button>'
      + '</div></section>'
      + ledger([
        { value: d.totalRequests, label: 'Requests sent' },
        { value: d.pendingRequests, label: 'Waiting for a decision', attention: d.pendingRequests > 0 },
        { value: d.approvedRequests, label: 'Approved' },
        { value: d.rejectedRequests, label: 'Rejected' },
        { value: d.approvedDaysThisYear, label: 'Approved days this year' }
      ])
      + '<div class="grid-main">'
      + '<section class="panel" aria-labelledby="recent-title"><div class="panel__head"><h2 id="recent-title">Recent requests</h2>'
      + (d.totalRequests > 0 ? '<a class="btn btn--ghost btn--sm" href="/leaves.html">See all</a>' : '') + '</div>'
      + (d.recentRequests.length === 0
        ? emptyState({
          title: 'No leave requests yet',
          text: 'When you need time off, send a request and track the decision here.',
          actionHtml: '<button type="button" class="btn" data-new-request>Request leave</button>'
        })
        : '<ul class="list">' + d.recentRequests.map((leave) => '<li class="list__item"><div><strong>'
          + esc(formatRange(leave.startDate, leave.endDate)) + '</strong><br><span class="muted">'
          + typeTag(leave.leaveType) + ' · ' + plural(leave.days, 'working day') + '</span></div>'
          + '<div>' + statusChip(leave.status) + ' <button type="button" class="btn btn--ghost btn--sm" data-leave-act="view" data-leave-id="'
          + leave.id + '">View</button></div></li>').join('') + '</ul>')
      + '</section>'
      + '<section class="panel" aria-labelledby="mix-title"><div class="panel__head"><h2 id="mix-title">Where your requests stand</h2></div>'
      + (d.totalRequests === 0 ? emptyState({ title: 'Nothing to show yet', text: 'Your request history will appear here.' })
        : ribbon([
          { key: 'pending', label: 'Pending', value: d.pendingRequests },
          { key: 'approved', label: 'Approved', value: d.approvedRequests },
          { key: 'rejected', label: 'Rejected', value: d.rejectedRequests },
          { key: 'cancelled', label: 'Cancelled', value: d.cancelledRequests }
        ]))
      + '</section></div>';
  }

  load();
}

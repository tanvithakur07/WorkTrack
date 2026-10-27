import { ApiError, del, get } from '../api.js';
import { showPendingFlash } from '../forms.js';
import { mountShell } from '../shell.js';
import {
  confirmDialog, createDialog, debounce, emptyState, employmentChip, errorState, esc, formatDate,
  formatDateTime, icon, person, renderPager, roleChip, skeletonBlock, toast
} from '../ui.js';

const shell = await mountShell({
  page: 'employees',
  title: 'Employees',
  subtitle: 'Everyone on the team, in one list.',
  requires: 'ADMIN'
});

if (shell) {
  const { user, main } = shell;
  const params = new URLSearchParams(location.search);
  const state = {
    q: '',
    department: '',
    status: ['ACTIVE', 'INACTIVE'].includes(params.get('status')) ? params.get('status') : '',
    page: 0,
    size: 10,
    sortBy: 'fullName',
    direction: 'asc'
  };
  let people = new Map();
  let requestCounter = 0;

  main.innerHTML =
    '<section class="panel" aria-label="Employee list">'
    + '<div class="toolbar">'
    + '<div class="field field--grow"><label for="f-q">Search</label><div class="input-icon">' + icon('search')
    + '<input class="input" id="f-q" type="search" placeholder="Name or email" autocomplete="off"></div></div>'
    + '<div class="field"><label for="f-dept">Department</label><select class="select" id="f-dept"><option value="">All departments</option></select></div>'
    + '<div class="field"><label for="f-status">Status</label><select class="select" id="f-status">'
    + '<option value="">All statuses</option><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option></select></div>'
    + '<div class="toolbar__spacer"></div>'
    + '<a class="btn btn--accent" href="/employee-form.html">' + icon('plus') + '<span>Add employee</span></a>'
    + '</div>'
    + '<div id="list-region" aria-live="polite"></div>'
    + '<div class="pager" id="pager" hidden></div>'
    + '</section>';

  const region = document.getElementById('list-region');
  const pager = document.getElementById('pager');
  const searchInput = document.getElementById('f-q');
  const deptSelect = document.getElementById('f-dept');
  const statusSelect = document.getElementById('f-status');
  statusSelect.value = state.status;

  get('/api/employees/departments').then((departments) => {
    departments.forEach((name) => {
      const option = document.createElement('option');
      option.value = name;
      option.textContent = name;
      deptSelect.appendChild(option);
    });
  }).catch(() => { /* the filter just stays on "All departments" */ });

  const reload = () => { state.page = 0; load(); };
  searchInput.addEventListener('input', debounce(() => { state.q = searchInput.value.trim(); reload(); }, 300));
  deptSelect.addEventListener('change', () => { state.department = deptSelect.value; reload(); });
  statusSelect.addEventListener('change', () => { state.status = statusSelect.value; reload(); });

  function sortHeader(label, field) {
    const active = state.sortBy === field;
    const aria = active ? (state.direction === 'asc' ? 'ascending' : 'descending') : 'none';
    return '<th scope="col" aria-sort="' + aria + '"><button type="button" class="sort-btn" data-sort="' + field + '">'
      + esc(label) + '</button></th>';
  }

  async function load() {
    const ticket = ++requestCounter;
    region.innerHTML = skeletonBlock(6);
    try {
      const page = await get('/api/employees', {
        q: state.q, department: state.department, status: state.status,
        page: state.page, size: state.size, sortBy: state.sortBy, direction: state.direction
      });
      if (ticket !== requestCounter) {
        return; // a newer search is already on its way
      }
      people = new Map(page.content.map((p) => [p.id, p]));

      if (page.content.length === 0) {
        const filtered = state.q || state.department || state.status;
        region.innerHTML = emptyState(filtered
          ? { title: 'No one matches those filters', text: 'Try a different name, or clear the filters.' }
          : { title: 'No employees yet', text: 'Add your first employee to get started.',
            actionHtml: '<a class="btn" href="/employee-form.html">Add employee</a>' });
        renderPager(pager, null, {});
        return;
      }

      region.innerHTML = '<div class="table-wrap"><table class="table"><thead><tr>'
        + sortHeader('Employee', 'fullName') + '<th scope="col">ID</th>' + sortHeader('Department', 'department')
        + '<th scope="col">Job title</th>' + sortHeader('Joined', 'joiningDate') + sortHeader('Status', 'status')
        + '<th scope="col"><span class="sr-only">Actions</span></th></tr></thead><tbody>'
        + page.content.map(row).join('') + '</tbody></table></div>';

      renderPager(pager, page, {
        onPage: (n) => { state.page = n; load(); },
        onSize: (n) => { state.size = n; state.page = 0; load(); }
      });
    } catch (error) {
      if (ticket !== requestCounter) {
        return;
      }
      region.innerHTML = errorState(error.message);
      region.querySelector('[data-retry]').addEventListener('click', load);
      renderPager(pager, null, {});
    }
  }

  function row(p) {
    const self = p.id === user.id;
    return '<tr>'
      + '<td class="cell-primary">' + person(p.fullName, p.email) + '</td>'
      + '<td class="num" data-label="ID">' + esc(p.employeeCode) + '</td>'
      + '<td data-label="Department">' + esc(p.department) + '</td>'
      + '<td data-label="Job title"><span class="cell-clip">' + esc(p.jobTitle) + '</span></td>'
      + '<td class="num" data-label="Joined">' + esc(formatDate(p.joiningDate)) + '</td>'
      + '<td data-label="Status">' + employmentChip(p.status) + '</td>'
      + '<td class="actions">'
      + '<button type="button" class="btn btn--ghost btn--sm btn--icon" data-act="view" data-id="' + p.id + '" title="View details">' + icon('eye') + '<span class="btn-label">View</span></button>'
      + '<a class="btn btn--ghost btn--sm btn--icon" href="/employee-form.html?id=' + p.id + '" title="Edit">' + icon('edit') + '<span class="btn-label">Edit</span></a>'
      + (p.status === 'ACTIVE' && !self
        ? '<button type="button" class="btn btn--ghost btn--sm btn--icon" data-act="deactivate" data-id="' + p.id + '" title="Deactivate">' + icon('ban') + '<span class="btn-label">Deactivate</span></button>'
        : '')
      + '</td></tr>';
  }

  region.addEventListener('click', async (event) => {
    const sort = event.target.closest('[data-sort]');
    if (sort) {
      const field = sort.dataset.sort;
      state.direction = state.sortBy === field && state.direction === 'asc' ? 'desc' : 'asc';
      state.sortBy = field;
      state.page = 0;
      load();
      return;
    }
    const button = event.target.closest('[data-act]');
    if (!button) {
      return;
    }
    const target = people.get(Number(button.dataset.id));
    if (!target) {
      return;
    }
    if (button.dataset.act === 'view') {
      showDetails(target);
    } else {
      await deactivate(target);
    }
  });

  function showDetails(p) {
    const dialog = createDialog(
      '<div class="dialog__head"><h2 id="emp-title">Employee details</h2></div>'
      + '<div class="dialog__body">' + person(p.fullName, p.jobTitle)
      + '<div>' + employmentChip(p.status) + ' ' + roleChip(p.role) + '</div>'
      + '<dl class="facts">'
      + '<div><dt>Employee ID</dt><dd>' + esc(p.employeeCode) + '</dd></div>'
      + '<div><dt>Email</dt><dd>' + esc(p.email) + '</dd></div>'
      + '<div><dt>Department</dt><dd>' + esc(p.department) + '</dd></div>'
      + '<div><dt>Joined</dt><dd>' + esc(formatDate(p.joiningDate)) + '</dd></div>'
      + '<div><dt>Added to WorkTrack</dt><dd>' + esc(formatDateTime(p.createdAt)) + '</dd></div>'
      + '<div><dt>Last changed</dt><dd>' + esc(formatDateTime(p.updatedAt)) + '</dd></div>'
      + '</dl></div>'
      + '<div class="dialog__foot"><button type="button" class="btn btn--ghost" data-act="close">Close</button>'
      + '<a class="btn" href="/employee-form.html?id=' + p.id + '">Edit employee</a></div>',
      'emp-title');
    dialog.querySelector('[data-act="close"]').addEventListener('click', () => dialog.close());
  }

  async function deactivate(p) {
    const confirmed = await confirmDialog({
      title: 'Deactivate ' + p.fullName + '?',
      message: 'They will no longer be able to sign in and their pending leave requests will be cancelled. '
        + 'Their history is kept, and you can reactivate them by editing the employee.',
      confirmLabel: 'Deactivate',
      danger: true
    });
    if (!confirmed) {
      return;
    }
    try {
      await del('/api/employees/' + p.id);
      toast(p.fullName + ' is deactivated.');
      load();
    } catch (error) {
      toast(error instanceof ApiError ? error.message : 'Something went wrong. Please try again.', 'error');
      load();
    }
  }

  showPendingFlash();
  load();
}

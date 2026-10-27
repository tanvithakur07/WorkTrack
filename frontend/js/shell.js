/*
 * Builds the sidebar + top bar around a page and returns the signed-in user.
 *
 * The navigation shows only what the user's role can use, but that is a convenience:
 * the real access control happens on the server for every API call.
 */
import { api } from './api.js';
import { BRAND_MARK, avatar, esc, icon, wireThemeToggle } from './ui.js';

const ADMIN_NAV = [
  { id: 'dashboard', href: '/dashboard.html', label: 'Dashboard', icon: 'grid' },
  { id: 'employees', href: '/employees.html', label: 'Employees', icon: 'users' },
  { id: 'leaves', href: '/leaves.html', label: 'Leave requests', icon: 'calendar' },
  { id: 'work', href: '/work.html', label: 'Work reports', icon: 'clipboard' },
  { id: 'notifications', href: '/notifications.html', label: 'Email queue', icon: 'mail' },
  { id: 'audit', href: '/audit.html', label: 'Audit log', icon: 'shield' },
  { id: 'profile', href: '/profile.html', label: 'My profile', icon: 'user' }
];

const EMPLOYEE_NAV = [
  { id: 'dashboard', href: '/dashboard.html', label: 'Dashboard', icon: 'grid' },
  { id: 'leaves', href: '/leaves.html', label: 'My leave', icon: 'calendar' },
  { id: 'work', href: '/work.html', label: 'Daily work', icon: 'clipboard' },
  { id: 'profile', href: '/profile.html', label: 'My profile', icon: 'user' }
];

/**
 * @returns {Promise<{user: object, main: HTMLElement, setTitle: Function}|null>}
 *   null means the page should stop (redirected to sign-in, or the role may not open this page).
 */
export async function mountShell({ page, title, subtitle = '', requires = null }) {
  let user;
  try {
    user = await api('/api/auth/me');
  } catch (error) {
    if (error.status === 401) {
      return null; // api.js is already redirecting to the sign-in page
    }
    document.getElementById('app').innerHTML =
      '<div class="content"><div class="panel"><div class="empty"><h3>Can’t reach WorkTrack</h3><p>'
      + esc(error.message) + '</p><button type="button" class="btn" id="reload-page">Reload</button></div></div></div>';
    document.getElementById('reload-page').addEventListener('click', () => location.reload());
    return null;
  }

  const isAdmin = user.role === 'ADMIN';
  const nav = isAdmin ? ADMIN_NAV : EMPLOYEE_NAV;
  document.title = title + ' · WorkTrack';

  document.getElementById('app').innerHTML =
    '<a class="skip-link" href="#main">Skip to content</a>'
    + '<div class="shell" id="shell">'
    + '<aside class="sidebar" id="sidebar" aria-label="Sidebar">'
    + '<a class="brand" href="/dashboard.html" aria-label="WorkTrack home">' + BRAND_MARK + '<span class="brand__name">WorkTrack</span></a>'
    + '<nav class="nav" aria-label="Main">'
    + nav.map((item) => '<a class="nav__link" href="' + item.href + '"'
      + (item.id === page ? ' aria-current="page"' : '') + '>' + icon(item.icon) + '<span>' + esc(item.label) + '</span></a>').join('')
    + '</nav>'
    + '<div class="sidebar__foot"><strong>How leave is counted</strong>Weekends are free. Only Monday to Friday use up leave days.</div>'
    + '</aside>'
    + '<div class="scrim" id="scrim"></div>'
    + '<div class="main-col">'
    + '<header class="topbar">'
    + '<button type="button" class="btn btn--ghost btn--icon nav-toggle" id="nav-toggle" aria-label="Open menu" aria-expanded="false" aria-controls="sidebar">' + icon('menu') + '</button>'
    + '<div class="topbar__title"><h1 id="page-title">' + esc(title) + '</h1><p id="page-subtitle">' + esc(subtitle) + '</p></div>'
    + '<div class="topbar__actions">'
    + '<button type="button" class="btn btn--ghost btn--icon" id="theme-toggle"></button>'
    + '<a class="user-chip" href="/profile.html" aria-label="Your profile">' + avatar(user.fullName)
    + '<span class="user-chip__text"><span class="user-chip__name">' + esc(user.fullName) + '</span>'
    + '<span class="user-chip__role">' + (isAdmin ? 'Administrator' : esc(user.jobTitle)) + '</span></span></a>'
    + '<button type="button" class="btn btn--ghost btn--sm" id="sign-out">' + icon('logout') + '<span>Sign out</span></button>'
    + '</div></header>'
    + '<main class="content" id="main" tabindex="-1"></main>'
    + '</div></div>';

  wireThemeToggle(document.getElementById('theme-toggle'));

  const shell = document.getElementById('shell');
  const toggle = document.getElementById('nav-toggle');
  const setNav = (open) => {
    shell.classList.toggle('is-nav-open', open);
    toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    toggle.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
  };
  toggle.addEventListener('click', () => setNav(!shell.classList.contains('is-nav-open')));
  document.getElementById('scrim').addEventListener('click', () => setNav(false));
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      setNav(false);
    }
  });

  document.getElementById('sign-out').addEventListener('click', async () => {
    try {
      await api('/api/auth/logout', { method: 'POST', redirectOn401: false });
    } catch (error) {
      /* even if the call fails, leave the page; the server session simply expires */
    }
    try {
      sessionStorage.removeItem('wt-signed-in');
    } catch (error) {
      /* ignore */
    }
    location.replace('/login.html');
  });

  const main = document.getElementById('main');

  if (requires === 'ADMIN' && !isAdmin) {
    main.innerHTML = '<div class="panel"><div class="empty"><h3>This page is for administrators</h3>'
      + '<p>Your account doesn’t have access to it. Head back to your dashboard.</p>'
      + '<a class="btn" href="/dashboard.html">Go to dashboard</a></div></div>';
    return null;
  }

  return {
    user,
    main,
    setTitle(newTitle, newSubtitle = '') {
      document.getElementById('page-title').textContent = newTitle;
      document.getElementById('page-subtitle').textContent = newSubtitle;
      document.title = newTitle + ' · WorkTrack';
    }
  };
}

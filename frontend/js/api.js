/*
 * The only place the front end talks to the server.
 *
 * - Authentication is the server-side session cookie; JavaScript never sees or stores it.
 * - For POST/PUT/PATCH/DELETE the XSRF-TOKEN cookie value is echoed in the X-XSRF-TOKEN header,
 *   which is what the Spring Security CSRF filter checks.
 * - A 401 anywhere (except on the login call itself) sends the user to the sign-in page.
 */

export class ApiError extends Error {
  constructor(status, body) {
    super((body && body.message) || 'Something went wrong. Please try again.');
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = (body && body.fieldErrors) || {};
  }
}

function readCookie(name) {
  const prefix = name + '=';
  for (const part of document.cookie.split('; ')) {
    if (part.startsWith(prefix)) {
      return decodeURIComponent(part.slice(prefix.length));
    }
  }
  return null;
}

async function ensureCsrfCookie() {
  if (!readCookie('XSRF-TOKEN')) {
    await fetch('/api/auth/csrf', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
  }
}

function toQuery(params) {
  const search = new URLSearchParams();
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, value);
    }
  });
  const text = search.toString();
  return text ? '?' + text : '';
}

export function redirectToLogin() {
  if (location.pathname.endsWith('/login.html')) {
    return;
  }
  let expired = false;
  try {
    expired = sessionStorage.getItem('wt-signed-in') === '1';
  } catch (e) {
    expired = false;
  }
  location.replace('/login.html' + (expired ? '?expired=1' : ''));
}

export async function api(path, { method = 'GET', body, params, redirectOn401 = true } = {}) {
  const headers = { Accept: 'application/json' };
  const options = { method, headers, credentials: 'same-origin' };

  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  if (method !== 'GET' && method !== 'HEAD') {
    await ensureCsrfCookie();
    const token = readCookie('XSRF-TOKEN');
    if (token) {
      headers['X-XSRF-TOKEN'] = token;
    }
  }

  let response;
  try {
    response = await fetch(path + toQuery(params), options);
  } catch (networkError) {
    throw new ApiError(0, { message: 'Cannot reach the server. Check your connection and try again.' });
  }

  if (response.status === 204) {
    return null;
  }

  let data = null;
  try {
    data = await response.json();
  } catch (parseError) {
    data = null;
  }

  if (!response.ok) {
    if (response.status === 401 && redirectOn401) {
      redirectToLogin();
    }
    throw new ApiError(response.status, data);
  }
  return data;
}

export const get = (path, params) => api(path, { params });
export const post = (path, body) => api(path, { method: 'POST', body });
export const put = (path, body) => api(path, { method: 'PUT', body });
export const patch = (path, body) => api(path, { method: 'PATCH', body });
export const del = (path) => api(path, { method: 'DELETE' });

/**
 * Downloads a file (CSV / Excel export) through fetch, so a refusal (400 "too many rows", 403, 401) can be
 * shown as a normal message instead of navigating the browser to a JSON error page. The session cookie is
 * sent as usual; nothing is stored. The file name comes from the server's Content-Disposition header.
 */
export async function download(path, params) {
  let response;
  try {
    response = await fetch(path + toQuery(params), { credentials: 'same-origin', headers: { Accept: '*/*' } });
  } catch (networkError) {
    throw new ApiError(0, { message: 'Cannot reach the server. Check your connection and try again.' });
  }
  if (!response.ok) {
    let data = null;
    try {
      data = await response.json();
    } catch (parseError) {
      data = null;
    }
    if (response.status === 401) {
      redirectToLogin();
    }
    throw new ApiError(response.status, data);
  }
  const disposition = response.headers.get('Content-Disposition') || '';
  const match = /filename="?([^";]+)"?/i.exec(disposition);
  const filename = match ? match[1] : 'worktrack-export';
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
  return filename;
}

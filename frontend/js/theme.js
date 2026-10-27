/*
 * Applies the saved light/dark theme before the page paints, so there is no flash.
 * Loaded as a normal (blocking) script in <head>. Only the theme name is stored in
 * localStorage; nothing sensitive ever is.
 */
(function () {
  var theme = 'light';
  try {
    theme = localStorage.getItem('wt-theme');
    if (theme !== 'light' && theme !== 'dark') {
      theme = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }
  } catch (e) {
    theme = 'light';
  }
  document.documentElement.setAttribute('data-theme', theme);
})();

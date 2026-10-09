'use strict';
// ====================== Light / Dark theme ======================
function savedTheme() {
  if (IN_APP) return new URLSearchParams(location.search).get('theme') || 'system'; // in the app: follow the app's setting
  try { return localStorage.getItem('camerold-theme') || 'system'; } catch { return 'system'; }
}
function applyTheme(v) {
  if (v === 'light' || v === 'dark') document.documentElement.dataset.theme = v;
  else delete document.documentElement.dataset.theme;
  const dark = v === 'dark' || (v !== 'light' && matchMedia('(prefers-color-scheme: dark)').matches);
  $('themeColor').content = dark ? '#0e1014' : '#f4f5f7';
}
function initTheme() {
  applyTheme(savedTheme());
  matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', () => applyTheme(savedTheme()));
}

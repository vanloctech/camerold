'use strict';
// ====================== Show password / share a saved camera by QR ======================
$('bEye').onclick = () => {
  const show = $('pass').type === 'password';
  $('pass').type = show ? 'text' : 'password';
  $('eyeIcon').setAttribute('href', show ? '#i-eyeoff' : '#i-eye');
  const label = t(show ? 'a_hide_pass' : 'a_show_pass');
  $('bEye').setAttribute('aria-label', label); $('bEye').title = label;
};

/** Same link as the camera's own QR, so the app, the web scanner and phone cameras all understand it. */
function joinUri(room, pass, broker = ownBroker()) {
  let s = `camerold://join?r=${encodeURIComponent(room)}&k=${encodeURIComponent(pass)}`;
  if (broker) s += `&b=${encodeURIComponent(broker.url)}` + (broker.backup ? '' : '&p=0');
  return s;
}

/**
 * QR in the app's style (same design as QrCode.kt on Android): soft-rounded modules, rounded corner markers in the accent
 * gradient, camera badge in the middle. Error correction H (30%) keeps it readable under the badge.
 */
function styledQr(text, o = {}) {
  // Longer links (own broker inside) get a lighter error correction so the code doesn't get too dense
  const q = qrcode(0, text.length <= 150 ? 'H' : 'Q'); q.addData(text); q.make();
  const n = q.getModuleCount(), M = 2, S = n + 2 * M;
  const bm = Math.floor(n * (o.badge ?? 0.22)) | 1, b0 = (n - bm) / 2;
  const inBadge = (x, y) => x >= b0 && x < b0 + bm && y >= b0 && y < b0 + bm;
  const inFinder = (x, y) => (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7);
  // Soft-rounded modules with thin gaps: rounder dots with wider gaps decoded noticeably worse in tests
  const d = o.d ?? 0.95, p = (1 - d) / 2, r = d * (o.r ?? 0.25);
  let dots = '';
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    if (!q.isDark(y, x) || inFinder(x, y) || inBadge(x, y)) continue;
    dots += `<rect x="${x + M + p}" y="${y + M + p}" width="${d}" height="${d}" rx="${r}"/>`;
  }
  const finder = (fx, fy) => {
    const x = fx + M, y = fy + M;
    return `<path fill-rule="evenodd" d="${rrect(x, y, 7, 2.2)}${rrect(x + 1, y + 1, 5, 1.4)}"/>` +
      `<rect x="${x + 2}" y="${y + 2}" width="3" height="3" rx="1"/>`;
  };
  const bx = b0 + M, ins = 0.5, tw = bm - 2 * ins, s = tw * 0.62 / 24;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${S} ${S}" shape-rendering="geometricPrecision">
    <defs><linearGradient id="qrg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#5b95ff"/><stop offset="1" stop-color="#2a62e8"/></linearGradient></defs>
    <rect width="${S}" height="${S}" fill="#fff"/>
    <g fill="#161a23">${dots}</g>
    <g fill="url(#qrg)">${finder(0, 0)}${finder(n - 7, 0)}${finder(0, n - 7)}</g>
    <rect x="${bx}" y="${bx}" width="${bm}" height="${bm}" rx="${bm * 0.3}" fill="#fff"/>
    <rect x="${bx + ins}" y="${bx + ins}" width="${tw}" height="${tw}" rx="${tw * 0.28}" fill="url(#qrg)"/>
    <g transform="translate(${bx + bm / 2 - 12 * s} ${bx + bm / 2 - 12 * s}) scale(${s})" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <path d="M3 8.5A2.5 2.5 0 0 1 5.5 6h1.8l1.6-2h6.2l1.6 2h1.8A2.5 2.5 0 0 1 21 8.5v9a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5z"/><circle cx="12" cy="13" r="3.8"/>
    </g></svg>`;
}
/** Rounded-rectangle path (square w×w at x,y, corner radius r). */
const rrect = (x, y, w, r) =>
  `M${x + r} ${y}h${w - 2 * r}a${r} ${r} 0 0 1 ${r} ${r}v${w - 2 * r}a${r} ${r} 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a${r} ${r} 0 0 1 ${-r} ${-r}v${-(w - 2 * r)}a${r} ${r} 0 0 1 ${r} ${-r}z`;

const QRD = { timer: null };
/** QR code of a saved camera (with its own server, if any), for another phone to scan. */
async function openShareQr(c) {
  if (!c) return;
  if (!window.qrcode) { try { await loadScript('vendor/qrcode.js'); } catch { return; } }
  $('qrBox').innerHTML = styledQr(joinUri(c.room, c.pass, c.broker || null));
  $('qrDlg').hidden = false;
  clearTimeout(QRD.timer);
  QRD.timer = setTimeout(closeShareQr, 90000); // don't leave the password on screen
}
function closeShareQr() {
  clearTimeout(QRD.timer);
  $('qrDlg').hidden = true; $('qrBox').innerHTML = '';
}
$('qrDlg').addEventListener('click', closeShareQr); // tap anywhere to close
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('qrDlg').hidden) closeShareQr(); });

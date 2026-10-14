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
 * gradient, the app logo in the middle. Error correction H (30%) keeps it readable under the badge.
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
  const bx = b0 + M, ins = 0.5, tw = bm - 2 * ins;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${S} ${S}" shape-rendering="geometricPrecision">
    <defs><linearGradient id="qrg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#5b95ff"/><stop offset="1" stop-color="#2a62e8"/></linearGradient></defs>
    <rect width="${S}" height="${S}" fill="#fff"/>
    <g fill="#161a23">${dots}</g>
    <g fill="url(#qrg)">${finder(0, 0)}${finder(n - 7, 0)}${finder(0, n - 7)}</g>
    <rect x="${bx}" y="${bx}" width="${bm}" height="${bm}" rx="${bm * 0.3}" fill="#fff"/>
    <g transform="translate(${bx + ins} ${bx + ins}) scale(${tw / 108})">${LOGO}</g></svg>`;
}
/** The app logo (web/icon.svg) in its 108×108 box: a phone whose screen is a camera lens. */
const LOGO = `<defs><linearGradient id="qrl" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#5A8FFF"/><stop offset="1" stop-color="#1E4ED8"/></linearGradient>
  <radialGradient id="qrlg" cx="52" cy="51.5" r="8.5" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#3D7BFF"/><stop offset=".6" stop-color="#1A3C9E"/><stop offset="1" stop-color="#0E2361"/></radialGradient></defs>
  <rect width="108" height="108" rx="30" fill="url(#qrl)"/>
  <g transform="translate(54 54) scale(1.05) translate(-54 -54)">
    <rect x="36" y="27" width="36" height="54" rx="9" fill="none" stroke="#fff" stroke-width="5"/>
    <circle cx="54" cy="54" r="11" fill="#fff"/><circle cx="54" cy="54" r="6.5" fill="url(#qrlg)"/>
    <circle cx="51.8" cy="51.8" r="1.8" fill="#fff" fill-opacity=".9"/>
  </g>`;
/** Rounded-rectangle path (square w×w at x,y, corner radius r). */
const rrect = (x, y, w, r) =>
  `M${x + r} ${y}h${w - 2 * r}a${r} ${r} 0 0 1 ${r} ${r}v${w - 2 * r}a${r} ${r} 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a${r} ${r} 0 0 1 ${-r} ${-r}v${-(w - 2 * r)}a${r} ${r} 0 0 1 ${r} ${-r}z`;

const QRD = { timer: null, name: '', role: '' };
/**
 * QR code of a saved camera (with its own server, if any), for another phone to scan.
 * viewOnly: c.pass is the camera's view-only code; the caption says what the other phone will be able to do.
 */
async function openShareQr(c, viewOnly = false) {
  if (!c) return;
  if (!window.qrcode) { try { await loadScript('vendor/qrcode.js'); } catch { return; } }
  $('qrBox').innerHTML = styledQr(joinUri(c.room, c.pass, c.broker || null));
  const known = viewOnly || (typeof c.viewPass === 'string' && c.viewPass.length >= 8); // else an older camera: one code
  QRD.name = c.name || c.room; QRD.role = known ? t(viewOnly ? 'qr_view' : 'qr_full') : '';
  $('qrShare').hidden = !qrCanShare();
  const cap = $('qrCap');
  cap.hidden = !known;
  if (known) {
    const b = document.createElement('b'); b.textContent = t(viewOnly ? 'qr_view' : 'qr_full');
    cap.replaceChildren(b, t(viewOnly ? 'qr_view_sub' : 'qr_full_sub'));
  }
  $('qrDlg').hidden = false;
  clearTimeout(QRD.timer);
  QRD.timer = setTimeout(closeShareQr, 90000); // don't leave the password on screen
}
function closeShareQr() {
  clearTimeout(QRD.timer);
  $('qrDlg').hidden = true; $('qrBox').innerHTML = ''; $('qrCap').hidden = true;
}
$('qrDlg').addEventListener('click', e => { if (!e.target.closest('.qr-acts')) closeShareQr(); }); // tap anywhere else to close

// ---------- The code as a picture: save it, or send it to the phone that will watch ----------
/** The QR with the camera's name and which link it is under it (PNG, like the app's). */
async function qrPng() {
  const img = new Image();
  img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent($('qrBox').innerHTML);
  await img.decode();
  const W = 1080, pad = 60, cv = document.createElement('canvas');
  cv.width = W; cv.height = W + 190;
  const g = cv.getContext('2d');
  g.fillStyle = '#fff'; g.fillRect(0, 0, cv.width, cv.height);
  g.drawImage(img, pad, pad, W - 2 * pad, W - 2 * pad);
  const line = (s, y) => {
    while (s.length > 1 && g.measureText(s).width > W - 2 * pad) s = s.slice(0, -2) + '…';
    g.fillText(s, W / 2, y);
  };
  g.textAlign = 'center';
  g.fillStyle = '#161a23'; g.font = 'bold 52px system-ui, sans-serif'; line(QRD.name, W + 40);
  g.fillStyle = '#6a707b'; g.font = '38px system-ui, sans-serif'; line([QRD.role, 'Camerold'].filter(Boolean).join(' · '), W + 110);
  return new Promise(ok => cv.toBlob(ok, 'image/png'));
}
const qrFileName = () => `Camerold QR ${QRD.name}${QRD.role ? ` (${QRD.role})` : ''}.png`.replace(/[\\/:*?"<>|]/g, ' ');
const blobB64 = b => new Promise(ok => { const r = new FileReader(); r.onload = () => ok(String(r.result).split(',')[1]); r.readAsDataURL(b); });
/** Share sheet (app, phones), else copying the picture (desktop browsers): either way it lands in a chat. */
const qrCanShare = () => (IN_APP && !!window.CameroldApp?.shareImage) ||
  !!navigator.canShare?.({ files: [new File([''], 'q.png', { type: 'image/png' })] }) || (!!window.ClipboardItem && !!navigator.clipboard?.write);

$('qrSave').onclick = async () => {
  const blob = await qrPng(); if (!blob) return;
  if (IN_APP && window.CameroldApp?.saveImage) {
    const res = CameroldApp.saveImage(await blobB64(blob), qrFileName());
    toast(t(res === 'ok' ? 'snap_saved_app' : res === 'perm' ? 'snap_perm' : 'snap_fail'), 2600);
    return;
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = qrFileName();
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 10000);
  toast(t('qr_saved'), 2000);
};
$('qrShare').onclick = async () => {
  if (IN_APP && window.CameroldApp?.shareImage) {
    const blob = await qrPng(); if (blob) CameroldApp.shareImage(await blobB64(blob), qrFileName(), t('qr_share'));
    return;
  }
  const file = new File([await qrPng()], qrFileName(), { type: 'image/png' });
  if (navigator.canShare?.({ files: [file] })) {
    try { await navigator.share({ files: [file], title: QRD.name }); } catch {} // closed without sharing
    return;
  }
  // Safari needs the item while still in the click: a promise for the picture
  try { await navigator.clipboard.write([new ClipboardItem({ 'image/png': qrPng() })]); toast(t('qr_copied'), 2600); }
  catch { toast(t('snap_fail'), 2000); }
};
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('qrDlg').hidden) closeShareQr(); });

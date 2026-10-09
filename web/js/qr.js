'use strict';
// ====================== QR code: scan with the camera, or read an image (upload / paste / drop) ======================
const QR = { stream: null, timer: null, detector: null };

/** camerold://join?r=&k=[&b=broker&p=0] or a web link #r=&k=… -> { r, k, b, p } (b only if it's a wss:// broker). */
function parseJoin(text) {
  try {
    const u = new URL(text.trim());
    let p = u.searchParams;
    if (u.protocol !== 'camerold:') p = new URLSearchParams(u.hash.slice(1)); // web link of the form #r=&k=
    const r = p.get('r'), k = p.get('k'), b = p.get('b');
    return r && k ? { r, k, b: b && isBrokerUrl(b) ? b : null, p: p.get('p') } : null;
  } catch { return null; }
}
/** Fill in what the QR code carries (code, password, connection server) and connect. */
function useJoin(j) {
  $('room').value = j.r; $('pass').value = j.k;
  if (j.b) useLinkBroker(j.b, j.p !== '0');
  start();
}

function loadScript(src) {
  return new Promise((ok, fail) => { const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = fail; document.head.appendChild(s); });
}
async function qrDecoder() {
  if (!QR.detector && 'BarcodeDetector' in window) {
    try { if ((await BarcodeDetector.getSupportedFormats()).includes('qr_code')) QR.detector = new BarcodeDetector({ formats: ['qr_code'] }); } catch {}
  }
}
async function loadJsQr() { if (!window.jsQR) { try { await loadScript('vendor/jsQR.js'); } catch {} } }
/**
 * Reads a QR code from a video frame or image; null if none. The browser's detector goes first (fast); jsQR is the
 * fallback when there's no detector, it finds nothing, or it doesn't answer in time.
 */
async function readQr(source, w, h, maxSide, cv) {
  if (QR.detector) {
    try {
      const r = await Promise.race([QR.detector.detect(source), new Promise(ok => setTimeout(() => ok([]), 1500))]);
      if (r[0]) return r[0].rawValue;
    } catch {}
  }
  await loadJsQr();
  if (!window.jsQR) return null;
  try {
    const sc = Math.min(1, maxSide / Math.max(w, h));
    cv.width = w * sc | 0; cv.height = h * sc | 0;
    const ctx = cv.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(source, 0, 0, cv.width, cv.height);
    const img = ctx.getImageData(0, 0, cv.width, cv.height);
    return jsQR(img.data, img.width, img.height, { inversionAttempts: 'attemptBoth' })?.data || null;
  } catch { return null; }
}

// ---------- Camera ----------
async function openScanner() {
  $('scanner').classList.remove('has-frames');
  $('scanner').hidden = false;
  $('scanHint').textContent = t('scan_opening');
  try {
    QR.stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 } }, audio: false });
  } catch (e) {
    $('scanHint').textContent = t('scan_no_cam'); // e.g. a PC without a webcam: the "pick an image" button stays available
    return;
  }
  const v = $('scanVideo');
  v.onplaying = () => { $('scanner').classList.add('has-frames'); $('scanHint').innerHTML = t('scan_hint'); };
  v.srcObject = QR.stream; await v.play().catch(() => {});
  await qrDecoder();
  const cv = document.createElement('canvas');
  const tick = async () => {
    if ($('scanner').hidden) return;
    if (!v.videoWidth) { QR.timer = setTimeout(tick, 250); return; }
    const text = await readQr(v, v.videoWidth, v.videoHeight, 720, cv);
    const j = text && parseJoin(text);
    if (j) {
      if (navigator.vibrate) navigator.vibrate(40);
      closeScanner();
      useJoin(j);
      return;
    }
    if (text) $('scanHint').textContent = t('scan_not_ours');
    QR.timer = setTimeout(tick, 200);
  };
  tick();
}
function closeScanner() {
  clearTimeout(QR.timer);
  if (QR.stream) QR.stream.getTracks().forEach(t => t.stop());
  QR.stream = null; $('scanVideo').srcObject = null; $('scanVideo').onplaying = null;
  $('scanner').classList.remove('has-frames'); $('scanner').hidden = true;
}

// ---------- Image (no webcam on a PC: screenshot of the camera's QR code) ----------
async function readQrImage(file) {
  if (!file || !file.type.startsWith('image/')) return;
  closeScanner();
  $('loginMsg').textContent = t('qr_reading');
  await qrDecoder();
  let text = null;
  try {
    const bmp = await createImageBitmap(file);
    text = await readQr(bmp, bmp.width, bmp.height, 1600, document.createElement('canvas'));
  } catch {}
  const j = text && parseJoin(text);
  if (!j) { $('loginMsg').textContent = t(text ? 'scan_not_ours' : 'qr_not_found'); return; }
  $('loginMsg').textContent = '';
  useJoin(j);
}
function pickQrImage() { $('qrFile').value = ''; $('qrFile').click(); }

$('scanBtn').onclick = openScanner;
$('scanClose').onclick = closeScanner;
$('uploadQr').onclick = pickQrImage;
$('scanPick').onclick = pickQrImage;
$('qrFile').onchange = () => readQrImage($('qrFile').files[0]);
// Paste a screenshot (Ctrl+V) or drop an image anywhere on the connect screen
document.addEventListener('paste', e => {
  if ($('login').hidden) return;
  const item = [...(e.clipboardData?.items || [])].find(i => i.type.startsWith('image/'));
  if (item) { e.preventDefault(); readQrImage(item.getAsFile()); }
});
document.addEventListener('dragover', e => { if (!$('login').hidden && e.dataTransfer?.types.includes('Files')) e.preventDefault(); });
document.addEventListener('drop', e => {
  if ($('login').hidden) return;
  const f = [...(e.dataTransfer?.files || [])].find(f => f.type.startsWith('image/'));
  if (f) { e.preventDefault(); readQrImage(f); }
});

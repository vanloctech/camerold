'use strict';
// ====================== Snapshot: save the current picture ======================
/** Full video frame at the resolution being received (e.g. 3840×2160 for 4K), turned like the view. */
function snapshotCanvas() {
  const v = $('video');
  if (!v.videoWidth) return null;
  const swap = Z.rot % 180 !== 0;
  const c = document.createElement('canvas');
  c.width = swap ? v.videoHeight : v.videoWidth; c.height = swap ? v.videoWidth : v.videoHeight;
  const x = c.getContext('2d');
  x.translate(c.width / 2, c.height / 2); x.rotate(Z.rot * Math.PI / 180);
  x.drawImage(v, -v.videoWidth / 2, -v.videoHeight / 2);
  return c;
}
function snapshotName() {
  const d = new Date(), p = n => String(n).padStart(2, '0');
  const stamp = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}.${p(d.getMinutes())}.${p(d.getSeconds())}`;
  const name = (S.camName || 'Camera').replace(/[\\/:*?"<>|]+/g, ' ').trim();
  return `Camerold ${name} ${stamp}.jpg`;
}
async function takeSnapshot() {
  const c = snapshotCanvas();
  if (!c) return;
  // Shutter feedback
  const f = $('flash'); f.classList.remove('go'); void f.offsetWidth; f.classList.add('go');
  showCtl();
  const blob = await new Promise(ok => c.toBlob(ok, 'image/jpeg', 0.92));
  if (!blob) return;
  const name = snapshotName();
  if (IN_APP && window.CameroldApp?.saveImage) {
    // The app saves it to the phone's Pictures (a WebView can't download blobs)
    const b64 = await new Promise(ok => { const r = new FileReader(); r.onload = () => ok(String(r.result).split(',')[1]); r.readAsDataURL(blob); });
    const res = CameroldApp.saveImage(b64, name);
    toast(t(res === 'ok' ? 'snap_saved_app' : res === 'perm' ? 'snap_perm' : 'snap_fail'), 2600);
    return;
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = name;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 10000);
  toast(t('snap_saved'), 2000);
}

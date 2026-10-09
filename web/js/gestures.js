'use strict';
// ====================== Pinch zoom / pan / rotate ======================
const Z = { s: 1, tx: 0, ty: 0, rot: 0, fill: false }; // fill: crop to cover the screen instead of fitting inside it
const stage = $('stage'), zoomEl = $('zoom'), video = $('video');
const MAX_ZOOM = 12;

function layoutVideo() {
  const W = stage.clientWidth, H = stage.clientHeight;
  if (!W || !H) return;
  const swap = Z.rot % 180 !== 0;
  const vw = swap ? H : W, vh = swap ? W : H;
  Object.assign(video.style, {
    width: vw + 'px', height: vh + 'px', left: (W - vw) / 2 + 'px', top: (H - vh) / 2 + 'px',
    transform: `rotate(${Z.rot}deg)`, objectFit: Z.fill ? 'cover' : 'contain',
  });
  applyZoom();
}
function applyZoom() {
  const W = stage.clientWidth, H = stage.clientHeight;
  Z.s = Math.min(MAX_ZOOM, Math.max(1, Z.s));
  Z.tx = Math.min(0, Math.max(W - W * Z.s, Z.tx));
  Z.ty = Math.min(0, Math.max(H - H * Z.s, Z.ty));
  zoomEl.style.transform = `translate(${Z.tx}px, ${Z.ty}px) scale(${Z.s})`;
  const pc = $('pinchChip');
  pc.hidden = Z.s < 1.05;
  pc.textContent = t('pinch', { z: Z.s.toFixed(1) });
}
function zoomAt(px, py, newS) {
  newS = Math.min(MAX_ZOOM, Math.max(1, newS));
  const cx = (px - Z.tx) / Z.s, cy = (py - Z.ty) / Z.s;
  Z.s = newS; Z.tx = px - cx * newS; Z.ty = py - cy * newS;
  applyZoom();
}
/** Fit (whole frame, maybe black bars) or Fill (no bars, edges cropped). Display only – the camera sends the same picture. */
function setFill(on) {
  Z.fill = !!on; layoutVideo(); savePatch({ fill: Z.fill });
  setSeg('selFit', Z.fill ? 'fill' : 'fit');
}
function rotateView() {
  Z.rot = (Z.rot + 90) % 360; Z.s = 1; Z.tx = Z.ty = 0; layoutVideo(); showCtl();
  // Remembered per saved camera: each phone may be mounted its own way
  const cam = S.room && findCam(S.room);
  if (cam) putCam({ room: cam.room, rot: Z.rot }, false);
}
/** Turn the view the way it was last time for this camera (0 / 90 / 180 / 270). */
function restoreRotation(room) {
  const r = +(findCam(room)?.rot) || 0;
  Z.rot = [0, 90, 180, 270].includes(r) ? r : 0; Z.s = 1; Z.tx = Z.ty = 0;
  layoutVideo();
}

const pts = new Map();
let gesture = null, lastTap = 0, moved = false;
function rel(e) { const r = stage.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }
function beginGesture() {
  const p = [...pts.values()];
  if (p.length === 1) gesture = { p0: p[0], tx: Z.tx, ty: Z.ty, s: Z.s };
  else if (p.length >= 2) {
    const d = Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y) || 1;
    const mid = { x: (p[0].x + p[1].x) / 2, y: (p[0].y + p[1].y) / 2 };
    gesture = { d, mid, tx: Z.tx, ty: Z.ty, s: Z.s };
  } else gesture = null;
}
let lpTimer = null, longPressed = false;
stage.addEventListener('pointerdown', e => {
  stage.setPointerCapture(e.pointerId);
  pts.set(e.pointerId, rel(e));
  clearTimeout(lpTimer);
  if (pts.size === 1) {
    moved = false; longPressed = false;
    const p0 = rel(e);
    lpTimer = setTimeout(() => { if (!moved && pts.size === 1) { longPressed = true; focusAt(p0); } }, 550);
  }
  beginGesture();
});
stage.addEventListener('pointermove', e => {
  if (!pts.has(e.pointerId) || !gesture) return;
  pts.set(e.pointerId, rel(e));
  const p = [...pts.values()];
  if (p.length === 1 && gesture.p0) {
    const dx = p[0].x - gesture.p0.x, dy = p[0].y - gesture.p0.y;
    if (Math.abs(dx) + Math.abs(dy) > 6) { moved = true; clearTimeout(lpTimer); }
    if (Z.s > 1) { Z.tx = gesture.tx + dx; Z.ty = gesture.ty + dy; applyZoom(); }
  } else if (p.length >= 2 && gesture.d) {
    moved = true;
    const d = Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y);
    const mid = { x: (p[0].x + p[1].x) / 2, y: (p[0].y + p[1].y) / 2 };
    const s = Math.min(MAX_ZOOM, Math.max(1, gesture.s * d / gesture.d));
    const cx = (gesture.mid.x - gesture.tx) / gesture.s, cy = (gesture.mid.y - gesture.ty) / gesture.s;
    Z.s = s; Z.tx = mid.x - cx * s; Z.ty = mid.y - cy * s; applyZoom();
  }
});
function endPointer(e) {
  if (!pts.has(e.pointerId)) return;
  const p = pts.get(e.pointerId);
  pts.delete(e.pointerId);
  clearTimeout(lpTimer);
  beginGesture();
  if (longPressed) { if (pts.size === 0) longPressed = false; return; }
  if (pts.size === 0 && !moved && e.type === 'pointerup') {
    const now = Date.now();
    if (now - lastTap < 300) { // double tap: zoom 3× at the tap point / zoom back out
      lastTap = 0;
      if (Z.s > 1.05) { Z.s = 1; Z.tx = 0; Z.ty = 0; applyZoom(); } else zoomAt(p.x, p.y, 3);
    } else {
      lastTap = now;
      setTimeout(() => { if (lastTap === now) toggleUi(); }, 300);
    }
  }
}
stage.addEventListener('pointerup', endPointer);
stage.addEventListener('pointercancel', endPointer);
stage.addEventListener('wheel', e => {
  e.preventDefault();
  const p = rel(e);
  zoomAt(p.x, p.y, Z.s * Math.exp(-e.deltaY * 0.0015));
}, { passive: false });

/**
 * Screen point -> normalized coordinates (0..1) on the displayed camera frame (rotated upright).
 * Undoes in order: pinch zoom/pan, viewer-side 90° rotation, black bars from object-fit: contain.
 */
function videoPointFromStage(px, py) {
  if (!video.videoWidth) return null;
  const W = stage.clientWidth, H = stage.clientHeight;
  const x = (px - Z.tx) / Z.s, y = (py - Z.ty) / Z.s;
  const a = -Z.rot * Math.PI / 180, dx = x - W / 2, dy = y - H / 2;
  const rx = dx * Math.cos(a) - dy * Math.sin(a), ry = dx * Math.sin(a) + dy * Math.cos(a);
  const swap = Z.rot % 180 !== 0, vw = swap ? H : W, vh = swap ? W : H;
  const ar = video.videoWidth / video.videoHeight;
  // Size of the frame as drawn: "contain" fits inside the element, "cover" fills it and overflows
  let dw = vw, dh = vw / ar;
  if (Z.fill ? dh < vh : dh > vh) { dh = vh; dw = vh * ar; }
  const u = rx / dw + 0.5, v = ry / dh + 0.5;
  return u < 0 || u > 1 || v < 0 || v > 1 ? null : { u, v };
}
function focusAt(p) {
  if (!S.pc || S.pc.connectionState !== 'connected') return;
  if (S.canFocusPoint === false) { toast(t('no_point')); return; }
  const q = videoPointFromStage(p.x, p.y);
  if (!q) return;
  if (navigator.vibrate) navigator.vibrate(20);
  const ring = $('focusRing');
  ring.style.left = p.x + 'px'; ring.style.top = (p.y + stage.offsetTop) + 'px';
  ring.className = ''; void ring.offsetWidth; ring.className = 'show';
  setTimeout(() => { ring.className = 'show fade-out'; }, 1200);
  setSeg('selFocus', 'point'); $('rowFdist').hidden = true;
  toast(t('focused'));
  sendCam({ t: 'cfg', focus: 'point', fx: +q.u.toFixed(4), fy: +q.v.toFixed(4) });
}

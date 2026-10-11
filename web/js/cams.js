'use strict';
// ====================== Saved cameras (one viewer, several cameras) ======================
/*
 * Each saved camera: { room, pass, name, broker, last }.
 * broker: the camera's own MQTT broker { url, backup } (from its QR code), or null = public brokers.
 * Most recently watched first.
 */
function loadCams() {
  const c = savedSettings().cams;
  return Array.isArray(c) ? c.filter(x => x && typeof x.room === 'string' && typeof x.pass === 'string') : [];
}
const findCam = room => loadCams().find(c => c.room === room) || null;
/** Add or update; touch = move it to the top (just watched). */
function putCam(cam, touch = true) {
  const cams = loadCams(), i = cams.findIndex(c => c.room === cam.room);
  const merged = { ...(i >= 0 ? cams[i] : {}), ...cam };
  if (i >= 0) cams.splice(i, 1);
  if (touch || i < 0) cams.unshift(merged); else cams.splice(i, 0, merged);
  savePatch({ cams });
}
function removeCam(room) { savePatch({ cams: loadCams().filter(c => c.room !== room) }); }

// ---------- Last picture of each camera (kept apart from the settings: images are bigger) ----------
function camShots() { try { return JSON.parse(localStorage.getItem('camerold-shots') || '{}'); } catch { return {}; } }
function setCamShot(room, img) {
  const all = camShots();
  if (img) all[room] = { img, ts: Date.now() }; else delete all[room];
  try { localStorage.setItem('camerold-shots', JSON.stringify(all)); }
  catch { // storage full: keep only this one
    try { localStorage.setItem('camerold-shots', JSON.stringify(img ? { [room]: all[room] } : {})); } catch {}
  }
}
/** A small copy of the picture being watched (turned like the view), for the cameras list. */
function saveCamShot() {
  const v = $('video');
  if (!S.room || !v.videoWidth || S.pc?.connectionState !== 'connected' || !findCam(S.room)) return;
  const swap = Z.rot % 180 !== 0, vw = swap ? v.videoHeight : v.videoWidth, vh = swap ? v.videoWidth : v.videoHeight;
  const k = 480 / Math.max(vw, vh), c = document.createElement('canvas');
  c.width = Math.round(vw * k); c.height = Math.round(vh * k);
  const x = c.getContext('2d');
  x.translate(c.width / 2, c.height / 2); x.rotate(Z.rot * Math.PI / 180);
  const dw = (swap ? c.height : c.width), dh = (swap ? c.width : c.height);
  x.drawImage(v, -dw / 2, -dh / 2, dw, dh);
  try { setCamShot(S.room, c.toDataURL('image/jpeg', 0.72)); } catch {}
}
/** In the app: it checks the saved cameras' battery in the background and notifies when it's low. */
function syncAppCams() {
  if (!window.CameroldApp?.syncCams) return;
  const cams = loadCams().map(c => ({ room: c.room, pass: c.pass, name: c.name || '', brokers: brokersFor(c.broker) }));
  try { CameroldApp.syncCams(JSON.stringify(cams)); } catch {}
}
/** Older versions kept a single camera (room/pass): turn it into the first list entry. */
function migrateCams() {
  const s = savedSettings();
  if (Array.isArray(s.cams)) return;
  const cams = [];
  if (s.room && s.pass) {
    const room = normalizeRoom(s.room);
    cams.push({ room, pass: s.pass, name: (s.camNames || {})[room] || null, broker: ownBroker(), last: Date.now() });
  }
  savePatch({ cams, room: undefined, pass: undefined, camNames: undefined });
}
const brokersFor = b => !b ? BROKERS : b.backup ? [b.url, ...BROKERS] : [b.url];

function relTime(ts) {
  if (!ts) return '';
  const s = (ts - Date.now()) / 1000, rtf = new Intl.RelativeTimeFormat(LANG, { numeric: 'auto' });
  if (Math.abs(s) < 60) return null;
  for (const [u, n] of [['day', 86400], ['hour', 3600], ['minute', 60]])
    if (Math.abs(s) >= n) { const r = rtf.format(Math.round(s / n), u); return r.charAt(0).toLocaleLowerCase(LANG) + r.slice(1); }
}

// ---------- List on the connect screen ----------
const CAMUI = { manual: false, undo: null, menuCam: null };
function renderCams() {
  const cams = loadCams(), has = cams.length > 0;
  $('camListBox').hidden = !has;
  $('loginTitle').textContent = t(has ? 'my_cameras' : 'watch_title');
  $('loginSub').hidden = has;
  // With saved cameras, typing a code is the exception: keep the form folded away
  const showForm = !has || CAMUI.manual;
  $('manualBox').hidden = !showForm; $('goBar').hidden = !showForm; $('manualToggle').hidden = showForm;
  const box = $('camList'); box.replaceChildren();
  box.classList.toggle('many', cams.length >= 3);
  const shots = camShots();
  for (const cam of cams) {
    // A card per camera with its last picture; the ⋮ button sits in its corner (a separate button, not nested)
    const card = document.createElement('div');
    card.className = 'cam-card'; card.setAttribute('role', 'button'); card.tabIndex = 0;
    const shot = shots[cam.room]?.img;
    card.innerHTML = (shot ? '<img alt="">' : '<span class="ph"><svg class="i"><use href="#i-camera"/></svg></span>') +
      '<div class="info"><div class="t"></div><div class="s"></div></div>';
    if (shot) card.querySelector('img').src = shot;
    const name = cam.name || t('cam_default');
    card.querySelector('.t').textContent = name;
    const when = cam.last ? relTime(cam.last) : '';
    card.querySelector('.s').textContent = when === null ? t('cam_just') : when ? t('cam_last', { when }) : cam.room;
    card.setAttribute('aria-label', name);
    card.onclick = () => start(cam);
    card.onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); start(cam); } };
    const more = document.createElement('button'); more.type = 'button'; more.className = 'icon-btn cam-more';
    more.setAttribute('aria-label', t('a_more')); more.title = t('a_more');
    more.innerHTML = '<svg class="i"><use href="#i-more"/></svg>';
    more.onclick = e => { e.stopPropagation(); openCamMenu(cam); };
    card.appendChild(more);
    box.appendChild(card);
  }
}
function openCamMenu(cam) {
  CAMUI.menuCam = cam;
  $('camMenuTitle').textContent = cam.name || t('cam_default');
  $('camMenuSub').textContent = cam.room;
  $('camMenu').hidden = false;
}
function closeCamMenu() { $('camMenu').hidden = true; CAMUI.menuCam = null; }
function deleteCam(cam) {
  removeCam(cam.room); renderCams();
  clearTimeout(CAMUI.undo?.timer);
  CAMUI.undo = { cam, timer: setTimeout(() => { $('snack').hidden = true; CAMUI.undo = null; setCamShot(cam.room, null); }, 6000) };
  $('snackText').textContent = t('cam_removed', { name: cam.name || cam.room });
  $('snack').hidden = false;
}
function undoDelete() {
  if (!CAMUI.undo) return;
  clearTimeout(CAMUI.undo.timer); putCam(CAMUI.undo.cam, false); CAMUI.undo = null;
  $('snack').hidden = true; renderCams();
}
function initCams() {
  migrateCams();
  syncAppCams();
  $('manualToggle').onclick = () => { CAMUI.manual = true; renderCams(); $('room').focus(); };
  $('camMenuShare').onclick = () => { const c = CAMUI.menuCam; closeCamMenu(); if (c) openShareQr(c); };
  $('camMenuDelete').onclick = () => { const c = CAMUI.menuCam; closeCamMenu(); if (c) deleteCam(c); };
  $('camMenu').addEventListener('click', e => { if (e.target === $('camMenu') || e.target.id === 'camMenuCancel') closeCamMenu(); });
  $('snackUndo').onclick = undoDelete;
  renderCams();
}

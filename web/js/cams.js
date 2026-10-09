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
  for (const cam of cams) {
    const row = document.createElement('div'); row.className = 'cam-item';
    const open = document.createElement('button'); open.type = 'button'; open.className = 'cam-open';
    open.innerHTML = '<span class="cam-ic"><svg class="i"><use href="#i-camera"/></svg></span><span class="grow"><span class="t"></span><span class="s"></span></span>';
    open.querySelector('.t').textContent = cam.name || t('cam_default');
    const when = cam.last ? relTime(cam.last) : '';
    open.querySelector('.s').textContent = [cam.room, when === null ? t('cam_just') : when && t('cam_last', { when })].filter(Boolean).join(' · ');
    open.onclick = () => start(cam);
    const more = document.createElement('button'); more.type = 'button'; more.className = 'icon-btn cam-more';
    more.setAttribute('aria-label', t('a_more')); more.title = t('a_more');
    more.innerHTML = '<svg class="i"><use href="#i-more"/></svg>';
    more.onclick = () => openCamMenu(cam);
    row.append(open, more);
    box.appendChild(row);
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
  CAMUI.undo = { cam, timer: setTimeout(() => { $('snack').hidden = true; CAMUI.undo = null; }, 6000) };
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
  $('manualToggle').onclick = () => { CAMUI.manual = true; renderCams(); $('room').focus(); };
  $('camMenuShare').onclick = () => { const c = CAMUI.menuCam; closeCamMenu(); if (c) openShareQr(c); };
  $('camMenuDelete').onclick = () => { const c = CAMUI.menuCam; closeCamMenu(); if (c) deleteCam(c); };
  $('camMenu').addEventListener('click', e => { if (e.target === $('camMenu') || e.target.id === 'camMenuCancel') closeCamMenu(); });
  $('snackUndo').onclick = undoDelete;
  renderCams();
}

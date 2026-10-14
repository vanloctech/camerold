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
  $('loginSub').textContent = !has ? t('hero_sub') : cams.length === 1 && LANG === 'en' ? t('cam_count1') : t('cam_count', { n: cams.length });
  // With saved cameras, typing a code is the exception: keep the form folded away
  const showForm = !has || CAMUI.manual;
  $('manualBox').hidden = !showForm; $('goBar').hidden = !showForm; $('manualToggle').hidden = showForm;
  const box = $('camList');
  box.classList.toggle('many', cams.length >= 3);
  // Cards are kept per camera: removing, renaming or coming back from watching updates them, it doesn't
  // rebuild the list (so the pictures don't reload)
  const old = new Map([...box.children].map(c => [c.dataset.room, c]));
  const shots = camShots();
  cams.forEach((cam, i) => {
    let card = old.get(cam.room);
    old.delete(cam.room);
    if (!card) card = camCard(cam.room);
    card.cam = cam; // what a tap opens
    camCardPicture(card, shots[cam.room]?.img);
    const name = cam.name || t('cam_default');
    setText(card.querySelector('.t'), name);
    const when = cam.last ? relTime(cam.last) : '';
    const sub = when === null ? t('cam_just') : when ? t('cam_last', { when }) : cam.room;
    setText(card.querySelector('.s'), cam.view ? sub + ' · ' + t('view_only') : sub);
    card.setAttribute('aria-label', name);
    const more = card.lastChild; more.title = t('a_more'); more.setAttribute('aria-label', more.title); // language may have changed
    if (box.children[i] !== card) box.insertBefore(card, box.children[i] || null);
  });
  old.forEach(c => c.remove());
}
/** A card per camera with its last picture; the ⋮ button sits in its corner (a separate button, not nested). */
function camCard(room) {
  const card = document.createElement('div');
  card.className = 'cam-card'; card.setAttribute('role', 'button'); card.tabIndex = 0; card.dataset.room = room;
  card.innerHTML = '<span class="ph"><svg class="i"><use href="#i-camera"/></svg></span><div class="info"><div class="t"></div><div class="s"></div></div>' +
    '<span class="play" aria-hidden="true"><svg class="i"><use href="#i-play"/></svg></span>';
  // Each camera keeps its own colour until it has a picture
  let h = 0; for (const ch of room) h = (h * 31 + ch.charCodeAt(0)) % 360;
  card.style.setProperty('--hue', h);
  card.onclick = () => start(card.cam);
  card.onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); start(card.cam); } };
  const more = document.createElement('button'); more.type = 'button'; more.className = 'icon-btn cam-more';
  more.innerHTML = '<svg class="i"><use href="#i-more"/></svg>';
  more.onclick = e => { e.stopPropagation(); openCamMenu(card.cam); };
  card.appendChild(more);
  return card;
}
/** The last picture, or the camera icon until there is one; the image is only replaced when the picture changed. */
function camCardPicture(card, shot) {
  const cur = card.firstChild;
  if (shot) {
    if (cur.tagName === 'IMG') { if (cur.getAttribute('src') !== shot) cur.src = shot; return; }
    const img = document.createElement('img'); img.alt = ''; img.src = shot; cur.replaceWith(img);
  } else if (cur.tagName === 'IMG') {
    const ph = document.createElement('span'); ph.className = 'ph'; ph.innerHTML = '<svg class="i"><use href="#i-camera"/></svg>';
    cur.replaceWith(ph);
  }
}
function openCamMenu(cam) {
  CAMUI.menuCam = cam;
  $('camMenuTitle').textContent = cam.name || t('cam_default');
  $('camMenuSub').textContent = cam.room;
  // Full-control cameras: their own code (full control) and, once the camera sent it, the view-only one
  const canShareView = !cam.view && typeof cam.viewPass === 'string' && cam.viewPass.length >= 8;
  $('camMenuShareView').hidden = !canShareView;
  $('camMenuShareText').textContent = t(canShareView ? 'share_full' : 'share_t');
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
  $('camMenuShare').onclick = () => { const c = CAMUI.menuCam; closeCamMenu(); if (c) openShareQr(c, !!c.view); };
  $('camMenuShareView').onclick = () => { const c = CAMUI.menuCam; closeCamMenu(); if (c?.viewPass) openShareQr({ ...c, pass: c.viewPass }, true); };
  $('camMenuDelete').onclick = () => { const c = CAMUI.menuCam; closeCamMenu(); if (c) deleteCam(c); };
  $('camMenu').addEventListener('click', e => { if (e.target === $('camMenu') || e.target.id === 'camMenuCancel') closeCamMenu(); });
  $('snackUndo').onclick = undoDelete;
  renderCams();
}

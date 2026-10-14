'use strict';
// ====================== Timelapse on the camera phone ======================
/*
 * Start / stop and options go to the camera like other settings ({t: 'cfg', tl: {...}}); its state comes back in
 * "info" (tl). Finished videos are listed, downloaded and deleted over the "tl" data channel of the WebRTC
 * connection (end-to-end encrypted, straight from the camera phone). See docs/PROTOCOL.md.
 */
const TL = {
  info: null,      // last state from the camera
  ch: null,        // "tl" data channel
  items: [],       // finished videos
  thumbs: {},      // id -> data URL
  dl: null,        // download in progress: { id, size, got, name, parts | app }
  confirmDel: null,
};
const TL_DEFAULTS = { interval: 10, minutes: 0, q: 1080, stamp: true, gallery: false };
function tlPrefs() { return { ...TL_DEFAULTS, ...(savedSettings().tl || {}) }; }
function tlSavePrefs(p) { savePatch({ tl: { ...tlPrefs(), ...p } }); }

const fmtSize = b => b >= 1e9 ? fmtNum(b / 1e9, 1) + ' GB' : b >= 1e6 ? fmtNum(b / 1e6, b >= 1e8 ? 0 : 1) + ' MB' : fmtNum(Math.max(1, b / 1e3)) + ' KB';
/** Short video length: "12 s", "1 min 5 s". */
function fmtSecs(s) {
  s = Math.round(s);
  return s < 60 ? t('secs', { s }) : t('min_secs', { m: Math.floor(s / 60), s: s % 60 });
}
function fmtWhen(ms) {
  return new Intl.DateTimeFormat(LANG, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(ms));
}
const tlVideoSecs = photos => photos / 30; // the camera makes 30 fps videos

// ---------- Estimates ----------
/*
 * What a timelapse will take, from the settings. The camera keeps one JPEG per photo while it runs (deleted at the
 * end) and makes an H.264 video at 30 fps; sizes follow Timelapse.kt (photo size from the quality, the encoder's
 * bitrate), with an average JPEG weight for a camera picture. Estimates, shown as such.
 */
const JPEG_BYTES_PER_PX = 0.16;
const TL_MIN_FREE = 300e6; // the camera stops by itself below this (Timelapse.MIN_FREE)
function tlPhotoSize(q) {
  // The camera's capture size (16:9 when unknown), scaled so the short side is the quality, never up
  const w = S.capW || 1920, h = S.capH || 1080, k = Math.min(1, q / Math.min(w, h));
  return [Math.round(w * k), Math.round(h * k)];
}
function tlEstimate(o) {
  const [w, h] = tlPhotoSize(o.q), px = w * h;
  const perPhoto = px * JPEG_BYTES_PER_PX;
  const perFrame = Math.min(Math.max(px * 30 / 6, 2e6), 2e7) / 8 / 30; // bytes of video per photo
  const hours = o.minutes ? o.minutes / 60 : 1;
  const photos = Math.round(hours * 3600 / o.interval);
  const free = TL.info?.free > 0 ? TL.info.free : null;
  return {
    hours, photos, perHour: !o.minutes,
    secs: tlVideoSecs(photos), video: photos * perFrame,
    need: photos * (perPhoto + perFrame), // photos and video side by side just before the photos are deleted
    fitsHours: free ? Math.max(0, free - TL_MIN_FREE) / (3600 / o.interval * (perPhoto + perFrame)) : null,
  };
}
const fmtHours = h => h >= 48 ? t('n_days', { n: fmtNum(h / 24) }) : h >= 1 ? t('n_hours', { n: fmtNum(h, h < 10 ? 1 : 0) }) : t('n_mins', { n: fmtNum(Math.max(1, h * 60)) });
/** Short running time for the stat tiles: "1h 12m", "25m". */
function fmtRun(sec) {
  const h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60);
  return h ? t('run_hm', { h, m }) : t('run_m', { m });
}
const fmtInterval = s => s >= 60 ? t('n_mins', { n: s / 60 }) : t('n_secs', { n: s });

// ---------- State from the camera ----------
function onTimelapseInfo(tl) {
  const first = !TL.info;
  TL.info = tl;
  $('tlBox').hidden = false; $('tlNone').hidden = true;
  if (first && TL.ch?.readyState === 'open') tlRequestList();
  renderTl();
}

/** The settings in use: the camera's while it runs, else this viewer's choices. */
function tlCurrent() {
  const tl = TL.info, p = tlPrefs();
  if (!tl?.on) return p;
  return { interval: tl.interval, q: tl.q, stamp: tl.stamp, gallery: tl.gallery,
    minutes: tl.stopAt ? Math.round((tl.stopAt - tl.startedAt) / 60000) : 0 };
}

function renderTl() {
  const tl = TL.info;
  if (!tl) return;
  const on = !!tl.on, c = tlCurrent();
  $('tlIc').classList.toggle('on', on);
  $('tlTabDot').hidden = !on;
  $('tlBadge').hidden = !on;
  $('tlBadgeN').textContent = on ? fmtNum(tl.frames || 0) : '';
  $('tlStats').hidden = !on;
  if (on) {
    $('tlState').textContent = t('tl_on');
    $('tlSub').textContent = tl.stopAt
      ? t('tl_stops_at', { time: new Intl.DateTimeFormat(LANG, { timeStyle: 'short' }).format(new Date(tl.stopAt)) })
      : t('tl_no_stop');
    $('tlStPhotos').textContent = fmtNum(tl.frames || 0);
    $('tlStVideo').textContent = fmtSecs(tlVideoSecs(tl.frames || 0));
    $('tlStTime').textContent = fmtRun(Math.max(0, (Date.now() - tl.startedAt) / 1000));
  } else if (tl.busy) {
    $('tlState').textContent = t('tl_making');
    $('tlSub').textContent = t('tl_making_sub');
  } else {
    $('tlState').textContent = t('tl_off');
    const ev = tl.event === 'storage' ? t('tl_ev_storage') : tl.event === 'encode' ? t('tl_ev_encode') : '';
    $('tlSub').textContent = ev || t('tl_off_sub');
  }
  $('tlSub').classList.toggle('warn', !on && !!tl.event);
  $('tlGo').classList.toggle('stop', on);
  $('tlGoIcon').setAttribute('href', on ? '#i-stop' : '#i-play');
  $('tlGoText').textContent = t(on ? 'tl_stop' : 'tl_start');
  // Settings summary: "Every 10 s · Full HD · stops after 3 h · date and time"
  $('tlSetSum').textContent = [t('tl_sum_every', { i: fmtInterval(c.interval) }), c.q >= 1080 ? 'Full HD' : 'HD',
    c.minutes ? t('tl_sum_stop', { d: fmtDuration(c.minutes * 60) }) : t('tl_sum_nostop'), c.stamp ? t('tl_sum_stamp') : null]
    .filter(Boolean).join(' · ');
  $('tlSetRow').classList.toggle('locked', on);
  renderTlStorage();
  if (!$('tlDlg').hidden) renderTlEstimate();
}

/** Camera storage: used, what this timelapse needs, free. */
function renderTlStorage() {
  const tl = TL.info, box = $('tlStore');
  box.hidden = !(tl?.free > 0 && tl.total > 0);
  if (box.hidden) return;
  const e = tlEstimate(tlCurrent()), used = tl.total - tl.free;
  // While it runs, part of what it needs is already on the phone (the photos so far)
  const need = Math.min(tl.free, tl.on ? Math.max(0, e.need - (tl.frames || 0) * tlPhotoSize(tlCurrent().q).reduce((a, b) => a * b) * JPEG_BYTES_PER_PX) : e.need);
  $('tlStoreFree').textContent = t('tl_free_of', { f: fmtSize(tl.free), t: fmtSize(tl.total) });
  $('tlBarUsed').style.width = (used / tl.total * 100).toFixed(2) + '%';
  $('tlBarNeed').style.width = Math.max(need / tl.total * 100, need > 0 ? 0.8 : 0).toFixed(2) + '%';
  $('tlNeedTxt').textContent = t(e.perHour ? 'tl_need_hour' : 'tl_need', { s: fmtSize(need) });
  const low = e.fitsHours != null && (e.perHour ? e.fitsHours < 3 : e.fitsHours < e.hours);
  $('tlStoreHint').textContent = e.fitsHours == null ? '' : t(low ? 'tl_fits_low' : 'tl_fits', { d: fmtHours(e.fitsHours) });
  $('tlStoreHint').classList.toggle('warn', low);
}

/** Top of the settings sheet: photos, video length and size, storage needed. */
function renderTlEstimate() {
  const e = tlEstimate(tlPrefs()), box = $('tlEst');
  // Built once; afterwards only the texts change (it follows the slider while it's dragged)
  if (!box.firstChild) {
    const head = document.createElement('div'); head.className = 'tl-est-head';
    const grid = document.createElement('div'); grid.className = 'tl-stats';
    for (let i = 0; i < 3; i++) { const d = document.createElement('div'); d.append(document.createElement('b'), document.createElement('span')); grid.appendChild(d); }
    const note = document.createElement('div'); note.className = 's';
    box.append(head, grid, note);
  }
  const [head, grid, note] = box.children;
  setText(head, e.perHour ? t('tl_est_hour') : t('tl_est_for', { d: fmtDuration(e.hours * 3600) }));
  [[fmtNum(e.photos), 'tl_st_photos'], [fmtSecs(e.secs), 'tl_st_video'], ['~' + fmtSize(e.video), 'tl_st_size']].forEach(([v, k], i) => {
    const [b, s] = grid.children[i].children;
    setText(b, v); setText(s, t(k));
  });
  const short = e.fitsHours != null && !e.perHour && e.fitsHours < e.hours;
  const parts = [t('tl_est_need', { s: fmtSize(e.need) })];
  if (e.fitsHours != null) parts.push(t('tl_fits', { d: fmtHours(e.fitsHours) }));
  setText(note, short ? t('tl_not_enough', { s: fmtSize(e.need) }) : parts.join(' · '));
  note.classList.toggle('warn', short);
}

/** Auto-stop time: the quick picks light up when the slider is on one of them. */
function showTlDur(min) {
  setSeg('selTlDur', min);
  const r = $('tlDur'); r.value = min; fillRange(r);
  $('tlDurVal').textContent = min ? fmtDuration(min * 60) : t('tl_never');
}
function openTlSettings() {
  if (TL.info?.on) { toast(t('tl_locked'), 2400); return; }
  const p = tlPrefs();
  setSeg('selTlInt', p.interval); setSeg('selTlQ', p.q); showTlDur(p.minutes);
  setTog('tTlStamp', p.stamp); setTog('tTlGallery', p.gallery);
  renderTlEstimate();
  $('tlDlg').hidden = false;
}
function closeTlSettings() { $('tlDlg').hidden = true; renderTl(); }

// ---------- Start / stop ----------
function tlStartStop() {
  if (TL.info?.on) { sendCam({ t: 'cfg', tl: { op: 'stop' } }); return; }
  const p = tlPrefs();
  // The video is turned the way it's shown here (the "rotate" button)
  sendCam({ t: 'cfg', tl: { op: 'start', interval: p.interval, q: p.q, minutes: p.minutes, stamp: p.stamp, gallery: p.gallery, rot: Z.rot } });
  toast(t('tl_started'), 2600);
}

// ---------- Videos over the data channel ----------
function tlAttach(ch) {
  TL.ch = ch;
  ch.binaryType = 'arraybuffer';
  ch.onopen = () => { if (TL.info) tlRequestList(); };
  ch.onmessage = e => typeof e.data === 'string' ? tlOnMessage(e.data) : tlOnChunk(e.data);
  ch.onclose = () => { if (TL.ch === ch) { TL.ch = null; if (TL.dl) tlDownloadDone(false); } };
  if (ch.readyState === 'open' && TL.info) tlRequestList(); // channels from the camera often arrive already open
}
function tlSend(m) { if (TL.ch?.readyState === 'open') { TL.ch.send(JSON.stringify(m)); return true; } return false; }
function tlRequestList() { tlSend({ op: 'list' }); }

function tlOnMessage(text) {
  let m; try { m = JSON.parse(text); } catch { return; }
  if (m.op === 'list') { TL.items = Array.isArray(m.items) ? m.items : []; renderTlList(); }
  else if (m.op === 'thumb') { TL.thumbs[m.id] = 'data:image/jpeg;base64,' + m.data; tlShowThumb(m.id); }
  else if (m.op === 'file' && TL.dl?.id === m.id) tlDownloadBegin(m);
  else if (m.op === 'end' && TL.dl?.id === m.id) tlDownloadDone(!m.cancelled, m.cancelled);
  else if (m.op === 'err' && TL.dl?.id === m.id) tlDownloadDone(false);
}

/**
 * The list comes again each time the tab opens or a video is added or deleted: rows are kept per video (with their
 * thumbnails), so only new videos are added and deleted ones removed.
 */
function renderTlList() {
  const box = $('tlList');
  if (!TL.items.length) {
    let p = box.firstChild;
    if (!p || box.children.length > 1 || !p.classList.contains('tl-empty')) {
      p = document.createElement('div'); p.className = 'tl-empty'; box.replaceChildren(p);
    }
    setText(p, TL.ch ? t('tl_none') : t('tl_list_wait'));
    return;
  }
  const old = new Map([...box.querySelectorAll('.tl-item')].map(r => [r.dataset.id, r]));
  box.querySelector('.tl-empty')?.remove();
  TL.items.forEach((v, i) => {
    let row = old.get(v.id);
    old.delete(v.id);
    if (!row) row = tlRow(v.id);
    row.video = v; // what its buttons act on
    row.querySelector('.dl').setAttribute('aria-label', t('tl_download')); row.querySelector('.del').setAttribute('aria-label', t('tl_delete'));
    setText(row.querySelector('.t'), fmtWhen(v.created));
    if (TL.dl?.id !== v.id) setText(row.querySelector('.s'), [fmtSecs(v.dur), fmtSize(v.size), t('tl_photos', { n: fmtNum(v.frames) })].join(' · '));
    if (box.children[i] !== row) box.insertBefore(row, box.children[i] || null);
  });
  old.forEach(r => r.remove());
  tlShowProgress();
}
function tlRow(id) {
  const row = document.createElement('div'); row.className = 'tl-item'; row.dataset.id = id;
  row.innerHTML = '<span class="tl-thumb"><svg class="i"><use href="#i-film"/></svg></span>' +
    '<div class="grow"><div class="t"></div><div class="s"></div><div class="bar" hidden><i></i></div></div>' +
    '<button type="button" class="icon-btn dl"><svg class="i"><use href="#i-download"/></svg></button>' +
    '<button type="button" class="icon-btn del"><svg class="i"><use href="#i-trash"/></svg></button>';
  const dl = row.querySelector('.dl'), del = row.querySelector('.del');
  dl.onclick = () => TL.dl?.id === row.video.id ? tlCancel() : tlDownload(row.video);
  del.onclick = () => tlDelete(row.video, del);
  // Thumbnails arrive after the list; one already here is shown at once
  const thumb = TL.thumbs[id];
  if (thumb) { const img = document.createElement('img'); img.className = 'tl-thumb'; img.alt = ''; img.src = thumb; row.firstChild.replaceWith(img); }
  return row;
}
/** Thumbnails come after the list: the film icon is replaced when one arrives. */
function tlShowThumb(id) {
  const ph = document.querySelector(`.tl-item[data-id="${CSS.escape(id)}"] span.tl-thumb`); // id comes from the camera
  if (!ph || !TL.thumbs[id]) return; // no row, no picture yet, or it already shows it
  const img = document.createElement('img');
  img.className = 'tl-thumb'; img.alt = ''; img.src = TL.thumbs[id];
  ph.replaceWith(img);
}

/** Deleting asks for a second tap on the same button. */
function tlDelete(v, btn) {
  if (TL.confirmDel !== v.id) {
    TL.confirmDel = v.id;
    for (const b of document.querySelectorAll('.tl-item .del.confirm')) b.classList.remove('confirm');
    btn.classList.add('confirm');
    toast(t('tl_delete_again'), 2400);
    setTimeout(() => { if (TL.confirmDel === v.id) { TL.confirmDel = null; btn.classList.remove('confirm'); } }, 3000);
    return;
  }
  TL.confirmDel = null;
  if (tlSend({ op: 'del', id: v.id })) { TL.items = TL.items.filter(x => x.id !== v.id); renderTlList(); }
}

// ---------- Download ----------
function tlDownload(v) {
  if (TL.dl) { toast(t('tl_one_at_a_time')); return; }
  if (!tlSend({ op: 'get', id: v.id })) { toast(t('tl_not_connected')); return; }
  TL.dl = { id: v.id, size: v.size, got: 0, name: v.file || `Camerold timelapse ${v.id}.mp4`, parts: [], pending: [], pendingBytes: 0 };
  tlShowProgress();
}
function tlCancel() { tlSend({ op: 'cancel' }); }

function tlDownloadBegin(m) {
  const d = TL.dl;
  d.size = m.size; d.name = m.name || d.name;
  if (IN_APP && window.CameroldApp?.fileOpen) {
    const r = CameroldApp.fileOpen(d.name);
    if (r !== 'ok') { tlCancel(); TL.dl = null; renderTlList(); toast(t(r === 'perm' ? 'snap_perm' : 'tl_dl_fail'), 3000); return; }
    d.app = true;
  }
}

/** Base64 of bytes, in slices (String.fromCharCode can't take megabytes at once). */
function toBase64(u8) {
  let s = '';
  for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(s);
}
function tlFlush(d) {
  if (!d?.app || !d.pendingBytes) return true;
  const all = new Uint8Array(d.pendingBytes);
  let o = 0; for (const p of d.pending) { all.set(new Uint8Array(p), o); o += p.byteLength; }
  d.pending = []; d.pendingBytes = 0;
  return CameroldApp.fileWrite(toBase64(all));
}

function tlOnChunk(buf) {
  const d = TL.dl;
  if (!d) return;
  d.got += buf.byteLength;
  if (d.app) {
    d.pending.push(buf); d.pendingBytes += buf.byteLength;
    // The app writes it to the gallery in pieces of about 1 MB
    if (d.pendingBytes >= 1 << 20 && !tlFlush(d)) { tlCancel(); tlDownloadDone(false); return; }
  } else d.parts.push(buf);
  if (!d.raf) d.raf = requestAnimationFrame(() => { d.raf = 0; tlShowProgress(); });
}

function tlShowProgress() {
  const d = TL.dl;
  for (const row of document.querySelectorAll('.tl-item')) {
    const mine = d && row.dataset.id === d.id;
    row.querySelector('.bar').hidden = !mine;
    row.querySelector('.dl use').setAttribute('href', mine ? '#i-x' : '#i-download');
    if (mine) {
      const pct = d.size ? Math.min(100, d.got / d.size * 100) : 0;
      row.querySelector('.bar i').style.width = pct + '%';
      setText(row.querySelector('.s'), t('tl_downloading', { p: fmtNum(pct) }));
    }
  }
}

function tlDownloadDone(ok, cancelled = false) {
  const d = TL.dl;
  if (!d) return;
  TL.dl = null;
  ok = ok && d.got >= d.size;
  if (d.app) {
    if (ok && !tlFlush(d)) ok = false;
    CameroldApp.fileClose(ok);
    if (ok) toast(t('tl_saved_app'), 3000);
  } else if (ok) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob(d.parts, { type: 'video/mp4' })); a.download = d.name;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 30000);
    toast(t('tl_saved'), 2600);
  }
  if (!ok && !cancelled) toast(t('tl_dl_fail'), 3000);
  renderTlList();
}

/** Reconnecting: the old data channel is gone (a download in it too); the state stays until new info arrives. */
function tlDropChannel() {
  if (TL.dl) tlDownloadDone(false, true);
  TL.ch = null;
}
/** Left the camera: hide everything about its timelapse. */
function tlReset() {
  tlDropChannel();
  TL.info = null; TL.items = []; TL.thumbs = {};
  $('tlBox').hidden = true; $('tlNone').hidden = false; $('tlBadge').hidden = true; $('tlTabDot').hidden = true;
  $('tlDlg').hidden = true;
}

function openTimelapse() { openPanel('tl'); tlRequestList(); }

// ---------- Wiring ----------
onSeg('selTlInt', v => { tlSavePrefs({ interval: +v }); renderTlEstimate(); });
onSeg('selTlDur', v => { tlSavePrefs({ minutes: +v }); showTlDur(+v); renderTlEstimate(); });
$('tlDur').addEventListener('input', () => { const v = +$('tlDur').value; tlSavePrefs({ minutes: v }); showTlDur(v); renderTlEstimate(); });
onSeg('selTlQ', v => { tlSavePrefs({ q: +v }); renderTlEstimate(); });
$('tTlStamp').onchange = () => tlSavePrefs({ stamp: $('tTlStamp').checked });
$('tTlGallery').onchange = () => tlSavePrefs({ gallery: $('tTlGallery').checked });
$('tlGo').onclick = tlStartStop;
$('tlSetRow').onclick = openTlSettings;
$('tlDlgDone').onclick = closeTlSettings;
$('tlDlg').addEventListener('click', e => { if (e.target === $('tlDlg')) closeTlSettings(); });
$('tlBadge').onclick = e => { e.stopPropagation(); openTimelapse(); };
// The running time changes even when no new photo arrives
setInterval(() => { if (TL.info?.on) renderTl(); }, 30000);

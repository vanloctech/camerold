'use strict';
// ====================== Continuous recording on the camera phone ======================
/*
 * On / off and settings go to the camera like other settings ({t: 'cfg', rec: {...}}); its state comes back in
 * "info" (rec). Recordings (one per half hour) are browsed by day, cut, downloaded, kept and deleted over the "rec"
 * data channel of the WebRTC connection (end-to-end encrypted, straight from the camera phone). See docs/PROTOCOL.md.
 */
const REC = {
  info: null,      // last state from the camera
  ch: null,        // "rec" data channel
  days: [],        // [{day, n, size, dur}], newest first
  day: null,       // the day shown ("yyyy-MM-dd")
  items: [],       // recordings of that day
  thumbs: {},      // id -> data URL
  open: null,      // id of the row showing its actions
  dl: null,        // download in progress: { id, size, got, name, codec, prep, parts | app }
  denied: false,   // the camera refused (view-only code not allowed)
  stale: true,     // the list changed while the tab wasn't showing
  confirmDel: null,
  cut: null,       // { v, from, len } in the cut sheet
};

/** HEVC recordings play here? (Firefox doesn't; Chrome, Edge and Safari do on most devices.) */
const HEVC_PLAYS = (() => {
  try { const v = document.createElement('video'); return !!(v.canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"') || v.canPlayType('video/mp4; codecs="hev1.1.6.L93.B0"')); }
  catch { return false; }
})();

// ---------- Estimates (Recorder.bitrate on the camera) ----------
function recCodec(o = REC.info) { return o && !o.h264 && o.hevc ? 'hevc' : 'h264'; }
/** The most an hour takes with these settings: the encoder's variable bitrate never goes above it. */
function recMaxPerHour(o) {
  const q = o.q || 720, wide = S.capW && S.capH ? Math.max(S.capW, S.capH) / Math.min(S.capW, S.capH) : 16 / 9;
  const br = Math.min(8e6, Math.max(3e5, Math.round(q * wide) * q * (o.fps || 15) * (recCodec(o) === 'hevc' ? 0.045 : 0.075)));
  return br * 3600 / 8;
}
/** An hour as measured on recent recordings (still scenes take much less), else the most. */
function recPerHour(o = REC.info) { return o?.rate > 0 ? o.rate : recMaxPerHour(o || {}); }
/** Hours the camera can hold: what's recorded plus what's free, minus what it always leaves free. */
function recFitsHours(o = REC.info, perHour = recPerHour(o)) {
  if (!(o?.total > 0)) return null;
  return Math.max(0, (o.used || 0) + o.free - (o.minFree || 0)) / perHour;
}
const fmtClock = ms => new Intl.DateTimeFormat(LANG, { timeStyle: 'short' }).format(new Date(ms));
const fmtClockS = ms => new Intl.DateTimeFormat(LANG, { timeStyle: 'medium' }).format(new Date(ms));
function recKeepText(d) { return d ? t('rec_keep_sum', { n: d }) : t('rec_keep_sum_full'); }
function recSum(o) {
  return t('rec_sum', { q: o.q >= 1080 ? 'Full HD' : 'HD', fps: o.fps, codec: recCodec(o) === 'hevc' ? 'HEVC' : 'H.264', keep: recKeepText(o.days) });
}

// ---------- State from the camera ----------
function onRecInfo(r) {
  const first = !REC.info, ver = REC.info?.ver;
  REC.info = { ...REC.info, ...r };
  $('recBox').hidden = false; $('recNone').hidden = true;
  if (first || r.ver !== ver) { REC.stale = true; if (S.tab === 'rec') recRefresh(); }
  renderRec();
}

const recAllowed = () => !S.viewOnly || !!REC.info?.viewOnly;

function renderRec() {
  const r = REC.info;
  if (!r) return;
  const on = !!r.on, rec = on && !!r.active;
  $('recIc').classList.toggle('on', rec);
  $('recTabDot').hidden = !rec;
  $('recPill').hidden = !rec;
  let state, sub, warn = false;
  if (on && r.event === 'storage') { state = t('rec_off'); sub = t('rec_ev_storage'); warn = true; }
  else if (on && r.event === 'encode') { state = t('rec_starting'); sub = t('rec_ev_encode'); warn = true; }
  else if (rec) { state = t('rec_on'); sub = t('rec_on_sub', { time: fmtClock(r.since), sum: recSum(r) }); }
  else if (on) { state = t('rec_starting'); sub = recSum(r); }
  else { state = t('rec_off'); sub = t('rec_off_sub'); }
  setText($('recState'), state); setText($('recSub'), sub);
  $('recSub').classList.toggle('warn', warn);
  const fits = recFitsHours(r);
  setText($('recStUsed'), fmtSize(r.used || 0));
  setText($('recStRate'), (r.rate > 0 ? '' : '≤ ') + fmtSize(recPerHour(r)));
  setText($('recStFits'), fits == null ? '–' : fmtHours(fits));
  $('recGo').classList.toggle('stop', on);
  $('recGoIcon').setAttribute('href', on ? '#i-stop' : '#i-play');
  setText($('recGoText'), t(on ? 'rec_stop' : 'rec_start'));
  setText($('recSetSum'), recSum(r));
  const allowed = recAllowed() && !REC.denied;
  $('recDenied').hidden = allowed; $('recBrowse').hidden = !allowed;
  if (!$('recDlg').hidden) renderRecSettings();
}

// ---------- Settings (applied on the camera at once) ----------
function sendRec(patch) {
  REC.info = { ...REC.info, ...patch }; // shown right away; the camera's info follows
  sendCam({ t: 'cfg', rec: patch });
  renderRec();
}
function openRecSettings() { renderRecSettings(); $('recDlg').hidden = false; }
function closeRecSettings() { $('recDlg').hidden = true; }

function renderRecSettings() {
  const r = REC.info || {};
  setSeg('selRecQ', r.q || 720); setSeg('selRecFps', r.fps || 15); setSeg('selRecDays', r.days ?? 7);
  setSeg('selRecFmt', recCodec(r));
  for (const b of $('selRecDays').querySelectorAll('button')) if (+b.dataset.v) setText(b, t('n_days', { n: b.dataset.v }));
  $('selRecFmt').querySelector('[data-v="hevc"]').disabled = !r.hevc;
  setText($('recFmtHint'), !r.hevc ? t('rec_fmt_none') : recCodec(r) === 'hevc'
    ? t('rec_fmt_hevc') + (HEVC_PLAYS ? '' : ' ' + t('rec_fmt_no_play')) : t('rec_fmt_h264'));
  $('recFmtHint').classList.toggle('warn', r.hevc && recCodec(r) === 'hevc' && !HEVC_PLAYS);
  setTog('tRecView', r.viewOnly);
  // Estimate: the most an hour and a day, how long the camera's storage lasts
  const box = $('recEst');
  if (!box.firstChild) {
    const head = document.createElement('div'); head.className = 'tl-est-head';
    const grid = document.createElement('div'); grid.className = 'tl-stats';
    for (let i = 0; i < 3; i++) { const d = document.createElement('div'); d.append(document.createElement('b'), document.createElement('span')); grid.appendChild(d); }
    const note = document.createElement('div'); note.className = 's';
    box.append(head, grid, note);
  }
  const [head, grid, note] = box.children, max = recMaxPerHour(r), fits = recFitsHours(r, max);
  setText(head, t('rec_est_head'));
  [['≤ ' + fmtSize(max), 'rec_st_rate'], ['≤ ' + fmtSize(max * 24), 'rec_per_day'], [fits == null ? '–' : '≥ ' + fmtHours(fits), 'rec_st_fits']].forEach(([v, k], i) => {
    const [b, s] = grid.children[i].children;
    setText(b, v); setText(s, t(k));
  });
  setText(note, [t('rec_est_note'), r.rate > 0 ? t('rec_est_measured', { s: fmtSize(r.rate) }) : null].filter(Boolean).join(' '));
}

function recStartStop() {
  if (REC.info?.on) { sendRec({ on: false }); return; }
  // The picture is turned the way it's shown here (the "rotate" button)
  sendRec({ on: true, rot: Z.rot });
  toast(S.health?.chg === false ? t('rec_charge') : t('rec_started'), S.health?.chg === false ? 4000 : 2400);
}

// ---------- Recordings over the data channel ----------
function recAttach(ch) {
  REC.ch = ch;
  ch.binaryType = 'arraybuffer';
  ch.onopen = () => { REC.stale = true; if (S.tab === 'rec') recRefresh(); };
  ch.onmessage = e => typeof e.data === 'string' ? recOnMessage(e.data) : recOnChunk(e.data);
  ch.onclose = () => { if (REC.ch === ch) { REC.ch = null; if (REC.dl) recDownloadDone(false); } };
  if (ch.readyState === 'open') { REC.stale = true; if (S.tab === 'rec') recRefresh(); } // often already open
}
function recSend(m) { if (REC.ch?.readyState === 'open') { REC.ch.send(JSON.stringify(m)); return true; } return false; }
/** The tab is showing: fetch the days (then the day's list) if something changed since. */
function recRefresh(force = false) {
  if (!REC.info || !recAllowed() || (!REC.stale && !force)) { renderRecDay(); return; }
  if (recSend({ op: 'days' })) REC.stale = false;
  renderRecDay();
}
function recRequestDay() {
  // Thumbnails already here aren't sent again
  if (REC.day) recSend({ op: 'list', day: REC.day, have: Object.keys(REC.thumbs) });
}

function recOnMessage(text) {
  let m; try { m = JSON.parse(text); } catch { return; }
  if (m.op === 'days') {
    REC.denied = false;
    REC.days = Array.isArray(m.items) ? m.items : [];
    if (!REC.days.some(d => d.day === REC.day)) { REC.day = REC.days[0]?.day || null; REC.items = []; }
    renderRecDays(); recRequestDay(); renderRecDay(); renderRec();
  } else if (m.op === 'list' && m.day === REC.day) { REC.items = Array.isArray(m.items) ? m.items : []; renderRecDay(); }
  else if (m.op === 'thumb') { REC.thumbs[m.id] = 'data:image/jpeg;base64,' + m.data; recShowThumb(m.id); }
  else if (m.op === 'changed') { REC.stale = true; if (S.tab === 'rec') recRefresh(); }
  else if (m.op === 'denied') { REC.denied = true; renderRec(); }
  else if (m.op === 'prep' && REC.dl?.id === m.id) { REC.dl.prep = true; recShowProgress(); }
  else if (m.op === 'file' && REC.dl?.id === m.id) recDownloadBegin(m);
  else if (m.op === 'end' && REC.dl?.id === m.id) recDownloadDone(!m.cancelled, m.cancelled);
  else if (m.op === 'err' && REC.dl?.id === m.id) recDownloadDone(false);
}

// ---------- Days, timeline, list ----------
function recDayLabel(day) {
  const [y, mo, d] = day.split('-').map(Number), when = new Date(y, mo - 1, d), today = new Date();
  today.setHours(0, 0, 0, 0);
  const diff = Math.round((today - when) / 864e5);
  if (diff === 0) return t('rec_today');
  if (diff === 1) return t('rec_yesterday');
  return new Intl.DateTimeFormat(LANG, { weekday: 'short', day: 'numeric', month: 'numeric' }).format(when);
}
const recDayStart = day => { const [y, mo, d] = day.split('-').map(Number); return new Date(y, mo - 1, d).getTime(); };

function renderRecDays() {
  const box = $('recDays');
  const old = new Map([...box.children].map(b => [b.dataset.day, b]));
  REC.days.forEach((d, i) => {
    let b = old.get(d.day);
    old.delete(d.day);
    if (!b) {
      b = document.createElement('button'); b.type = 'button'; b.className = 'rec-chip'; b.dataset.day = d.day; b.setAttribute('role', 'tab');
      b.append(document.createElement('b'), document.createElement('span'));
      b.onclick = () => { if (REC.day === d.day) return; REC.day = d.day; REC.items = []; REC.open = null; renderRecDays(); renderRecDay(); recRequestDay(); };
    }
    setText(b.children[0], recDayLabel(d.day));
    setText(b.children[1], d.dur ? fmtHours(d.dur / 3600) : t('rec_live'));
    b.classList.toggle('on', d.day === REC.day); b.setAttribute('aria-selected', d.day === REC.day);
    if (box.children[i] !== b) box.insertBefore(b, box.children[i] || null);
  });
  old.forEach(b => b.remove());
  box.querySelector('.on')?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
}

/** The day: blocks on a 24-hour line, then a row per recording (kept per id, so only changes touch the page). */
function renderRecDay() {
  const list = $('recList'), track = $('recTrack');
  $('recLine').hidden = !REC.items.length;
  if (!REC.items.length) {
    let p = list.firstChild;
    if (!p || list.children.length > 1 || !p.classList.contains('tl-empty')) { p = document.createElement('div'); p.className = 'tl-empty'; list.replaceChildren(p); }
    setText(p, !REC.ch || (REC.day && REC.days.length) ? t('rec_list_wait') : t('rec_day_none'));
    track.replaceChildren();
    return;
  }
  const d0 = recDayStart(REC.day), oldB = new Map([...track.children].map(b => [b.dataset.id, b]));
  for (const v of REC.items) {
    let b = oldB.get(v.id);
    oldB.delete(v.id);
    if (!b) { b = document.createElement('i'); b.dataset.id = v.id; b.onclick = () => recFocus(v.id); track.appendChild(b); }
    b.style.left = Math.max(0, (v.start - d0) / 864e5 * 100).toFixed(3) + '%';
    b.style.width = Math.max(0.5, (v.dur || 60) / 864).toFixed(3) + '%';
    b.className = v.live ? 'live' : v.keep ? 'kept' : '';
  }
  oldB.forEach(b => b.remove());

  const old = new Map([...list.querySelectorAll('.rec-item')].map(r => [r.dataset.id, r]));
  list.querySelector('.tl-empty')?.remove();
  // Newest first, like the days
  [...REC.items].reverse().forEach((v, i) => {
    let row = old.get(v.id);
    old.delete(v.id);
    if (!row) row = recRow(v.id);
    row.rec = v;
    const end = v.start + (v.dur || 0) * 1000;
    setText(row.querySelector('.t .when'), `${fmtClock(v.start)} – ${v.live ? '…' : fmtClock(end)}`);
    row.querySelector('.t .tag').hidden = !v.live && !v.keep;
    row.querySelector('.t .tag').className = 'tag ' + (v.live ? 'live' : 'kept');
    setText(row.querySelector('.t .tag'), v.live ? t('rec_live') : t('rec_kept'));
    if (REC.dl?.id !== v.id) setText(row.querySelector('.s'), [fmtSecsLong(v.dur || 0), fmtSize(v.size || 0), v.codec ? v.codec.toUpperCase().replace('H264', 'H.264') : null].filter(Boolean).join(' · '));
    setText(row.querySelector('.acts .keep span'), v.keep ? t('rec_kept') : t('rec_keep_btn'));
    row.querySelector('.acts .keep').classList.toggle('on', !!v.keep);
    row.querySelector('.acts .keep').hidden = !!v.live;
    row.querySelector('.acts .del').hidden = !!v.live;
    row.querySelector('.acts').hidden = REC.open !== v.id;
    row.classList.toggle('open', REC.open === v.id);
    if (list.children[i] !== row) list.insertBefore(row, list.children[i] || null);
  });
  old.forEach(r => r.remove());
  recShowProgress();
}
/** "28 min", "1 h 5 min": recordings are long. */
function fmtSecsLong(s) {
  s = Math.round(s);
  if (s < 60) return t('secs', { s });
  const m = Math.round(s / 60);
  return m < 60 ? t('n_mins', { n: m }) : fmtDuration(m * 60);
}

function recRow(id) {
  const row = document.createElement('div'); row.className = 'rec-item'; row.dataset.id = id;
  row.innerHTML = '<div class="tl-item"><span class="tl-thumb"><svg class="i"><use href="#i-rec"/></svg></span>' +
    '<div class="grow"><div class="t"><span class="when"></span><span class="tag" hidden></span></div><div class="s"></div><div class="bar" hidden><i></i></div></div>' +
    '<button type="button" class="icon-btn cut"><svg class="i"><use href="#i-cut"/></svg></button></div>' +
    '<div class="acts" hidden>' +
    '<button type="button" class="rec-act dl"><svg class="i"><use href="#i-download"/></svg><span></span></button>' +
    '<button type="button" class="rec-act keep owner-only"><svg class="i"><use href="#i-pin"/></svg><span></span></button>' +
    '<button type="button" class="rec-act del owner-only"><svg class="i"><use href="#i-trash"/></svg><span></span></button></div>';
  const cut = row.querySelector('.cut'), dl = row.querySelector('.acts .dl'), keep = row.querySelector('.acts .keep'), del = row.querySelector('.acts .del');
  cut.setAttribute('aria-label', t('rec_cut_btn')); cut.title = t('rec_cut_btn');
  setText(dl.querySelector('span'), t('rec_dl_btn')); setText(del.querySelector('span'), t('rec_del_btn'));
  row.querySelector('.tl-item').onclick = e => {
    if (e.target.closest('.cut')) return;
    REC.open = REC.open === id ? null : id; renderRecDay();
  };
  cut.onclick = () => REC.dl?.id === id ? recCancel() : openCut(row.rec);
  dl.onclick = () => REC.dl?.id === id ? recCancel() : recDownload(row.rec);
  keep.onclick = () => {
    const on = !row.rec.keep;
    if (recSend({ op: 'keep', id, on })) { row.rec.keep = on; renderRecDay(); toast(t(on ? 'rec_keep_on' : 'rec_keep_off'), 2400); }
  };
  del.onclick = () => recDelete(row.rec, del);
  const thumb = REC.thumbs[id];
  if (thumb) recPutThumb(row.querySelector('span.tl-thumb'), thumb);
  return row;
}
function recPutThumb(ph, src) {
  const img = document.createElement('img'); img.className = 'tl-thumb'; img.alt = ''; img.src = src; ph.replaceWith(img);
}
function recShowThumb(id) {
  const ph = document.querySelector(`.rec-item[data-id="${CSS.escape(id)}"] span.tl-thumb`);
  if (ph && REC.thumbs[id]) recPutThumb(ph, REC.thumbs[id]);
}
/** A block on the timeline was tapped: its row opens and comes into view. */
function recFocus(id) {
  REC.open = id; renderRecDay();
  const row = document.querySelector(`.rec-item[data-id="${CSS.escape(id)}"]`);
  row?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

function recDelete(v, btn) {
  if (REC.confirmDel !== v.id) {
    REC.confirmDel = v.id;
    btn.classList.add('confirm');
    toast(t('tl_delete_again'), 2400);
    setTimeout(() => { if (REC.confirmDel === v.id) { REC.confirmDel = null; btn.classList.remove('confirm'); } }, 3000);
    return;
  }
  REC.confirmDel = null;
  if (recSend({ op: 'del', id: v.id })) { REC.items = REC.items.filter(x => x.id !== v.id); REC.open = null; renderRecDay(); }
}

// ---------- Cut a part ----------
function openCut(v) {
  const dur = Math.max(5, Math.floor(v.dur || 0));
  // A recording still being written: start near its end (what just happened)
  const len = Math.min(300, dur), from = v.live ? Math.max(0, dur - len) : 0;
  REC.cut = { v, from, len };
  const r = $('cutFrom'); r.max = Math.max(0, dur - 5); r.value = from;
  setSeg('selCutLen', len);
  renderCut();
  $('cutDlg').hidden = false;
}
function renderCut() {
  const c = REC.cut; if (!c) return;
  const v = c.v, dur = v.dur || 0, end = Math.min(dur, c.from + c.len);
  fillRange($('cutFrom'));
  setText($('cutHead'), `${recDayLabel(REC.day)} · ${fmtClock(v.start)} – ${v.live ? '…' : fmtClock(v.start + dur * 1000)}`);
  setText($('cutRange'), `${fmtClockS(v.start + c.from * 1000)} – ${fmtClockS(v.start + end * 1000)}`);
  setText($('cutFromVal'), fmtClockS(v.start + c.from * 1000));
  const size = dur > 0 && v.size ? v.size / dur * (end - c.from) : 0;
  setText($('cutInfo'), [size ? t('cut_info', { s: fmtSize(size) }) : null, v.live ? t('rec_live_partial') : null].filter(Boolean).join(' · '));
}
$('cutFrom').addEventListener('input', () => { if (REC.cut) { REC.cut.from = +$('cutFrom').value; renderCut(); } });
onSeg('selCutLen', v => { if (REC.cut) { REC.cut.len = +v; renderCut(); } });
$('cutGo').onclick = () => {
  const c = REC.cut; if (!c) return;
  $('cutDlg').hidden = true; REC.cut = null;
  recDownload(c.v, c.from, c.from + c.len);
};
$('cutDlg').addEventListener('click', e => { if (e.target === $('cutDlg')) { $('cutDlg').hidden = true; REC.cut = null; } });

// ---------- Download, then play here ----------
function recDownload(v, from = null, to = null) {
  if (REC.dl) { toast(t('tl_one_at_a_time')); return; }
  const msg = from == null ? { op: 'get', id: v.id } : { op: 'clip', id: v.id, from, to };
  if (!recSend(msg)) { toast(t('tl_not_connected')); return; }
  REC.dl = { id: v.id, size: 0, got: 0, name: `Camerold ${v.id}.mp4`, codec: v.codec || recCodec(), prep: from != null || !!v.live, parts: [], pending: [], pendingBytes: 0 };
  recShowProgress();
}
function recCancel() { recSend({ op: 'cancel' }); }

function recDownloadBegin(m) {
  const d = REC.dl;
  d.size = m.size; d.name = m.name || d.name; d.prep = false;
  if (IN_APP && window.CameroldApp?.fileOpen) {
    const r = CameroldApp.fileOpen(d.name);
    if (r !== 'ok') { recCancel(); REC.dl = null; renderRecDay(); toast(t(r === 'perm' ? 'snap_perm' : 'tl_dl_fail'), 3000); return; }
    d.app = true;
  }
  recShowProgress();
}

function recOnChunk(buf) {
  const d = REC.dl;
  if (!d) return;
  d.got += buf.byteLength;
  if (d.app) {
    d.pending.push(buf); d.pendingBytes += buf.byteLength;
    if (d.pendingBytes >= 1 << 20 && !tlFlush(d)) { recCancel(); recDownloadDone(false); return; }
  } else d.parts.push(buf);
  if (!d.raf) d.raf = requestAnimationFrame(() => { d.raf = 0; recShowProgress(); });
}

function recShowProgress() {
  const d = REC.dl;
  for (const row of document.querySelectorAll('.rec-item')) {
    const mine = d && row.dataset.id === d.id;
    row.querySelector('.bar').hidden = !mine;
    row.querySelector('.cut use').setAttribute('href', mine ? '#i-x' : '#i-cut');
    row.querySelector('.acts .dl use').setAttribute('href', mine ? '#i-x' : '#i-download');
    if (mine) {
      const pct = d.size ? Math.min(100, d.got / d.size * 100) : 0;
      row.querySelector('.bar i').style.width = pct + '%';
      row.querySelector('.bar').classList.toggle('busy', !!d.prep);
      setText(row.querySelector('.s'), d.prep ? t('rec_preparing') : t('tl_downloading', { p: fmtNum(pct) }));
    }
  }
}

function recDownloadDone(ok, cancelled = false) {
  const d = REC.dl;
  if (!d) return;
  REC.dl = null;
  ok = ok && d.got >= d.size && d.size > 0;
  if (d.app) {
    if (ok && !tlFlush(d)) ok = false;
    CameroldApp.fileClose(ok);
    if (ok) toast(t('tl_saved_app'), 3000);
  } else if (ok) openPlayer(new Blob(d.parts, { type: 'video/mp4' }), d.name, d.codec);
  if (!ok && !cancelled) toast(t('tl_dl_fail'), 3000);
  renderRecDay();
}

let playUrl = null;
function openPlayer(blob, name, codec) {
  closePlayer();
  playUrl = URL.createObjectURL(blob);
  const v = $('playVideo'), canPlay = codec !== 'hevc' || HEVC_PLAYS;
  v.hidden = !canPlay;
  if (canPlay) v.src = playUrl;
  setText($('playTitle'), name.replace(/\.mp4$/i, ''));
  setText($('playNote'), canPlay ? fmtSize(blob.size) : t('play_hevc'));
  $('playSave').onclick = () => {
    const a = document.createElement('a'); a.href = playUrl; a.download = name;
    document.body.appendChild(a); a.click(); a.remove();
    toast(t('tl_saved'), 2400);
  };
  $('playDlg').hidden = false;
  if (canPlay) v.play().catch(() => {});
}
function closePlayer() {
  const v = $('playVideo');
  v.pause(); v.removeAttribute('src'); v.load();
  if (playUrl) { const u = playUrl; setTimeout(() => URL.revokeObjectURL(u), 30000); playUrl = null; }
  $('playDlg').hidden = true;
}
$('playClose').onclick = closePlayer;
$('playDlg').addEventListener('click', e => { if (e.target === $('playDlg')) closePlayer(); });

/** Reconnecting: the old data channel is gone (a download in it too); the state stays until new info arrives. */
function recDropChannel() {
  if (REC.dl) recDownloadDone(false, true);
  REC.ch = null; REC.stale = true;
}
/** Left the camera: hide everything about its recordings. */
function recReset() {
  recDropChannel();
  Object.assign(REC, { info: null, days: [], day: null, items: [], thumbs: {}, open: null, denied: false, cut: null });
  $('recBox').hidden = true; $('recNone').hidden = false; $('recTabDot').hidden = true; $('recPill').hidden = true;
  $('recDays').replaceChildren(); $('recList').replaceChildren(); $('recTrack').replaceChildren();
  $('recDlg').hidden = true; $('cutDlg').hidden = true;
}

// ---------- Wiring ----------
onSeg('selRecQ', v => sendRec({ q: +v }));
onSeg('selRecFps', v => sendRec({ fps: +v }));
onSeg('selRecDays', v => sendRec({ days: +v }));
onSeg('selRecFmt', v => sendRec({ h264: v === 'h264' }));
$('tRecView').onchange = () => sendRec({ viewOnly: $('tRecView').checked });
$('recGo').onclick = recStartStop;
$('recSetRow').onclick = openRecSettings;
$('recDlgDone').onclick = closeRecSettings;
$('recDlg').addEventListener('click', e => { if (e.target === $('recDlg')) closeRecSettings(); });
$('recPill').onclick = e => { e.stopPropagation(); openPanel('rec'); };
// The live recording grows: refresh its length once a minute while the tab shows it
setInterval(() => { if (S.tab === 'rec' && REC.items.some(v => v.live)) recRequestDay(); }, 60000);

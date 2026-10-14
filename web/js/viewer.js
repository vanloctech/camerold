// ====================== Viewer state ======================
const S = {
  sig: null, topic: '', viewerId: '', session: 0,
  pc: null, remoteSet: false, pendingIce: [], gotOffer: false,
  helloTimer: null, helloCount: 0, discTimer: null, statsTimer: null,
  iceServers: DEFAULT_ICE, codec: 'H264', lastBytes: 0, lastTs: 0, wakeLock: null,
  fs: false, audioAvail: false, muted: true, camName: 'Camera',
  viewOnly: false, // came in with the camera's view-only code (the camera tells, see setRole)
};
const IN_APP = !!window.CameroldApp || new URLSearchParams(location.search).get('app') === '1';
// ====================== Display state ======================
function setStatus(text, kind) {
  $('statusText').textContent = text;
  $('camSub').textContent = text;
  $('dot').className = 'dot' + (kind === 'ok' ? ' ok' : kind === 'bad' ? ' bad' : '');
}
function toast(text, ms = 2200) {
  const t = $('toast'); t.textContent = text; t.hidden = false;
  clearTimeout(toast.timer); toast.timer = setTimeout(() => { t.hidden = true; }, ms);
}

// ====================== Save / load ======================
function loadSaved() {
  try {
    const s = JSON.parse(localStorage.getItem('camerold') || '{}');
    $('codec').value = s.codec ?? 'H264';
    $('turnUrl').value = s.turnUrl || ''; $('turnUser').value = s.turnUser || ''; $('turnPass').value = s.turnPass || '';
    S.savedCamIce = Array.isArray(s.camIce) ? s.camIce : [];
    S.muted = s.muted !== false;
    Z.fill = !!s.fill;
    showSavedTurn();
  } catch {}
  applyHash();
}
/** Link/QR of the form #r=ID&k=PASSWORD[&auto=1]: prefill, auto-connect, then clear it from the address bar so the password doesn't linger. */
function applyHash() {
  const h = new URLSearchParams(location.hash.slice(1));
  if (!h.get('r')) return;
  $('room').value = h.get('r');
  if (h.get('k')) $('pass').value = h.get('k');
  if (h.get('b')) useLinkBroker(h.get('b'), h.get('p') !== '0');
  try { history.replaceState(null, '', location.pathname + location.search); } catch {}
  if (h.get('k') && h.get('auto') === '1' && $('viewer').hidden) start();
}
window.addEventListener('hashchange', applyHash);
/** Name of a saved camera (from the camera itself), shown while connecting next time. */
function knownCamName(room) { return findCam(room)?.name || null; }

/**
 * The camera says which code this viewer used: "owner" (full control) or "view" (watch only). It refuses changes
 * from the view-only code itself; here its controls are hidden. Owners get the view-only code too, to share it.
 */
function setRole(role, viewPass) {
  const view = role === 'view';
  S.viewOnly = view;
  $('viewer').classList.toggle('viewonly', view);
  if (view && S.tab === 'pic') selectTab('tl');
  if (typeof renderRec === 'function') renderRec(); // view-only: recordings only if the owner allows it
  const cam = S.room && findCam(S.room);
  if (!cam) return;
  const patch = {};
  if (!!cam.view !== view) patch.view = view;
  if (!view && typeof viewPass === 'string' && viewPass.length >= 8 && cam.viewPass !== viewPass) patch.viewPass = viewPass;
  if (view && cam.viewPass) patch.viewPass = undefined;
  if (Object.keys(patch).length) putCam({ room: cam.room, ...patch }, false);
}
function rememberCamName(name) {
  const cam = S.room && findCam(S.room);
  if (cam && (cam.name || null) !== name) putCam({ room: cam.room, name }, false);
}

// ====================== Connection server (MQTT broker) ======================
function savedSettings() { try { return JSON.parse(localStorage.getItem('camerold') || '{}'); } catch { return {}; } }
/** The camera's own broker, when this viewer uses one; null = public brokers only. */
function ownBroker() {
  const s = savedSettings();
  if (s.brokerMode !== 'own') return null;
  const url = ownBrokerUrl({ provider: s.brokerProvider, host: s.brokerHost, user: s.brokerUser, pass: s.brokerPass });
  return url && isBrokerUrl(url) ? { url, backup: s.brokerBackup !== false } : null;
}
/** Broker that came with a QR code / link: used for that camera (and saved with it). */
function useLinkBroker(url, backup) {
  if (isBrokerUrl(url)) S.linkBroker = { url, backup };
}

function showSavedTurn() {
  const ice = S.savedCamIce || [];
  $('turnSavedRow').hidden = !ice.length;
}
function savePatch(patch) {
  try {
    const s = JSON.parse(localStorage.getItem('camerold') || '{}');
    localStorage.setItem('camerold', JSON.stringify({ ...s, ...patch }));
  } catch {}
  if ('cams' in patch) syncAppCams(); // the app's background battery alerts (cams.js)
}
/** Saves the relay server sent by the camera so it can be used right away next time. */
function rememberCamIce(ice) {
  if (!ice.length || JSON.stringify(ice) === JSON.stringify(S.savedCamIce)) return;
  S.savedCamIce = ice; showSavedTurn(); savePatch({ camIce: ice });
}
function save() {
  const data = { codec: $('codec').value, turnUrl: $('turnUrl').value.trim(), turnUser: $('turnUser').value.trim(),
    turnPass: $('turnPass').value, muted: S.muted, fill: Z.fill };
  if (S.savedCamIce && S.savedCamIce.length) data.camIce = S.savedCamIce;
  savePatch(data); // keeps the other saved settings (cameras, connection server…)
}

// ====================== Connection ======================
/** cam: a saved camera (tapped in the list); without it, the code/password typed or filled in from a QR code. */
async function start(cam) {
  if (!(cam && cam.room)) cam = null;
  const room = cam ? cam.room : normalizeRoom($('room').value), pass = cam ? cam.pass : $('pass').value;
  if (room.length < 6) { $('loginMsg').textContent = t('err_code'); return; }
  if (!pass) { $('loginMsg').textContent = t('err_pass'); return; }
  if (!window.crypto || !crypto.subtle) { $('loginMsg').textContent = t('err_https'); return; }
  // Connection server: the saved camera's own, the one its QR code carried, or this viewer's setting
  const broker = cam ? (cam.broker || null) : (S.linkBroker || ownBroker());
  S.linkBroker = null; S.room = room;
  restoreRotation(room);
  if (cam || $('remember').checked) putCam({ room, pass, broker, last: Date.now() });
  save();
  $('loginMsg').textContent = ''; $('go').disabled = true; $('go').textContent = t('securing');

  S.codec = $('codec').value;
  S.iceServers = [...DEFAULT_ICE];
  const turn = parseTurnUrls($('turnUrl').value);
  if (turn.length) S.iceServers.push({ urls: turn, username: $('turnUser').value.trim(), credential: $('turnPass').value.trim() });
  else if (S.savedCamIce && S.savedCamIce.length) S.iceServers.push(...S.savedCamIce);

  const { key, topic: base } = await deriveKeys(room, pass);
  S.topic = base; S.viewerId = randomId(10); S.session = 1; S.gotOffer = false; S.helloCount = 0;
  S.sig = new Signaling(key, `${base}/v/${S.viewerId}`, onSignal, onBrokers, brokersFor(broker));
  S.sig.start();

  $('go').disabled = false; $('go').textContent = t('watch');
  $('login').hidden = true; $('viewer').hidden = false; $('viewer').scrollTop = 0;
  setCamName(knownCamName(room) || t('cam_default')); setStatus(t('connecting')); resetOverlay(); showLoading('net');
  setAudioUi(); updateMode(); showCtl(false); requestWakeLock(); appWatching(true);
  $('bPip').hidden = !pipSupported(); fitCtl();
  S.helloTimer = setInterval(helloTick, 4000);
  detectNat().then(n => { S.nat = n; }).catch(() => {});
}

function stopAll() {
  saveCamShot(); // the last picture, for the cameras list
  clearInterval(S.helloTimer); clearInterval(S.statsTimer); clearTimeout(S.discTimer);
  if (S.sig) { try { sendCam({ t: 'bye' }); } catch {} setTimeout((sig => () => sig.stop())(S.sig), 300); }
  closePc(); S.sig = null; tlReset(); recReset();
  $('video').srcObject = null; resetOverlay(); $('heatPill').hidden = true; $('warnBadge').hidden = true;
  S.viewers = 0; $('viewersPill').hidden = true;
  S.viewOnly = false; $('viewer').classList.remove('viewonly');
  S.health = null; S.heat = 0; S.warnRank = 0; clearTimeout(S.warnTimer);
  setSheet(false); setFullscreen(false); exitPip(); appWatching(false);
  $('viewer').hidden = true; $('login').hidden = false;
  $('pass').value = ''; renderCams(); // names and "last watched" may have changed
  if (S.wakeLock) { S.wakeLock.release().catch(() => {}); S.wakeLock = null; }
}

function sendCam(msg) {
  if (msg.t === 'cfg' && S.viewOnly) { toast(t('view_only_toast'), 2400); return Promise.resolve(); } // the camera would ignore it
  msg.v = S.viewerId; msg.s = S.session;
  return S.sig.send(`${S.topic}/c`, msg);
}
function hello() {
  if (!S.sig || !S.sig.up) return;
  S.helloCount++;
  sendCam({ t: 'hello', ice: S.iceServers.slice(DEFAULT_ICE.length) });
}
function helloTick() {
  if (S.pc && S.pc.connectionState === 'connected') return;
  if (!S.gotOffer) {
    hello();
    if (S.helloCount >= 3) {
      showProblem('problem', 'not_found_t', 'not_found');
      setStatus(t('searching'), 'bad');
    }
  }
}
function onBrokers(up, total, justUp) {
  if (!S.sig) return;
  if (!S.pc || S.pc.connectionState !== 'connected')
    setStatus(up ? t('searching') : t('no_internet'), up ? '' : 'bad');
  if (up && OV.mode === 'loading' && OV.stage < 1) showLoading('find');
  if (justUp && !S.gotOffer) hello();
}
function restartSession(reason) {
  closePc();
  S.session++; S.gotOffer = false; S.helloCount = 0;
  setStatus(t('reconnecting'), 'bad'); showProblem('problem', reason.title, reason.text); showCtl(false);
  hello();
}
function closePc() {
  if (S.pc) { S.pc.ontrack = S.pc.onicecandidate = S.pc.onconnectionstatechange = null; S.pc.close(); }
  S.pc = null; S.remoteSet = false; S.pendingIce = [];
  clearInterval(S.statsTimer);
  if (typeof tlDropChannel === 'function') tlDropChannel(); // a new connection brings new ones
  if (typeof recDropChannel === 'function') recDropChannel();
}

async function onSignal(m) {
  if (!S.sig || m.s !== S.session) return;
  if (m.t === 'offer') {
    if (S.gotOffer) return;
    S.gotOffer = true;
    updateInfo(m);
    S.camIce = Array.isArray(m.ice) ? m.ice : [];
    rememberCamIce(S.camIce);
    await handleOffer(m.sdp);
  } else if (m.t === 'ice') {
    const c = m.c;
    if (S.pc && S.remoteSet) S.pc.addIceCandidate(c).catch(() => {});
    else S.pendingIce.push(c);
  } else if (m.t === 'info') {
    updateInfo(m);
  }
}

async function handleOffer(sdp) {
  closePc();
  showLoading('link');
  const pc = new RTCPeerConnection({ iceServers: [...S.iceServers, ...(S.camIce || [])], bundlePolicy: 'max-bundle' });
  S.pc = pc;
  pc.ontrack = e => {
    const v = $('video');
    const stream = e.streams[0] || (v.srcObject instanceof MediaStream ? v.srcObject : new MediaStream());
    if (!e.streams[0]) stream.addTrack(e.track);
    if (v.srcObject !== stream) v.srcObject = stream;
    v.muted = S.muted || !S.audioAvail;
    v.play().catch(() => { v.muted = true; v.play().catch(() => {}); });
  };
  pc.onicecandidate = e => { if (e.candidate && e.candidate.candidate) sendCam({ t: 'ice', c: e.candidate.toJSON() }); };
  // Timelapse videos (timelapse.js) and recordings (rec.js)
  pc.ondatachannel = e => { if (e.channel.label === 'tl') tlAttach(e.channel); else if (e.channel.label === 'rec') recAttach(e.channel); };
  pc.onconnectionstatechange = () => {
    if (S.pc !== pc) return;
    const st = pc.connectionState;
    clearTimeout(S.discTimer);
    if (st === 'connected') {
      setStatus(t('watching'), 'ok');
      if (OV.hadVideo || $('video').readyState >= 2) { OV.hadVideo = true; if (OV.mode !== 'busy') hideOverlay(); }
      startStats(); showCtl();
    } else if (st === 'disconnected') {
      setStatus(t('weak_signal'), 'bad');
      S.discTimer = setTimeout(() => { if (S.pc === pc && pc.connectionState !== 'connected') restartSession({ title: 'lost_t', text: 'lost' }); }, 7000);
    } else if (st === 'failed') {
      restartSession(failReason());
    }
  };
  await pc.setRemoteDescription({ type: 'offer', sdp });
  try {
    if (S.codec && RTCRtpReceiver.getCapabilities) {
      const codecs = RTCRtpReceiver.getCapabilities('video').codecs;
      const want = 'video/' + S.codec.toLowerCase();
      const pref = codecs.filter(c => c.mimeType.toLowerCase() === want);
      const rest = codecs.filter(c => c.mimeType.toLowerCase() !== want);
      if (pref.length) pc.getTransceivers().forEach(t => { if (t.receiver.track.kind === 'video') t.setCodecPreferences([...pref, ...rest]); });
    }
  } catch (e) { console.warn('setCodecPreferences', e); }
  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);
  await sendCam({ t: 'answer', sdp: pc.localDescription.sdp });
  S.remoteSet = true;
  for (const c of S.pendingIce) pc.addIceCandidate(c).catch(() => {});
  S.pendingIce = [];
  // Not connected after 25 seconds -> start over
  setTimeout(() => { if (S.pc === pc && pc.connectionState !== 'connected') restartSession(failReason()); }, 25000);
}

// ====================== Info from the camera ======================
function setCamName(name) {
  S.camName = name;
  $('camTitle').textContent = name; $('camTitle2').textContent = name;
  document.title = name + ' · Camerold';
}
/** "Wide 0.6x" -> "0.6×", "Main 1x" -> "1×": short lens labels for the control bar (also accepts "0,6x"). */
const shortLens = label => { const m = /(\d+(?:[.,]\d+)?)\s*x\b/i.exec(label); return m ? m[1].replace(',', '.') + '×' : label; };

function updateInfo(m) {
  if (m.role) setRole(m.role, m.viewPass);
  // The name set on the camera phone, else "Camera · <model>"; remembered per camera code for next time
  const name = typeof m.name === 'string' && m.name.trim() ? m.name.trim().slice(0, 40) : null;
  if (name) setCamName(name);
  else if (m.model) setCamName(t('cam_default') + ' · ' + m.model);
  if (m.name !== undefined) rememberCamName(name);
  if (m.caps?.w) { S.capW = m.caps.w; S.capH = m.caps.h; } // capture size, for timelapse estimates
  if (m.caps?.maxRes) {
    // Qualities the camera phone's video chip can't encode are greyed out
    S.maxRes = m.caps.maxRes;
    for (const b of $('selRes').querySelectorAll('button')) b.disabled = +b.dataset.v > S.maxRes;
  }
  if (m.res) setSeg('selRes', Math.min(m.res, S.maxRes || m.res));
  if (m.fps) setSeg('selFps', m.fps);
  if (m.aspect) setSeg('selAspect', m.aspect);
  if (m.audio != null) { S.audioAvail = !!m.audio; setAudioUi(); }
  if (m.lenses) {
    // Back lenses (0.6× / 1× / 2×…) in the control bar; the front camera is a separate switch (chip + settings),
    // which keeps the bar short enough for a phone held sideways
    const isFront = l => l.front ?? /front|trước/i.test(l.label);
    const back = m.lenses.filter(l => !isFront(l)), front = m.lenses.find(isFront);
    S.lensFront = front ? front.k : null;
    const onFront = !!front && m.lens === front.k;
    if (!onFront && m.lens) S.lensBack = m.lens;
    if (!back.some(l => l.k === S.lensBack)) S.lensBack = (back.find(l => shortLens(l.label) === '1×') || back[0])?.k || null;
    // Every setting change brings this again: the buttons are only rebuilt when the lenses themselves differ
    const box = $('lenses'), shown = back.length > 1 ? back : [];
    const key = shown.map(l => l.k + '=' + l.label).join('|');
    if (box.dataset.key !== key) {
      box.dataset.key = key;
      box.replaceChildren(...shown.map(l => {
        const b = document.createElement('button');
        b.textContent = shortLens(l.label); b.title = l.label; b.dataset.k = l.k;
        b.onclick = () => { sendCam({ t: 'cfg', lens: l.k }); showCtl(); };
        return b;
      }));
    }
    for (const b of box.children) b.classList.toggle('on', b.dataset.k === m.lens);
    $('qcFront').hidden = $('rowFront').hidden = $('bFlip').hidden = !front;
    $('qcFront').classList.toggle('on', onFront); $('bFlip').classList.toggle('on', onFront); setTog('tFront', onFront);
  }
  const c = m.caps;
  if (c) {
    const z = $('zoomBar'); z.min = c.zoomMin; z.max = Math.max(c.zoomMax, c.zoomMin + 0.1);
    z.disabled = c.zoomMax - c.zoomMin < 0.05;
    $('rowTorch').hidden = !c.torch; $('qcTorch').hidden = $('bTorch').hidden = !c.torch; $('rowStab').hidden = !c.stab;
    const fm = { auto: true, point: c.focusPoint, lock: c.focusLock, manual: c.focusManual, inf: c.focusInf };
    for (const b of $('selFocus').querySelectorAll('button')) b.disabled = !fm[b.dataset.v];
    S.canFocusPoint = !!c.focusPoint && !S.viewOnly;
    $('focusHint').hidden = !c.focusPoint;
    const anyFocus = c.focusPoint || c.focusLock || c.focusManual || c.focusInf;
    $('focusTitle').hidden = $('focusGroup').hidden = !anyFocus;
    $('moreTitle').hidden = $('moreGroup').hidden = !c.stab;
    const ev = $('ev'); ev.min = c.evMin; ev.max = c.evMax; ev.dataset.step = c.evStep || 0;
    $('selFps').querySelector('[data-v="60"]').disabled = c.maxFps < 60;
  }
  if (m.zoom != null && !barDragging) $('zoomBar').value = m.zoom;
  showZoomBar();
  if (m.torch != null) { setTog('tTorch', m.torch); $('qcTorch').classList.toggle('on', !!m.torch); $('bTorch').classList.toggle('on', !!m.torch); }
  if (m.lenses || c) fitCtl();
  if (m.night != null) { setTog('tNight', m.night); $('qcNight').classList.toggle('on', !!m.night); }
  if (m.stab != null) setTog('tStab', m.stab);
  if (m.ev != null) { $('ev').value = m.ev; showEv(); }
  if (m.focus) { setSeg('selFocus', m.focus); $('rowFdist').hidden = m.focus !== 'manual'; }
  if (m.fdist != null && !fdistDragging) { $('fdist').value = m.fdist; showFdist(); }
  if (m.turn) showCamTurn(m.turn);
  if (m.tl) onTimelapseInfo(m.tl);
  if (m.rec) onRecInfo(m.rec);
  if (m.viewers != null) showViewers(m.viewers);
  if (m.heatGuard != null) {
    $('heatGroupBox').hidden = false;
    setTog('tHeat', m.heatGuard);
    $('rowHeatStart').classList.toggle('off', !m.heatGuard);
    if (m.heatStart && !heatDragging) { $('heatStart').value = m.heatStart; showHeatStart(); }
  }
  if (m.heat != null) S.heat = m.heat;
  if (m.health) S.health = m.health;
  if (m.heat != null || m.health) camWarning();
  if (m.camBusy != null && S.pc && S.pc.connectionState === 'connected') {
    if (m.camBusy) { S.busyShown = true; showProblem('busy', 'cam_busy_t', 'cam_busy'); }
    else if (S.busyShown) { S.busyShown = false; hideOverlay(); }
  }
}

/** People watching this camera right now (this device included), from the camera. */
function showViewers(n) {
  S.viewers = Math.max(1, n || 0); // the count can lag a moment behind our own connection
  $('viewersN').textContent = fmtNum(S.viewers);
  $('viewersPill').hidden = false;
  $('viewersPill').title = t('n_watching', { n: S.viewers }); $('viewersPill').setAttribute('aria-label', $('viewersPill').title);
}

/**
 * One small notice on top of the video about the camera phone itself, most urgent first:
 * battery running out without a charger (usually a loose cable), then heat (it lowers its own quality when hot).
 */
function camWarning() {
  const h = S.health || {};
  let icon = null, text = '', rank = 0;
  if (h.bat != null && h.bat <= 20 && h.chg === false) { icon = 'i-battery'; text = t('bat_low', { n: h.bat }); rank = 10; }
  else if (S.heat) { icon = 'i-heat'; text = t('heat_' + Math.min(S.heat, 3)); rank = S.heat; }
  S.warnText = text;
  $('warnBadge').hidden = !icon;
  if (!icon) { S.warnRank = 0; $('heatPill').hidden = true; return; }
  $('warnBadgeIcon').setAttribute('href', '#' + icon); $('heatIcon').setAttribute('href', '#' + icon);
  $('warnBadge').setAttribute('aria-label', text); $('warnBadge').title = text;
  // Pop the notice only when it's new or got worse; afterwards just the small icon next to the title stays
  if (rank > (S.warnRank || 0)) flashWarning();
  S.warnRank = rank;
}
function flashWarning() {
  $('heatText').textContent = S.warnText;
  $('heatPill').hidden = false;
  clearTimeout(S.warnTimer);
  S.warnTimer = setTimeout(() => { $('heatPill').hidden = true; }, 5000);
}
const fmtNum = (n, d = 0) => new Intl.NumberFormat(LANG, { maximumFractionDigits: d }).format(n);
function fmtDuration(s) {
  const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
  return d ? t('dur_dh', { d, h }) : h ? (m ? t('dur_hm', { h, m }) : t('n_hours', { n: h })) : t('dur_m', { m });
}
/** Rows for the info panel about the camera phone (battery, temperature, storage, running time). */
function healthRows() {
  const h = S.health;
  if (!h) return [];
  const rows = [];
  if (h.bat != null) rows.push([t('h_battery'), `${h.bat}% · ${t(h.chg ? 'h_charging' : 'h_not_charging')}`]);
  if (h.temp != null) rows.push([t('h_temp'), `${fmtNum(h.temp, 1)} °C`]);
  if (S.heat) rows.push([t('h_cooling'), t('heat_' + Math.min(S.heat, 3))]);
  if (h.free != null) rows.push([t('h_free'), `${fmtNum(h.free / 1e9, 1)} GB`]);
  if (h.up != null) rows.push([t('h_up'), fmtDuration(h.up)]);
  return rows;
}

/** Front camera on/off: back to the last back lens used (usually 1×). */
/**
 * The zoom level and buttons on the video bar, least important first: hidden one by one while the bar is too narrow (portrait phone).
 * Full screen always stays; the others are also in the chips under the video and in settings.
 */
const CTL_DROP = ['zoomTxt', 'bTorch', 'bFlip', 'bRotate', 'bPip'];
function fitCtl() {
  const row = $('bFull').parentElement;
  for (const id of CTL_DROP) $(id).classList.remove('squeezed');
  if (!row.clientWidth) return;
  const need = () => {
    let w = 0;
    for (const el of row.children) {
      if (!el.offsetWidth || el.classList.contains('grow')) continue;
      w += (el.id === 'lenses' || el.id === 'zoomTxt' ? el.scrollWidth : el.offsetWidth) + 2;
    }
    return w;
  };
  for (const id of CTL_DROP) {
    if (need() <= row.clientWidth) break;
    $(id).classList.add('squeezed');
  }
}
new ResizeObserver(fitCtl).observe($('bFull').parentElement);

function setFrontCamera(on) {
  const k = on ? S.lensFront : S.lensBack;
  if (k) sendCam({ t: 'cfg', lens: k });
  showCtl();
}

// ---------- Relay server on the camera (typed here, sent there) ----------
function showCamTurn(tj) {
  $('camTurnBox').hidden = false;
  const st = $('camTurnStatus');
  const host = (tj.urls || '').split(/[,\s]+/).filter(Boolean)[0] || '';
  st.className = 's' + (tj.ok === true ? ' ok' : tj.ok === false ? ' bad' : '');
  st.textContent = !host ? t('cam_turn_none')
    : tj.ok === true ? t('cam_turn_ok', { h: host }) : tj.ok === false ? t('cam_turn_bad', { h: host })
    : S.turnSent ? t('cam_turn_testing') : host;
  // Show what the camera has (never its password) unless the user is typing
  if (!S.turnEditing) { $('camTurnUrl').value = tj.urls || ''; $('camTurnUser').value = tj.user || ''; }
  if (tj.ok != null) { S.turnSent = false; $('camTurnSend').disabled = false; }
}
function sendCamTurn() {
  const urls = $('camTurnUrl').value.trim(), user = $('camTurnUser').value.trim(), pass = $('camTurnPass').value.trim();
  if (urls && (!user || !pass)) { toast(t('cam_turn_need')); return; }
  sendCam({ t: 'cfg', turn: { urls, user, pass } });
  S.turnSent = !!urls; S.turnEditing = false;
  $('camTurnPass').value = '';
  $('camTurnSend').disabled = !!urls;
  $('camTurnStatus').className = 's';
  $('camTurnStatus').textContent = t(urls ? 'cam_turn_testing' : 'cam_turn_none');
  setTimeout(() => { $('camTurnSend').disabled = false; }, 15000);
}
/** Copy the relay server entered in this viewer's own Settings. */
function fillCamTurn() {
  const s = savedSettings();
  if (!s.turnUrl) { toast(t('cam_turn_no_saved')); return; }
  $('camTurnUrl').value = s.turnUrl; $('camTurnUser').value = s.turnUser || ''; $('camTurnPass').value = s.turnPass || '';
  S.turnEditing = true;
}

// ====================== Audio ======================
function setAudioUi() {
  const on = S.audioAvail && !S.muted;
  $('muteIcon').setAttribute('href', on ? '#i-vol' : '#i-mute');
  $('qcAudioIcon').setAttribute('href', on ? '#i-vol' : '#i-mute');
  $('qcAudio').classList.toggle('on', on);
  $('qcAudioText').textContent = t(!S.audioAvail ? 'chip_no_sound' : on ? 'chip_sound_on' : 'chip_sound_off');
  $('bMute').disabled = !S.audioAvail; $('qcAudio').disabled = !S.audioAvail;
  $('bMute').title = t(!S.audioAvail ? 'no_audio' : on ? 'mute' : 'unmute');
  const v = $('video');
  v.muted = !on;
  if (on) v.play().catch(() => { S.muted = true; setAudioUi(); toast(t('tap_unmute')); });
}
function toggleMute() {
  if (!S.audioAvail) { toast(t('no_audio')); return; }
  S.muted = !S.muted; savePatch({ muted: S.muted }); setAudioUi();
  toast(t(S.muted ? 'muted' : 'unmuted'), 1200);
}

// ====================== Zoom bar (replaces the seek bar) ======================
let barDragging = false, barTimer = null;
function showZoomBar() {
  const z = $('zoomBar');
  fillRange(z);
  $('zoomTxt').innerHTML = z.disabled ? '' : `${(+z.value).toFixed(1)}×<span class="max"> / ${(+z.max).toFixed(0)}×</span>`;
}
$('zoomBar').oninput = () => {
  barDragging = true; showZoomBar(); showCtl();
  clearTimeout(barTimer);
  barTimer = setTimeout(() => sendCam({ t: 'cfg', zoom: +$('zoomBar').value }), 100);
};
$('zoomBar').onchange = () => { barDragging = false; sendCam({ t: 'cfg', zoom: +$('zoomBar').value }); showCtl(); };

// ====================== YouTube-style controls: show on tap, auto-hide ======================
function showCtl(autoHide = true) {
  $('player').classList.remove('hide-ctl');
  clearTimeout(S.ctlTimer);
  if (autoHide) S.ctlTimer = setTimeout(() => { if (canHideCtl()) $('player').classList.add('hide-ctl'); }, 3500);
}
const canHideCtl = () => S.pc && S.pc.connectionState === 'connected' && !$('panel').classList.contains('open') && !barDragging;
function toggleUi() {
  if ($('player').classList.contains('hide-ctl')) showCtl();
  else if (canHideCtl()) { clearTimeout(S.ctlTimer); $('player').classList.add('hide-ctl'); }
}
$('ctl').addEventListener('pointerdown', e => { if (e.target.closest('button, input')) showCtl(); });

// ====================== Portrait / landscape / fullscreen ======================
// Three layouts: "stacked" (phone or tablet held upright: video on top, settings below), "split" (tablet held
// sideways, computer: video on the left, settings in a column on the right) and "immersive" (phone sideways, full screen,
// floating window: only the video, settings in a sheet over it).
function viewMode() {
  if (S.fs || S.pip) return 'immersive';
  if (innerWidth >= 960 && innerHeight >= 500) return 'split';
  return innerWidth > innerHeight ? 'immersive' : 'stacked';
}
function updateMode() {
  const mode = viewMode(), imm = mode === 'immersive';
  const v = $('viewer');
  if (!v.classList.contains(mode)) {
    for (const m of ['immersive', 'stacked', 'split']) v.classList.toggle(m, m === mode);
    setSheet(false);
  }
  // Connection info: overlaid on the video in landscape (info button), in the "Camera phone" tab in portrait
  const stats = $('stats'), home = imm ? $('player') : $('statsHome');
  if (stats.parentElement !== home) { home.appendChild(stats); stats.hidden = imm; }
  $('fullIcon').setAttribute('href', S.fs ? '#i-unfull' : '#i-full');
  $('backIcon').setAttribute('href', S.fs ? '#i-down' : '#i-back');
  requestAnimationFrame(layoutVideo);
}
function setFullscreen(on) {
  if (IN_APP && window.CameroldApp) { try { CameroldApp.setFullscreen(on); } catch {} return; } // the app reports back via onAppFullscreen
  if (on && !document.fullscreenElement) {
    document.documentElement.requestFullscreen?.().then(() => screen.orientation?.lock?.('landscape').catch(() => {})).catch(() => {});
  } else if (!on && document.fullscreenElement) document.exitFullscreen().catch(() => {});
  if (!document.fullscreenEnabled) { S.fs = on; updateMode(); } // browser doesn't support it: emulate
}
/** In the app: turning the phone sideways while watching enters full screen (and back), like YouTube. */
function appWatching(on) {
  if (IN_APP && window.CameroldApp?.setWatching) { try { CameroldApp.setWatching(on); } catch {} }
  // Black system bars under the video, page-colored ones on the lists and settings
  if (IN_APP && window.CameroldApp?.setDarkBars) { try { CameroldApp.setDarkBars(on); } catch {} }
}
window.onAppFullscreen = on => { S.fs = !!on; updateMode(); };
document.addEventListener('fullscreenchange', () => { S.fs = !!document.fullscreenElement; updateMode(); });
window.addEventListener('resize', updateMode);
// ====================== Picture in picture ======================
// In the app: Android's own floating window shows the whole page (rotation included), also when going Home while
// watching. In a browser: Chrome/Edge on a computer move the picture into a Document PiP window (rotation and
// zoom kept); elsewhere the <video>'s own PiP (the browser can't rotate it).
const DOC_PIP = 'documentPictureInPicture' in window;
const pipSupported = () => {
  if (IN_APP) { try { return !!window.CameroldApp?.canPip?.(); } catch { return false; } }
  return DOC_PIP || !!document.pictureInPictureEnabled;
};
/** Width and height of the picture as shown (rotation applied), for the floating window's shape. */
function pipShape() {
  const v = $('video'), w = v.videoWidth || 16, h = v.videoHeight || 9;
  return Z.rot % 180 ? [h, w] : [w, h];
}
let pipSent = '';
/** Keeps the app's floating window the shape of the picture (also for entering it by going Home). */
function appPipShape() {
  if (!IN_APP || !window.CameroldApp?.setPipShape) return;
  const [w, h] = pipShape(), k = w + 'x' + h;
  if (k === pipSent) return;
  pipSent = k;
  try { CameroldApp.setPipShape(w, h); } catch {}
}
async function enterPip() {
  const v = $('video');
  if (IN_APP) { const [w, h] = pipShape(); try { CameroldApp.enterPip(w, h); } catch {} return; }
  try {
    if (DOC_PIP) {
      if (documentPictureInPicture.window) return;
      const [w, h] = pipShape(), k = Math.min(1, 480 / Math.max(w, h));
      const win = await documentPictureInPicture.requestWindow({ width: Math.round(w * k), height: Math.round(h * k) });
      const st = win.document.createElement('style');
      st.textContent = 'html,body{margin:0;height:100%;background:#000;overflow:hidden}' +
        '#stage{position:absolute;inset:0;overflow:hidden}#zoom{position:absolute;inset:0;transform-origin:0 0}' +
        '#video{position:absolute;background:#000;transform-origin:50% 50%}';
      win.document.head.append(st);
      win.document.title = $('camTitle').textContent;
      win.document.body.append(stage);
      v.play().catch(() => {});
      win.addEventListener('resize', layoutVideo);
      win.addEventListener('pagehide', () => {
        $('player').prepend(stage);
        v.play().catch(() => {});
        requestAnimationFrame(layoutVideo);
      });
      layoutVideo();
    } else {
      if (Z.rot) toast(t('pip_rot'));
      await v.requestPictureInPicture();
    }
  } catch (e) { console.warn('pip', e); }
}
function exitPip() {
  try { documentPictureInPicture?.window?.close(); } catch {}
  if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
}
/** The app's floating window opened or closed: only the picture, no controls. */
window.onAppPip = on => {
  S.pip = !!on;
  document.documentElement.classList.toggle('pip', S.pip);
  if (S.pip) { setSheet(false); clearTimeout(S.ctlTimer); $('player').classList.add('hide-ctl'); }
  else showCtl();
  updateMode();
};
/** The floating window was swiped away: stop watching (the camera would keep sending to nobody). */
window.onAppPipClosed = () => { if (!$('viewer').hidden) stopAll(); };
$('video').addEventListener('resize', appPipShape);
$('bPip').onclick = enterPip;

// gestures.js (layoutVideo) loads after this file; the first callback can come before it
new ResizeObserver(() => { if (typeof layoutVideo === 'function') layoutVideo(); }).observe($('stage'));

// ====================== Camera settings ======================
let fdistDragging = false, fdistTimer = null;
function showFdist() {
  const v = +$('fdist').value;
  $('fdistVal').textContent = v < 0.02 ? t('fdist_far') : v > 0.98 ? t('fdist_nearest') : Math.round(v * 100) + '%';
  fillRange($('fdist'));
}
function setTog(id, on) { $(id).checked = !!on; }
let heatDragging = false;
function showHeatStart() {
  const v = +$('heatStart').value;
  $('heatStartVal').textContent = t('heat_start_val', { a: v, b: v + 3, c: v + 6 });
  fillRange($('heatStart'));
}
function showEv() {
  const e = $('ev'), st = +e.dataset.step || 0;
  const val = st ? e.value * st : +e.value;
  $('evVal').textContent = Math.abs(val) < 0.05 ? t('brightness_default') : (val > 0 ? '+' : '') + val.toFixed(1);
  fillRange(e);
}
/** Fills the dragged part of the slider with the accent color. */
function fillRange(el) { const p = (el.value - el.min) / ((el.max - el.min) || 1) * 100; el.style.setProperty('--p', p + '%'); }
function setSeg(id, v) { for (const b of $(id).querySelectorAll('button')) b.classList.toggle('on', b.dataset.v === String(v)); }
function onSeg(id, pick) {
  $(id).addEventListener('click', e => {
    const b = e.target.closest('button');
    if (!b || b.disabled) return;
    setSeg(id, b.dataset.v); pick(b.dataset.v);
  });
}
onSeg('selRes', v => sendCam({ t: 'cfg', res: +v }));
$('tHeat').onchange = () => { $('rowHeatStart').classList.toggle('off', !$('tHeat').checked); sendCam({ t: 'cfg', heatGuard: $('tHeat').checked }); };
$('heatStart').addEventListener('input', () => { heatDragging = true; showHeatStart(); });
$('heatStart').addEventListener('change', () => { heatDragging = false; sendCam({ t: 'cfg', heatStart: +$('heatStart').value }); });
onSeg('selFps', v => sendCam({ t: 'cfg', fps: +v }));
onSeg('selAspect', v => sendCam({ t: 'cfg', aspect: v }));
onSeg('selFocus', v => {
  $('rowFdist').hidden = v !== 'manual';
  if (v === 'point') toast(t('tip_point'));
  if (v === 'lock') toast(t('tip_lock'));
  sendCam({ t: 'cfg', focus: v, ...(v === 'manual' ? { fdist: +$('fdist').value } : {}) });
});
$('fdist').oninput = () => {
  fdistDragging = true; showFdist();
  clearTimeout(fdistTimer);
  fdistTimer = setTimeout(() => sendCam({ t: 'cfg', focus: 'manual', fdist: +$('fdist').value }), 120);
};
$('fdist').onchange = () => { fdistDragging = false; sendCam({ t: 'cfg', focus: 'manual', fdist: +$('fdist').value }); };
for (const [id, key] of [['tTorch', 'torch'], ['tNight', 'night'], ['tStab', 'stab']]) {
  $(id).onchange = () => sendCam({ t: 'cfg', [key]: $(id).checked });
}
$('ev').oninput = showEv;
$('ev').onchange = () => sendCam({ t: 'cfg', ev: +$('ev').value });

function setSheet(open) {
  if (!$('viewer').classList.contains('immersive')) open = false; // portrait: settings are already shown below
  $('panel').classList.toggle('open', open); $('scrim').classList.toggle('show', open);
  if (open) showCtl(false); else showCtl();
}
// ---------- Tabs: picture / timelapse / recording / camera phone ----------
function selectTab(name) {
  S.tab = name;
  for (const b of $('tabs').querySelectorAll('button')) { const on = b.dataset.tab === name; b.classList.toggle('on', on); b.setAttribute('aria-selected', on); }
  for (const p of document.querySelectorAll('#panel .pane')) p.hidden = p.dataset.pane !== name;
  // Portrait: when scrolled down, start the new tab right under the tabs (which stick under the video)
  const v = $('viewer');
  if (v.classList.contains('stacked')) { const top = $('panel').offsetTop - $('player').offsetHeight; if (v.scrollTop > top) v.scrollTop = top; }
  if (name === 'tl' && typeof tlRequestList === 'function') tlRequestList();
  if (name === 'rec' && typeof recRefresh === 'function') recRefresh();
}
$('tabs').addEventListener('click', e => { const b = e.target.closest('button[data-tab]'); if (b) selectTab(b.dataset.tab); });
/** Shows a tab: the sheet over the video in landscape, scrolled into view below the video in portrait. */
function openPanel(tab) {
  if (tab) selectTab(tab);
  if ($('viewer').classList.contains('immersive')) setSheet(true);
  else if ($('viewer').classList.contains('split')) $('panel').scrollTo({ top: 0, behavior: 'smooth' });
  else { const v = $('viewer'); v.scrollTo({ top: $('panel').offsetTop - $('player').offsetHeight, behavior: 'smooth' }); }
}
function openSettings() {
  if ($('viewer').classList.contains('immersive') && $('panel').classList.contains('open')) setSheet(false);
  else openPanel(['dev', 'tl', 'rec'].includes(S.tab) ? S.tab : 'pic');
}
selectTab('pic');
$('scrim').onclick = () => setSheet(false);
(() => { // drag the handle down to close
  let y0 = null;
  $('grabber').addEventListener('pointerdown', e => { y0 = e.clientY; $('grabber').setPointerCapture(e.pointerId); });
  $('grabber').addEventListener('pointerup', e => { if (y0 != null && e.clientY - y0 > 40) setSheet(false); y0 = null; });
})();

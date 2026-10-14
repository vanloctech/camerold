'use strict';
// ====================== Connection info ======================
const qualityName = h => h >= 1800 ? '4K' : h >= 1000 ? 'Full HD' : h >= 700 ? 'HD' : h ? t('q_low') : '…';
function startStats() {
  clearInterval(S.statsTimer);
  S.lastBytes = 0; S.lastTs = 0; S.shotTick = 0;
  S.statsTimer = setInterval(async () => {
    if (!S.pc) return;
    // Keep the cameras list picture fresh: soon after the video starts, then every minute
    if (++S.shotTick % 60 === 4) saveCamShot();
    const r = await S.pc.getStats();
    let inb, pair, local, remote;
    r.forEach(x => { if (x.type === 'inbound-rtp' && x.kind === 'video') inb = x; });
    r.forEach(x => { if (x.type === 'transport' && x.selectedCandidatePairId) pair = r.get(x.selectedCandidatePairId); });
    if (!pair) r.forEach(x => { if (x.type === 'candidate-pair' && x.nominated && x.state === 'succeeded') pair = x; });
    if (pair) { local = r.get(pair.localCandidateId); remote = r.get(pair.remoteCandidateId); }
    if (!inb) return;
    let kbps = 0;
    if (S.lastTs) kbps = (inb.bytesReceived - S.lastBytes) * 8 / (inb.timestamp - S.lastTs);
    S.lastBytes = inb.bytesReceived; S.lastTs = inb.timestamp;
    const relay = (local && local.candidateType === 'relay') || (remote && remote.candidateType === 'relay');
    const w = inb.frameWidth || 0, h = Math.min(inb.frameWidth || 0, inb.frameHeight || 0);
    const fps = Math.round(inb.framesPerSecond || 0), rtt = pair && pair.currentRoundTripTime ? Math.round(pair.currentRoundTripTime * 1000) : null;
    renderStats([
      [t('st_quality'), `${qualityName(h)} · ${w}×${inb.frameHeight || 0}`],
      [t('st_fps'), t('st_fps_val', { n: fps })],
      [t('st_speed'), `${(kbps / 1000).toFixed(1)} Mbps`],
      [t('st_route'), t(relay ? 'route_relay' : 'route_direct')],
      [t('st_latency'), rtt != null ? rtt + ' ms' : '…'],
      [t('st_audio'), t(S.audioAvail ? (S.muted ? 'audio_muted' : 'audio_on') : 'audio_none')],
      ...(S.viewers ? [[t('st_viewers'), S.viewers > 1 ? t('viewers_others', { n: S.viewers, o: S.viewers - 1 }) : t('viewers_only_you')]] : []),
      ...healthRows(),
    ]);
    if (!S.busyShown) setStatus(t('watching_q', { q: qualityName(h) }) + (relay ? t('via_relay') : '') +
      (S.viewers > 1 ? ' · ' + t('n_watching', { n: S.viewers }) : ''), 'ok');
  }, 1000);
}
/**
 * Two-column label/value grid (textContent – values never contain markup). It refreshes every second, so the cells
 * stay and only the values that changed are written.
 */
function renderStats(rows) {
  const box = $('stats'), cells = box.children, texts = rows.flat();
  while (cells.length < texts.length) box.appendChild(document.createElement('span'));
  while (cells.length > texts.length) box.lastChild.remove();
  texts.forEach((text, i) => setText(cells[i], text));
}
/** Info button: an overlay on the video in landscape; in portrait the info is in the "Camera phone" tab. */
function toggleStats() {
  if (!$('viewer').classList.contains('immersive')) { openPanel('dev'); return; }
  $('stats').hidden = !$('stats').hidden;
  showCtl();
}

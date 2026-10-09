'use strict';
// ====================== Network diagnostics ======================
const hasTurn = () => S.iceServers.length > DEFAULT_ICE.length || (S.camIce || []).length > 0;
/** Title + advice (i18n keys) for the problem screen when the video link can't be set up. */
function failReason() {
  if (hasTurn()) return { title: 'fail_t', text: 'fail_relay' };
  if (S.nat && S.nat.symmetric) return { title: 'fail_mobile_t', text: 'fail_mobile' };
  return { title: 'fail_t', text: 'fail_generic' };
}
function parseCand(c) {
  const p = c.split(' ');
  const o = { ip: p[4], port: +p[5], type: p[7] };
  const ri = p.indexOf('raddr'); if (ri > 0) { o.raddr = p[ri + 1]; o.rport = +p[p.indexOf('rport') + 1]; }
  return o;
}
function gather(config, ms) {
  return new Promise(async resolve => {
    const pc = new RTCPeerConnection(config), out = [];
    const done = () => { try { pc.close(); } catch {} resolve(out); };
    pc.onicecandidate = e => { if (!e.candidate) done(); else if (e.candidate.candidate) out.push({ ...parseCand(e.candidate.candidate), url: e.candidate.url }); };
    pc.createDataChannel('x');
    await pc.setLocalDescription(await pc.createOffer());
    setTimeout(done, ms);
  });
}
/** Network blocks direct connections (symmetric NAT): each STUN server sees a different public port. */
async function detectNat() {
  const c = await gather({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }, { urls: 'stun:stun.cloudflare.com:3478' }, { urls: 'stun:stun1.l.google.com:19302' }] }, 6000);
  const srflx = c.filter(x => x.type === 'srflx');
  const byBase = {};
  for (const x of srflx) (byBase[x.rport + '|' + (x.ip.includes(':') ? 6 : 4)] ||= new Set()).add(x.ip + ':' + x.port);
  const symmetric = Object.values(byBase).some(set => set.size > 1);
  return { symmetric, publicIps: [...new Set(srflx.map(x => x.ip))], ipv6: srflx.some(x => x.ip.includes(':')), stunOk: srflx.length > 0 };
}
async function testTurn(servers) {
  if (!servers.length) return null;
  const c = await gather({ iceServers: servers, iceTransportPolicy: 'relay' }, 8000);
  return c.some(x => x.type === 'relay');
}
$('netCheck').onclick = async () => {
  const r = $('netResult'); r.textContent = t('nc_running');
  const nat = await detectNat(); S.nat = nat;
  const turn = parseTurnUrls($('turnUrl').value);
  const relayOk = await testTurn(turn.length ? [{ urls: turn, username: $('turnUser').value.trim(), credential: $('turnPass').value.trim() }] : (S.savedCamIce || []));
  r.textContent = [
    t(nat.stunOk ? 'nc_internet_ok' : 'nc_internet_bad'),
    nat.symmetric ? t('nc_blocked') : nat.stunOk ? t('nc_direct_ok') : '',
    relayOk === null ? t('nc_no_relay') : t(relayOk ? 'nc_relay_ok' : 'nc_relay_bad'),
  ].filter(Boolean).join('\n');
};

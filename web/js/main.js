'use strict';
// ====================== Buttons ======================
$('go').onclick = () => start();
$('pass').addEventListener('keydown', e => { if (e.key === 'Enter') start(); });
$('bBack').onclick = () => { if (S.fs) setFullscreen(false); else stopAll(); };
$('bPanel').onclick = openSettings;
$('bStats').onclick = toggleStats;
$('bMute').onclick = toggleMute; $('qcAudio').onclick = toggleMute;
$('bSnap').onclick = takeSnapshot;
$('camTurnSend').onclick = sendCamTurn;
$('camTurnFill').onclick = fillCamTurn;
['camTurnUrl', 'camTurnUser', 'camTurnPass'].forEach(id => $(id).addEventListener('input', () => { S.turnEditing = true; }));
$('warnBadge').onclick = () => { flashWarning(); showCtl(); };
$('qcFront').onclick = () => setFrontCamera(!$('qcFront').classList.contains('on'));
$('tFront').onchange = () => setFrontCamera($('tFront').checked);
$('bRotate').onclick = rotateView; $('qcRotate').onclick = rotateView;
$('bFull').onclick = () => setFullscreen(!S.fs);
onSeg('selFit', v => setFill(v === 'fill'));
function toggleTorch() {
  $('tTorch').checked = !$('tTorch').checked; $('tTorch').onchange();
  for (const id of ['qcTorch', 'bTorch']) $(id).classList.toggle('on', $('tTorch').checked);
}
$('qcTorch').onclick = toggleTorch;
$('bFlip').onclick = () => setFrontCamera(!$('qcFront').classList.contains('on'));
// Light button: tap = on/off; hold = the camera flashes it until let go (repeated while held: if this page goes away,
// the camera stops by itself)
(() => {
  const b = $('bTorch');
  let timer = null, keep = null;
  const stopFlash = () => {
    if (!keep) return;
    clearInterval(keep); keep = null;
    b.classList.remove('strobe');
    sendCam({ t: 'strobe', on: false });
  };
  b.addEventListener('pointerdown', e => {
    if (e.button) return;
    b.setPointerCapture(e.pointerId);
    showCtl();
    timer = setTimeout(() => {
      timer = null;
      b.classList.add('strobe');
      sendCam({ t: 'strobe', on: true });
      keep = setInterval(() => { sendCam({ t: 'strobe', on: true }); showCtl(); }, 1000);
      navigator.vibrate?.(15);
      toast(t('strobe_on'), 1600);
    }, 400);
  });
  b.addEventListener('pointerup', () => { if (timer) { clearTimeout(timer); timer = null; toggleTorch(); } else stopFlash(); });
  b.addEventListener('pointercancel', () => { clearTimeout(timer); timer = null; stopFlash(); });
  b.addEventListener('contextmenu', e => e.preventDefault());
  b.addEventListener('click', e => { if (e.detail === 0) toggleTorch(); }); // keyboard
  addEventListener('blur', stopFlash);
})();
$('qcNight').onclick = () => { $('tNight').checked = !$('tNight').checked; $('tNight').onchange(); $('qcNight').classList.toggle('on', $('tNight').checked); };
// Interactions on the settings panel aren't treated as video pan/zoom
for (const ev of ['pointerdown', 'wheel']) $('panel').addEventListener(ev, e => e.stopPropagation());

async function requestWakeLock() {
  try { if ('wakeLock' in navigator) S.wakeLock = await navigator.wakeLock.request('screen'); } catch {}
}
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && S.sig) {
    requestWakeLock();
    if (S.pc && S.pc.connectionState !== 'connected' && S.pc.connectionState !== 'connecting') restartSession({ title: 'lost_t', text: 'lost' });
  }
});

// In the app the sign-in page is a screen like the others: back arrow + title in the top bar
if (IN_APP) {
  document.documentElement.classList.add('in-app');
  $('appBack').hidden = false;
  $('appBack').onclick = () => { if (window.CameroldApp?.close) CameroldApp.close(); else history.back(); };
  $('brand').hidden = true;
  $('openSettings').before($('loginTitle'));
}

// ====================== Start ======================
applyI18n();
$('statsHome').dataset.empty = t('dev_waiting');
initTheme();
initSettings();
loadSaved();
initCams();
showEv(); showFdist(); showHeatStart(); showZoomBar(); setAudioUi(); setFill(Z.fill);

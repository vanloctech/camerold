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
$('qcTorch').onclick = () => { $('tTorch').checked = !$('tTorch').checked; $('tTorch').onchange(); $('qcTorch').classList.toggle('on', $('tTorch').checked); };
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

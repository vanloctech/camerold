'use strict';
/*
 * Waiting / problem screen shown over the player.
 *  - loading: pulsing camera icon + 3 steps (Internet → find camera → connect)
 *  - problem: warning icon, short title, advice, "retrying…" line
 *  - busy:    the camera phone has another app using the camera
 * If video was already playing, a reconnect only shows a small pill over the blurred last frame.
 */
const OV_STEPS = ['net', 'find', 'link'];
const OV = { mode: null, stage: -1, hadVideo: false };

function renderOverlay({ mode, title, text = '', icon = 'i-camera', stage = -1 }) {
  OV.mode = mode;
  setReconnecting(false);
  const o = $('overlay');
  o.hidden = false;
  // No picture yet: the zoom bar and sound/lens buttons are meaningless, hide them (busy keeps the last frame)
  $('player').classList.toggle('waiting', mode !== 'busy');
  o.dataset.mode = mode;
  $('ovTitle').textContent = title;
  $('ovText').textContent = text;
  $('ovText').hidden = !text;
  $('ovIcon').setAttribute('href', '#' + icon);
  $('ovSteps').hidden = mode !== 'loading';
  $('ovRetry').hidden = mode !== 'problem';
  if (mode === 'loading') {
    OV.stage = stage;
    $('ovSteps').querySelectorAll('li').forEach((li, i) => {
      li.className = i < stage ? 'done' : i === stage ? 'active' : '';
    });
  }
}

/** stage: 'net' (reaching the internet), 'find' (looking for the camera), 'link' (setting up the video). */
function showLoading(stage) {
  const i = OV_STEPS.indexOf(stage);
  if (OV.hadVideo) { setReconnecting(true); return; }
  if (OV.mode === 'loading' && i < OV.stage) return; // never step backwards visually
  renderOverlay({ mode: 'loading', title: t('ov_connecting'), stage: i });
}

/** kind: 'problem' or 'busy'; titleKey/textKey are i18n keys. */
function showProblem(kind, titleKey, textKey) {
  if (OV.hadVideo && kind === 'problem') { setReconnecting(true); return; }
  renderOverlay({ mode: kind, title: t(titleKey), text: t(textKey), icon: kind === 'busy' ? 'i-camoff' : 'i-alert' });
}

function hideOverlay() {
  OV.mode = null; OV.stage = -1;
  $('overlay').hidden = true;
  $('player').classList.remove('waiting');
  setReconnecting(false);
}

/** Keep the last frame on screen, blurred, with a small "reconnecting" pill. */
function setReconnecting(on) {
  $('player').classList.toggle('reconnecting', on);
  $('reconnectPill').hidden = !on;
}

function resetOverlay() { OV.hadVideo = false; hideOverlay(); }

// The waiting screen goes away only when the first frame is actually drawn.
$('video').addEventListener('playing', () => {
  if (!S.pc || S.pc.connectionState !== 'connected') return;
  OV.hadVideo = true;
  if (OV.mode !== 'busy') hideOverlay();
});

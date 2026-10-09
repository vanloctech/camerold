'use strict';
// ====================== Settings page (theme, language, relay server, troubleshooting) ======================
function openSettingsPage() {
  $('settings').hidden = false; $('login').hidden = true;
  window.scrollTo(0, 0);
}
function closeSettingsPage() {
  saveViewerSettings();
  $('settings').hidden = true; $('login').hidden = false;
}
/** Settings are kept as soon as they change (not only when "Watch" is pressed). */
function saveViewerSettings() {
  savePatch({ codec: $('codec').value, turnUrl: $('turnUrl').value.trim(), turnUser: $('turnUser').value.trim(), turnPass: $('turnPass').value });
}

/** "Auto" + every language in I18N, each named in its own language and sorted by that name. */
function languageName(tag) {
  try {
    const n = new Intl.DisplayNames([tag], { type: 'language' }).of(tag);
    return n ? n.charAt(0).toLocaleUpperCase(tag) + n.slice(1) : tag;
  } catch { return tag; }
}
function fillLanguages() {
  let saved = '';
  try { saved = localStorage.getItem('camerold-lang') || ''; } catch {}
  const sel = $('selLang');
  sel.innerHTML = '';
  sel.add(new Option(t('lang_auto'), ''));
  Object.keys(I18N).map(tag => [tag, languageName(tag)])
    .sort((a, b) => a[1].localeCompare(b[1]))
    .forEach(([tag, name]) => sel.add(new Option(name, tag)));
  sel.value = saved in I18N ? saved : '';
}

function initSettings() {
  $('openSettings').onclick = openSettingsPage;
  $('closeSettings').onclick = closeSettingsPage;
  ['codec', 'turnUrl', 'turnUser', 'turnPass'].forEach(id => $(id).addEventListener('change', saveViewerSettings));
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('settings').hidden) closeSettingsPage(); });
  initBrokerUi();

  // In the app, theme and language follow the app's Settings screen
  $('lookGroup').hidden = IN_APP;
  if (IN_APP) return;
  setSeg('selTheme', savedTheme());
  onSeg('selTheme', v => {
    try { localStorage.setItem('camerold-theme', v); } catch {}
    applyTheme(v);
  });
  fillLanguages();
  $('selLang').onchange = () => {
    const v = $('selLang').value;
    try { v ? localStorage.setItem('camerold-lang', v) : localStorage.removeItem('camerold-lang'); } catch {}
    LANG = pickLang();
    applyI18n();
    $('selLang').options[0].textContent = t('lang_auto');
    loadBrokerUi();
  };
}

// ---------- Connection server (MQTT broker) ----------
function loadBrokerUi() {
  const s = savedSettings();
  const sel = $('brokerProvider');
  if (!sel.options.length) PROVIDERS.forEach(p => sel.add(new Option(p.name || t('provider_custom'), p.id)));
  else sel.options[sel.options.length - 1].textContent = t('provider_custom');
  $('brokerMode').value = s.brokerMode === 'own' ? 'own' : 'public';
  sel.value = providerById(s.brokerProvider).id;
  $('brokerHost').value = s.brokerHost || ''; $('brokerUser').value = s.brokerUser || ''; $('brokerPass').value = s.brokerPass || '';
  $('brokerBackup').checked = s.brokerBackup !== false;
  syncBrokerUi();
}
function syncBrokerUi() {
  const own = $('brokerMode').value === 'own', p = providerById($('brokerProvider').value);
  $('ownBroker').hidden = !own;
  $('brokerHost').placeholder = p.hint;
  $('brokerSignup').hidden = !p.signup;
  if (p.signup) $('brokerSignup').href = p.signup;
  $('brokerNote').textContent = t(own ? 'broker_note_own' : 'broker_note_public');
}
function saveBrokerUi() {
  savePatch({ brokerMode: $('brokerMode').value, brokerProvider: $('brokerProvider').value, brokerHost: $('brokerHost').value.trim(),
    brokerUser: $('brokerUser').value.trim(), brokerPass: $('brokerPass').value, brokerBackup: $('brokerBackup').checked });
  syncBrokerUi();
}
function brokerResult(key, ok) {
  const r = $('brokerResult'); r.hidden = false; r.className = 'row ' + (ok === true ? 'ok' : ok === false ? 'bad' : '');
  r.firstElementChild.textContent = t(key);
}
/** Real check like the app's: connect, subscribe, publish, and wait for our own message. */
function testBroker(url) {
  return new Promise(done => {
    const topic = 'camerold/test/' + randomId(12);
    let c, finished = false, timers = [];
    const finish = r => { if (finished) return; finished = true; timers.forEach(clearTimeout); c.stop(); done(r); };
    c = new MqttWs(url, {
      onUp: () => {
        c.subscribe(topic);
        timers.push(setTimeout(() => c.publish(topic, enc.encode('ping')), 500), setTimeout(() => finish('no_msg'), 6000));
      },
      onDown: () => {},
      onPublish: () => finish('ok'),
      onRefused: () => finish('refused'),
      onFailed: () => finish('unreachable'),
    });
    timers.push(setTimeout(() => finish('unreachable'), 15000));
    c.connect();
  });
}
async function runBrokerTest() {
  saveBrokerUi();
  const s = savedSettings();
  const url = ownBrokerUrl({ provider: s.brokerProvider, host: s.brokerHost, user: s.brokerUser, pass: s.brokerPass });
  if (!url) return brokerResult('broker_need_host', false);
  if (!isBrokerUrl(url)) return brokerResult('broker_bad_url', false);
  brokerResult('broker_testing');
  const r = await testBroker(url);
  brokerResult('broker_' + r, r === 'ok');
}
function initBrokerUi() {
  loadBrokerUi();
  ['brokerMode', 'brokerProvider', 'brokerHost', 'brokerUser', 'brokerPass', 'brokerBackup']
    .forEach(id => $(id).addEventListener('change', () => { $('brokerResult').hidden = true; saveBrokerUi(); }));
  $('brokerTest').onclick = runBrokerTest;
}

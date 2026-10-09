'use strict';
// ====================== Shared config (must match the Android app) ======================
const BROKERS = [
  'wss://broker.emqx.io:8084/mqtt',
  'wss://broker.hivemq.com:8884/mqtt',
  'wss://test.mosquitto.org:8081/mqtt',
  'wss://public:public@public.cloud.shiftr.io', // shiftr.io's public broker asks for this shared login
];
/**
 * The user's own broker (free accounts at HiveMQ Cloud, EMQX Cloud, CloudAMQP…). Same list and rules as
 * signaling/Brokers.kt in the app. url(): host from the provider's console -> WebSocket URL.
 */
const PROVIDERS = [
  { id: 'hivemq', name: 'HiveMQ Cloud', signup: 'https://console.hivemq.cloud/', hint: 'abc123.s1.eu.hivemq.cloud', url: h => `wss://${h}:8884/mqtt` },
  { id: 'emqx', name: 'EMQX Cloud', signup: 'https://www.emqx.com/en/cloud/serverless-mqtt', hint: 'abc123.ala.asia-southeast1.emqxsl.com', url: h => `wss://${h}:8084/mqtt` },
  // LavinMQ on CloudAMQP: MQTT over WebSocket at /ws/mqtt; on shared plans the vhost is the user name ("vhost:user")
  { id: 'cloudamqp', name: 'CloudAMQP (LavinMQ)', signup: 'https://customer.cloudamqp.com/signup', hint: 'abc-def.lmq.cloudamqp.com', url: h => `wss://${h}/ws/mqtt`,
    login: u => (!u || u.includes(':')) ? u : `${u}:${u}` },
  { id: 'custom', name: '', signup: null, hint: 'wss://mqtt.example.com:8084/mqtt', url: h => h },
];
const providerById = id => PROVIDERS.find(p => p.id === id) || PROVIDERS[0];
/** A broker is one URL with the login inside: wss://user:pass@host/path (MqttWs takes it apart). */
function withLogin(url, user, pass) {
  const u = new URL(url);
  if (user) { u.username = encodeURIComponent(user); if (pass) u.password = encodeURIComponent(pass); }
  return u.href;
}
function ownBrokerUrl(b) {
  const host = (b.host || '').trim().replace(/\/$/, '');
  if (!host) return null;
  const p = providerById(b.provider);
  try { return withLogin(host.includes('://') ? host : p.url(host), (p.login || (x => x))((b.user || '').trim()), b.pass || ''); }
  catch { return null; }
}
/** Only encrypted WebSocket brokers (what a browser can reach, and safe to accept from a QR code). */
const isBrokerUrl = s => { try { const u = new URL(s); return u.protocol === 'wss:' && !!u.hostname; } catch { return false; } };

/**
 * User-entered TURN URLs (comma/space separated). A plain "turn:host:port" is only tried over UDP by WebRTC, and many
 * networks (some Wi-Fi, most 4G carriers) block UDP to port 3478, so it's also offered over TCP. Same rule as Rtc.kt.
 */
function parseTurnUrls(raw) {
  const out = [];
  for (let u of (raw || '').split(/[,\s]+/).map(x => x.trim()).filter(Boolean)) {
    if (!/^(turns?|stun):/.test(u)) u = 'turn:' + u;
    if (u.startsWith('turn:') && !u.includes('transport=')) out.push(u + '?transport=udp', u + '?transport=tcp');
    else out.push(u);
  }
  return [...new Set(out)];
}

const DEFAULT_ICE = [
  { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] },
  { urls: 'stun:stun.cloudflare.com:3478' },
];
const ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789';
const enc = new TextEncoder(), dec = new TextDecoder();
const $ = id => document.getElementById(id);

function randomId(n) {
  const b = crypto.getRandomValues(new Uint8Array(n));
  return Array.from(b, x => ALPHABET[x % ALPHABET.length]).join('');
}
const normalizeRoom = s => s.trim().toLowerCase().replace(/\s/g, '');

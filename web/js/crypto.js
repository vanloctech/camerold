'use strict';
// ====================== Encryption - protocol v2 (matches SigCrypto.kt) ======================
// master = PBKDF2(pass, "camerold-v2:"+room, 600k) ; encKey = HMAC(master,"camerold enc") ;
// topic = "camerold/v2/" + hex(HMAC(master,"camerold topic"))[:32] ; packet = IV || AES-GCM(json, AAD = topic)
const PBKDF2_ITER = 600000;
const MAX_SKEW_MS = 15 * 60 * 1000;
async function deriveKeys(room, pass) {
  const base = await crypto.subtle.importKey('raw', enc.encode(pass), 'PBKDF2', false, ['deriveBits']);
  const master = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', salt: enc.encode('camerold-v2:' + room), iterations: PBKDF2_ITER, hash: 'SHA-256' }, base, 256);
  const mk = await crypto.subtle.importKey('raw', master, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const encRaw = await crypto.subtle.sign('HMAC', mk, enc.encode('camerold enc'));
  const topicRaw = new Uint8Array(await crypto.subtle.sign('HMAC', mk, enc.encode('camerold topic')));
  const key = await crypto.subtle.importKey('raw', encRaw, 'AES-GCM', false, ['encrypt', 'decrypt']);
  const topic = 'camerold/v2/' + Array.from(topicRaw, x => x.toString(16).padStart(2, '0')).join('').slice(0, 32);
  return { key, topic };
}
async function encrypt(key, text, aad) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: enc.encode(aad) }, key, enc.encode(text)));
  const out = new Uint8Array(12 + ct.length); out.set(iv); out.set(ct, 12); return out;
}
async function decrypt(key, data, aad) {
  try {
    const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: data.slice(0, 12), additionalData: enc.encode(aad) }, key, data.slice(12));
    return dec.decode(pt);
  } catch { return null; }
}

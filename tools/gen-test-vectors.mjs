// Generates test-vectors/crypto-v2.json from the reference algorithm (Node WebCrypto).
// The Kotlin (app) and JavaScript (web) implementations are both tested against this file,
// so they can never silently drift apart. Re-run only if the protocol version changes:
//   node tools/gen-test-vectors.mjs
import { webcrypto as crypto } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const enc = new TextEncoder();
const hex = b => Buffer.from(b).toString('hex');

async function vector(room, password, aad, plaintext, ivHex) {
  const base = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  const master = await crypto.subtle.deriveBits({ name: 'PBKDF2', salt: enc.encode('camerold-v2:' + room), iterations: 600000, hash: 'SHA-256' }, base, 256);
  const mk = await crypto.subtle.importKey('raw', master, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const encKey = new Uint8Array(await crypto.subtle.sign('HMAC', mk, enc.encode('camerold enc')));
  const topicRaw = new Uint8Array(await crypto.subtle.sign('HMAC', mk, enc.encode('camerold topic')));
  const key = await crypto.subtle.importKey('raw', encKey, 'AES-GCM', false, ['encrypt']);
  const iv = Buffer.from(ivHex, 'hex');
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: enc.encode(aad) }, key, enc.encode(plaintext)));
  return {
    room, password, aad, plaintext,
    topic: 'camerold/v2/' + hex(topicRaw).slice(0, 32),
    encKey: hex(encKey),
    packet: ivHex + hex(ct), // IV(12) || ciphertext || tag(16)
  };
}

const vectors = [
  await vector('k7m2pq9xha', 'Kq7vW2xZpR9mTn4sYb8H', 'camerold/v2/x/c', '{"t":"hello","id":"abcdefgh1234","ts":1700000000000}', '000102030405060708090a0b'),
  await vector('testcam42', 'mật khẩu Việt 123 ✓', 'camerold/v2/y/v/viewer1', '{"t":"offer","sdp":"v=0","x":"Xin chào"}', 'ffeeddccbbaa998877665544'),
];
writeFileSync(new URL('../test-vectors/crypto-v2.json', import.meta.url),
  JSON.stringify({ protocol: 'camerold-v2', pbkdf2Iterations: 600000, vectors }, null, 2) + '\n');
console.log('wrote', vectors.length, 'vectors');

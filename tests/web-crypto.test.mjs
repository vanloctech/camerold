// Checks the web viewer's crypto (web/js/crypto.js) against the shared test vectors.
// Run: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

/** Load the browser scripts into a sandbox, the same way index.html does (plain scripts, shared globals). */
function loadWebScripts(files) {
  const ctx = vm.createContext({ crypto: globalThis.crypto, TextEncoder, TextDecoder, console, document: undefined });
  for (const f of files) vm.runInContext(readFileSync(new URL(`../web/js/${f}`, import.meta.url), 'utf8'), ctx, { filename: f });
  return ctx;
}
const web = loadWebScripts(['config.js', 'crypto.js']);
const run = expr => vm.runInContext(expr, web);
const { vectors } = JSON.parse(readFileSync(new URL('../test-vectors/crypto-v2.json', import.meta.url), 'utf8'));
const fromHex = h => Uint8Array.from(Buffer.from(h, 'hex'));

for (const v of vectors) {
  test(`derives topic and decrypts vector for room ${v.room}`, async () => {
    web.v = v; web.packet = fromHex(v.packet);
    const out = await run(`(async () => { const k = await deriveKeys(v.room, v.password); return { topic: k.topic, pt: await decrypt(k.key, packet, v.aad) }; })()`);
    assert.equal(out.topic, v.topic);
    assert.equal(out.pt, v.plaintext);
  });

  test(`rejects tampering for room ${v.room}`, async () => {
    web.v = v; web.packet = fromHex(v.packet);
    const out = await run(`(async () => {
      const k = await deriveKeys(v.room, v.password);
      const wrongAad = await decrypt(k.key, packet, v.aad + 'x');
      const flipped = packet.slice(); flipped[flipped.length - 1] ^= 1;
      const tampered = await decrypt(k.key, flipped, v.aad);
      const wrongPass = await decrypt((await deriveKeys(v.room, v.password + '!')).key, packet, v.aad);
      return [wrongAad, tampered, wrongPass];
    })()`);
    assert.deepEqual([...out], [null, null, null]); // copy: arrays from the sandbox come from another realm
  });
}

test('encrypt/decrypt round trip with random IV', async () => {
  const pt = await run(`(async () => { const { key } = await deriveKeys('roundtrip1', 'password-123'); return decrypt(key, await encrypt(key, 'hello ✓', 'a/b'), 'a/b'); })()`);
  assert.equal(pt, 'hello ✓');
});

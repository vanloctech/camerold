'use strict';
// ====================== Minimal MQTT 3.1.1 over WebSocket ======================
class MqttWs {
  /** url: broker address; "wss://user:pass@host/path" logs in with user/pass (e.g. shiftr.io). */
  constructor(url, cb) {
    const u = new URL(url);
    this.user = u.username ? decodeURIComponent(u.username) : null;
    this.pass = u.password ? decodeURIComponent(u.password) : null;
    u.username = ''; u.password = '';
    this.url = u.href; this.cb = cb; this.connected = false; this.stopped = false; this.retry = 0; this.pid = 1;
  }
  connect() { this.stopped = false; this._open(); }
  stop() {
    this.stopped = true; clearInterval(this.pingT); clearTimeout(this.retryT);
    if (this.ws) { try { if (this.connected) this.ws.send(new Uint8Array([0xE0, 0])); this.ws.close(); } catch {} }
    this.ws = null; this.connected = false;
  }
  _open() {
    if (this.stopped) return;
    const ws = new WebSocket(this.url, ['mqtt']);
    ws.binaryType = 'arraybuffer';
    this.ws = ws; this.buf = new Uint8Array(0);
    const timeout = setTimeout(() => { if (this.ws === ws && !this.connected) ws.close(); }, 20000);
    ws.onopen = () => {
      const id = 'cmo_' + randomId(14);
      // keep-alive 60s, clean session (+ username/password flags when the broker needs a login)
      let flags = 2; const parts = [this._str(id)];
      if (this.user != null) { flags |= 0x80; parts.push(this._str(this.user)); }
      if (this.user != null && this.pass != null) { flags |= 0x40; parts.push(this._str(this.pass)); }
      ws.send(this._packet(0x10, this._cat(this._str('MQTT'), new Uint8Array([4, flags, 0, 60]), ...parts)));
    };
    ws.onmessage = e => { if (this.ws === ws) this._feed(new Uint8Array(e.data)); };
    ws.onclose = ws.onerror = () => {
      clearTimeout(timeout);
      if (this.ws !== ws) return;
      this.ws = null; clearInterval(this.pingT);
      const was = this.connected; this.connected = false;
      if (was) this.cb.onDown(this); else this.cb.onFailed?.(this); // couldn't connect at all (it keeps retrying)
      if (!this.stopped) {
        const d = Math.min(30, 2 ** Math.min(this.retry++, 5)) * 1000;
        this.retryT = setTimeout(() => this._open(), d);
      }
    };
  }
  subscribe(topic) {
    if (!this.connected) return;
    this.pid = this.pid >= 65535 ? 1 : this.pid + 1;
    this.ws.send(this._packet(0x82, this._cat(new Uint8Array([this.pid >> 8, this.pid & 255]), this._str(topic), new Uint8Array([0]))));
  }
  publish(topic, payload) { if (this.connected) this.ws.send(this._packet(0x30, this._cat(this._str(topic), payload))); }
  _feed(data) {
    this.buf = this._cat(this.buf, data);
    for (;;) {
      const b = this.buf; if (b.length < 2) return;
      let mult = 1, len = 0, i = 1, x;
      do { if (i >= b.length) return; x = b[i++]; len += (x & 127) * mult; mult *= 128; } while ((x & 128) && i < 5);
      if (b.length < i + len) return;
      const type = b[0] >> 4, flags = b[0] & 15, body = b.slice(i, i + len);
      this.buf = b.slice(i + len);
      if (type === 2) {
        if (body[1] === 0) {
          this.connected = true; this.retry = 0;
          this.pingT = setInterval(() => { try { this.ws && this.ws.send(new Uint8Array([0xC0, 0])); } catch {} }, 20000);
          this.cb.onUp(this);
        } else { // e.g. 4/5 = wrong user name or password
          const ws = this.ws;
          this.cb.onRefused?.(this, body[1]); // may stop() this client
          ws?.close(); return;
        }
      } else if (type === 3) {
        const tl = (body[0] << 8) | body[1];
        const topic = dec.decode(body.slice(2, 2 + tl));
        let idx = 2 + tl; if ((flags >> 1) & 3) idx += 2;
        this.cb.onPublish(this, topic, body.slice(idx));
      }
    }
  }
  _packet(h, body) {
    const lb = []; let x = body.length;
    do { let d = x % 128; x = Math.floor(x / 128); if (x > 0) d |= 128; lb.push(d); } while (x > 0);
    return this._cat(new Uint8Array([h, ...lb]), body);
  }
  _str(s) { const b = enc.encode(s); return this._cat(new Uint8Array([b.length >> 8, b.length & 255]), b); }
  _cat(...arrs) {
    const out = new Uint8Array(arrs.reduce((n, a) => n + a.length, 0)); let o = 0;
    for (const a of arrs) { out.set(a, o); o += a.length; } return out;
  }
}

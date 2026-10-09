'use strict';
// Connect to several brokers in parallel, publish to all, dedupe by id
class Signaling {
  constructor(key, subTopic, onMessage, onBrokers, brokers = BROKERS) {
    this.key = key; this.subTopic = subTopic; this.onMessage = onMessage; this.onBrokers = onBrokers;
    this.seen = new Map(); // id -> receive time (replay protection)
    this.clients = brokers.map(u => new MqttWs(u, this));
  }
  start() { this.clients.forEach(c => c.connect()); }
  stop() { this.clients.forEach(c => c.stop()); }
  get up() { return this.clients.filter(c => c.connected).length; }
  async send(topic, msg) {
    msg.id = randomId(12); msg.ts = Date.now(); this._remember(msg.id);
    const data = await encrypt(this.key, JSON.stringify(msg), topic);
    this.clients.forEach(c => c.publish(topic, data));
  }
  _remember(id) {
    if (typeof id !== 'string' || id.length < 8 || this.seen.has(id)) return false;
    const now = Date.now();
    this.seen.set(id, now);
    for (const [k, t] of this.seen) { if (now - t > 2 * MAX_SKEW_MS) this.seen.delete(k); else break; }
    return true;
  }
  onUp(c) { c.subscribe(this.subTopic); this.onBrokers(this.up, this.clients.length, c); }
  onDown() { this.onBrokers(this.up, this.clients.length); }
  async onPublish(c, topic, payload) {
    if (topic !== this.subTopic) return;
    const text = await decrypt(this.key, payload, topic);
    if (!text) return;
    let m; try { m = JSON.parse(text); } catch { return; }
    if (!(Math.abs(Date.now() - (+m.ts || 0)) <= MAX_SKEW_MS)) return; // stale / replayed message
    if (!this._remember(m.id)) return;
    this.onMessage(m);
  }
}

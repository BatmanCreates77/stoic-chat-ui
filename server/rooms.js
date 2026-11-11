/** In-memory rooms keyed by session code. Each room holds "meter" (phone) and "display" (browser) peers. */
export class Rooms {
  constructor() { this.rooms = new Map(); }
  get size() { return this.rooms.size; }

  room(code) {
    if (!this.rooms.has(code)) this.rooms.set(code, { meter: new Set(), display: new Set() });
    return this.rooms.get(code);
  }

  join(code, role, ws) {
    const r = this.room(code);
    r[role].add(ws);
    // A newcomer learns who is already here.
    const other = role === 'meter' ? 'display' : 'meter';
    if (r[other].size > 0) this.sendTo(ws, { type: 'peer_present', role: other });
  }

  leave(code, role, ws) {
    const r = this.rooms.get(code);
    if (!r) return;
    r[role].delete(ws);
    this.forward(code, role, { type: 'peer_left', role });
    if (r.meter.size === 0 && r.display.size === 0) this.rooms.delete(code);
  }

  /** Forward a message from `fromRole` to every peer of the other role. */
  forward(code, fromRole, msg) {
    const r = this.rooms.get(code);
    if (!r) return 0;
    const targets = fromRole === 'meter' ? r.display : r.meter;
    const text = JSON.stringify(msg);
    let n = 0;
    for (const ws of targets) { if (this.sendRaw(ws, text)) n++; }
    return n;
  }

  sendTo(ws, msg) { return this.sendRaw(ws, JSON.stringify(msg)); }

  sendRaw(ws, text) {
    try { if (ws.readyState === 1 || ws.readyState === undefined) { ws.send(text); return true; } } catch { /* dropped */ }
    return false;
  }
}

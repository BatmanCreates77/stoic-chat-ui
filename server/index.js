import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { Rooms } from './rooms.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const WEB_ROOT = path.resolve(__dirname, '..', 'web');
const PORT = Number(process.env.PORT || 8787);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

const rooms = new Rooms();

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/api/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size }));
    return;
  }
  let file = url.pathname === '/' ? '/index.html' : url.pathname;
  const full = path.normalize(path.join(WEB_ROOT, file));
  if (!full.startsWith(WEB_ROOT)) { res.writeHead(403); res.end(); return; }
  fs.readFile(full, (err, data) => {
    if (err) { res.writeHead(404); res.end('not found'); return; }
    res.writeHead(200, { 'content-type': MIME[path.extname(full)] || 'application/octet-stream', 'cache-control': 'no-cache' });
    res.end(data);
  });
});

const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname !== '/ws') { socket.destroy(); return; }
  const code = (url.searchParams.get('code') || '').toUpperCase();
  const role = url.searchParams.get('role');
  if (!/^[A-Z0-9]{4,8}$/.test(code) || !['meter', 'display'].includes(role)) { socket.destroy(); return; }
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.code = code; ws.role = role;
    rooms.join(code, role, ws);
    ws.on('message', (buf) => {
      let msg;
      try { msg = JSON.parse(buf.toString()); } catch { return; }
      if (!msg || typeof msg.type !== 'string') return;
      if (msg.type === 'hello') msg.role = role;
      rooms.forward(code, role, msg);
    });
    ws.on('close', () => rooms.leave(code, role, ws));
    ws.on('error', () => rooms.leave(code, role, ws));
  });
});

server.listen(PORT, () => {
  console.log(`ScreenMeter relay listening on http://0.0.0.0:${PORT}`);
});

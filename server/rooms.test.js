import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Rooms } from './rooms.js';

function fakeSocket() { const s = { sent: [], readyState: 1 }; s.send = (t) => s.sent.push(JSON.parse(t)); return s; }

test('messages flow from meter to display and back, never to self', () => {
  const rooms = new Rooms();
  const phone = fakeSocket();
  const browser = fakeSocket();
  rooms.join('ABCD', 'display', browser);
  rooms.join('ABCD', 'meter', phone);
  assert.deepEqual(phone.sent[0], { type: 'peer_present', role: 'display' });
  rooms.forward('ABCD', 'meter', { type: 'show', id: 'white' });
  assert.equal(browser.sent.at(-1).id, 'white');
  assert.equal(phone.sent.length, 1);
  rooms.forward('ABCD', 'display', { type: 'shown', id: 'white' });
  assert.equal(phone.sent.at(-1).type, 'shown');
});

test('leaving notifies the peer and empties the room', () => {
  const rooms = new Rooms();
  const phone = fakeSocket();
  const browser = fakeSocket();
  rooms.join('ZZ99', 'display', browser);
  rooms.join('ZZ99', 'meter', phone);
  rooms.leave('ZZ99', 'meter', phone);
  assert.deepEqual(browser.sent.at(-1), { type: 'peer_left', role: 'meter' });
  rooms.leave('ZZ99', 'display', browser);
  assert.equal(rooms.size, 0);
});

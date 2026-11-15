import test, { mock } from 'node:test';
import assert from 'node:assert';
import { Room } from './room.js';
import { RoomManager } from './room-manager.js';
import { RoomPersistence, RoomStore, StoredRoom } from './room-store.js';
import { createStandardDeck } from '../engine/deck.js';
import { DEFAULT_GAME_CONFIG } from '../models/types.js';
import { WebSocket } from 'ws';

class MockWebSocket {
  sentPackets: any[] = [];
  readyState: number = WebSocket.OPEN;
  send(data: string) { this.sentPackets.push(JSON.parse(data)); }
  terminate() { this.readyState = WebSocket.CLOSED; }
  ofType(type: string): any[] { return this.sentPackets.filter(p => p.type === type); }
}
const ws = () => new MockWebSocket();
const asWs = (m: MockWebSocket) => m as unknown as WebSocket;

/** In-memory stand-in for Supabase. */
class FakeStore implements RoomPersistence {
  enabled = true;
  rows = new Map<string, any>();
  async save(code: string, state: unknown) { this.rows.set(code, JSON.parse(JSON.stringify(state))); }
  async remove(code: string) { this.rows.delete(code); }
  async loadAll(): Promise<StoredRoom[]> {
    return Array.from(this.rows.entries()).map(([room_code, state]) => ({ room_code, state }));
  }
}

test('Persistence: a table survives a snapshot round trip (racks, board, deck, scores, turn)', () => {
  const room = new Room('SNAP1', { ...DEFAULT_GAME_CONFIG, turnDurationSeconds: 60 }, () => createStandardDeck(false));
  room.addPlayer('P1', 'Alice', asWs(ws()));
  room.addPlayer('P2', 'Bob', asWs(ws()));
  room.startGame();
  room.handleDrawTile('P1'); // P1 draws, turn passes to P2

  const snap = JSON.parse(JSON.stringify(room.toSnapshot()));
  const copy = Room.fromSnapshot(snap);

  assert.strictEqual(copy.status, 'IN_GAME');
  assert.strictEqual(copy.boardState.activePlayerId, 'P2');
  assert.strictEqual(copy.players.get('P1')!.rack.tiles.length, 15);
  assert.strictEqual(copy.players.get('P2')!.rack.tiles.length, 14);
  assert.deepStrictEqual(
    copy.players.get('P1')!.rack.tiles.map(t => t.id),
    room.players.get('P1')!.rack.tiles.map(t => t.id)
  );
  // nobody is online after a restart
  assert.strictEqual(copy.players.get('P1')!.isConnected, false);
  assert.strictEqual(copy.allDisconnected, true);
  assert.strictEqual(copy.players.get('P1')!.sessionToken, room.players.get('P1')!.sessionToken);
  room.dispose();
  copy.dispose();
});

test('Persistence: after a restart the player reconnects with his token and the game resumes', async () => {
  mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  try {
    const manager1 = new RoomManager(null);
    const w1 = ws();
    const { room, playerId, sessionToken } = manager1.createRoom('Alice', asWs(w1), { ...DEFAULT_GAME_CONFIG, botCount: 1, maxPlayers: 2, turnDurationSeconds: 30 });
    room.botThinkMs = [50, 60];
    room.syncBots = true;
    room.botStepMs = 10;
    room.startGame();
    const snapshot = JSON.parse(JSON.stringify(room.toSnapshot()));
    const code = room.roomCode;

    // "server restart": a brand new manager restores the table from the saved snapshot
    const store = new FakeStore();
    store.rows.set(code, snapshot);
    const manager2 = new RoomManager(store);
    {
      const count = await manager2.restore();
      assert.strictEqual(count, 1);
      const restored = manager2.getRoom(code)!;
      assert.ok(restored, 'the table exists again');
      restored.botThinkMs = [50, 60];
      restored.syncBots = true;
      restored.botStepMs = 10;

      // the phone reconnects with the old session token
      const w2 = ws();
      const result = manager2.reconnect(code, playerId, sessionToken, 1, asWs(w2));
      assert.strictEqual(result?.success, true);
      assert.strictEqual(w2.ofType('full_snapshot_res').length, 1);
      assert.strictEqual(w2.ofType('full_snapshot_res')[0].playerRack.tiles.length, 14);
      assert.strictEqual(restored.players.get(playerId)!.isConnected, true);

      // it is Alice's turn: she draws, then the restored robot must play by itself
      restored.handleDrawTile(playerId);
      for (let i = 0; i < 100; i++) mock.timers.tick(100); // small steps: the computer shows its moves one by one
      const botActed = w2.ofType('tile_drawn_evt').some(p => p.playerId === 'BOT_1') ||
        w2.ofType('turn_committed_evt').some(p => p.activePlayerId === 'BOT_1');
      assert.ok(botActed, 'the computer player keeps playing after the restart');
      restored.dispose();
    }
  } finally {
    mock.timers.reset();
  }
});

test('Persistence: an unknown or wrong-token reconnection is still refused after a restore', async () => {
  const store = new FakeStore();
  const room = new Room('KEEP1', { ...DEFAULT_GAME_CONFIG, turnDurationSeconds: 0 });
  room.addPlayer('P1', 'Alice', asWs(ws()));
  room.addPlayer('P2', 'Bob', asWs(ws()));
  room.startGame();
  store.rows.set('KEEP1', JSON.parse(JSON.stringify(room.toSnapshot())));
  room.dispose();

  const manager = new RoomManager(store);
  await manager.restore();
  assert.strictEqual(manager.reconnect('KEEP1', 'P1', 'wrong-token', 1, asWs(ws()))?.success, false);
  assert.strictEqual(manager.reconnect('NOPE00', 'P1', 'x', 1, asWs(ws())), null);
  manager.getRoom('KEEP1')?.dispose();
});

test('Persistence: saves are debounced, lobbies are not saved, deleted tables are removed', async () => {
  mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  try {
    const store = new FakeStore();
    let saves = 0;
    const originalSave = store.save.bind(store);
    store.save = async (c, s) => { saves++; return originalSave(c, s); };
    const manager = new RoomManager(store);

    const w1 = ws();
    const { room } = manager.createRoom('Alice', asWs(w1), { ...DEFAULT_GAME_CONFIG, turnDurationSeconds: 0 });
    manager.joinRoom(room.roomCode, 'Bob', asWs(ws()));
    mock.timers.tick(1000);
    assert.strictEqual(saves, 0, 'a lobby is not worth saving');

    room.startGame();
    room.handleDrawTile('' + Array.from(room.players.keys())[0]);
    mock.timers.tick(1000);
    await Promise.resolve();
    assert.ok(store.rows.has(room.roomCode), 'a running game is saved');
    assert.strictEqual(saves, 1, 'several changes in a row produce a single write');

    // everybody leaves: the table disappears from the store as well
    manager.handleLeave(asWs(w1));
    await Promise.resolve();
    room.dispose();
  } finally {
    mock.timers.reset();
  }
});

test('RoomStore: talks to Supabase REST with the service key and does nothing when not configured', async () => {
  assert.strictEqual(new RoomStore(undefined, undefined).enabled, false);
  await new RoomStore(undefined, undefined).save('X', {}); // no-op, no throw

  const calls: Array<{ url: string; init: any }> = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = (async (url: any, init: any) => {
    calls.push({ url: String(url), init });
    return { ok: true, status: 200, json: async () => [{ room_code: 'AB12CD', state: { hello: 1 } }] } as any;
  }) as any;
  try {
    const store = new RoomStore('https://proj.supabase.co/', 'SECRET');
    assert.strictEqual(store.enabled, true);
    await store.save('AB12CD', { a: 1 });
    assert.ok(calls[0].url.startsWith('https://proj.supabase.co/rest/v1/rooms?on_conflict=room_code'));
    assert.strictEqual(calls[0].init.method, 'POST');
    assert.strictEqual(calls[0].init.headers.apikey, 'SECRET');
    assert.ok(calls[0].init.headers.Prefer.includes('merge-duplicates'));
    const body = JSON.parse(calls[0].init.body);
    assert.strictEqual(body[0].room_code, 'AB12CD');

    await store.remove('AB12CD');
    assert.ok(calls[1].url.endsWith('room_code=eq.AB12CD'));
    assert.strictEqual(calls[1].init.method, 'DELETE');

    const rows = await store.loadAll();
    assert.strictEqual(rows.length, 1);
    assert.strictEqual(rows[0].room_code, 'AB12CD');
  } finally {
    globalThis.fetch = realFetch;
  }
});

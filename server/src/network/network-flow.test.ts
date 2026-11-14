import test from 'node:test';
import assert from 'node:assert';
import { Room } from './room.js';
import { WebSocket } from 'ws';

// Mock minimal WebSocket for testing
class MockWebSocket {
  sentPackets: any[] = [];
  readyState: number = WebSocket.OPEN;

  send(data: string) {
    this.sentPackets.push(JSON.parse(data));
  }
  terminate() {
    this.readyState = WebSocket.CLOSED;
  }
}

test('Room Network Flow: Create, Join, Deal 14 tiles and Start Game', () => {
  const room = new Room('TEST01');
  const ws1 = new MockWebSocket() as unknown as WebSocket;
  const ws2 = new MockWebSocket() as unknown as WebSocket;

  const { player: p1, sessionToken: s1 } = room.addPlayer('P1', 'Alice', ws1);
  const { player: p2, sessionToken: s2 } = room.addPlayer('P2', 'Bob', ws2);

  assert.strictEqual(p1.isHost, true);
  assert.strictEqual(p2.isHost, false);
  assert.strictEqual(room.players.size, 2);

  const started = room.startGame();
  assert.strictEqual(started, true);
  assert.strictEqual(room.status, 'IN_GAME');

  // Each player receives exactly 14 tiles
  assert.strictEqual(p1.rack.tiles.length, 14);
  assert.strictEqual(p2.rack.tiles.length, 14);

  // Total deck started with 106 tiles: 106 - 28 = 78
  assert.strictEqual(room.boardState.drawPileCount, 78);
  assert.strictEqual(room.boardState.versionId, 1);
  assert.strictEqual(room.boardState.activePlayerId, 'P1');
});

test('Room Network Flow: Grace Period on active player disconnect and reconnect', () => {
  const room = new Room('TEST02');
  const ws1 = new MockWebSocket() as unknown as WebSocket;
  const ws2 = new MockWebSocket() as unknown as WebSocket;

  const { player: p1, sessionToken: s1 } = room.addPlayer('P1', 'Alice', ws1);
  const { player: p2, sessionToken: s2 } = room.addPlayer('P2', 'Bob', ws2);
  room.startGame();

  // Active player P1 disconnects
  room.handleDisconnect('P1');
  assert.strictEqual(p1.isConnected, false);
  assert.strictEqual(room.boardState.isGracePeriod, true);
  assert.strictEqual(room.boardState.disconnectedPlayerId, 'P1');

  // Reconnect with valid session token and lastKnownVersionId
  const ws1New = new MockWebSocket() as unknown as WebSocket;
  const reconnected = room.reconnectPlayer('P1', s1, 1, ws1New);

  assert.strictEqual(reconnected, true);
  assert.strictEqual(p1.isConnected, true);
  assert.strictEqual(room.boardState.isGracePeriod, false);
  assert.strictEqual(room.boardState.disconnectedPlayerId, null);
});

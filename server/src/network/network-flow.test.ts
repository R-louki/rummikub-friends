import test, { mock } from 'node:test';
import assert from 'node:assert';
import { Room } from './room.js';
import { RoomManager } from './room-manager.js';
import { createStandardDeck } from '../engine/deck.js';
import { BoardValidator } from '../engine/board-validator.js';
import { DEFAULT_GAME_CONFIG, MeldSet } from '../models/types.js';
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
  ofType(type: string): any[] {
    return this.sentPackets.filter(p => p.type === type);
  }
}

const ws = () => new MockWebSocket();
const asWs = (m: MockWebSocket) => m as unknown as WebSocket;

/** Deterministic deck: P1 gets BLACK 1..13 + BLUE 1, P2 gets BLUE 2..13 + RED 1, RED 2. */
const orderedDeck = () => createStandardDeck(false);

test('Room Network Flow: Create, Join, Deal 14 tiles and Start Game', () => {
  const room = new Room('TEST01');
  const ws1 = ws();
  const ws2 = ws();

  const { player: p1 } = room.addPlayer('P1', 'Alice', asWs(ws1));
  const { player: p2 } = room.addPlayer('P2', 'Bob', asWs(ws2));

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
  room.dispose();
});

test('Room Network Flow: Grace Period on active player disconnect and reconnect (full snapshot)', () => {
  const room = new Room('TEST02');
  const ws1 = ws();
  const ws2 = ws();

  const { player: p1, sessionToken: s1 } = room.addPlayer('P1', 'Alice', asWs(ws1));
  room.addPlayer('P2', 'Bob', asWs(ws2));
  room.startGame();

  // Active player P1 disconnects
  room.handleDisconnect('P1');
  assert.strictEqual(p1.isConnected, false);
  assert.strictEqual(room.boardState.isGracePeriod, true);
  assert.strictEqual(room.boardState.disconnectedPlayerId, 'P1');

  // Reconnect with valid session token and lastKnownVersionId
  const ws1New = ws();
  const reconnected = room.reconnectPlayer('P1', s1, 1, asWs(ws1New));

  assert.strictEqual(reconnected, true);
  assert.strictEqual(p1.isConnected, true);
  assert.strictEqual(room.boardState.isGracePeriod, false);
  assert.strictEqual(room.boardState.disconnectedPlayerId, null);

  // The reconnecting player always gets the full state, including the rack
  const snapshots = ws1New.ofType('full_snapshot_res');
  assert.strictEqual(snapshots.length, 1);
  assert.strictEqual(snapshots[0].playerRack.tiles.length, 14);
  assert.strictEqual(snapshots[0].boardState.activePlayerId, 'P1');
  room.dispose();
});

test('Room: a second disconnect of the same player is ignored (single grace period, single rollback)', () => {
  mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  try {
    const room = new Room('TEST03', { ...DEFAULT_GAME_CONFIG, gracePeriodSeconds: 10 });
    const ws1 = ws();
    const ws2 = ws();
    room.addPlayer('P1', 'Alice', asWs(ws1));
    room.addPlayer('P2', 'Bob', asWs(ws2));
    room.startGame();

    room.handleDisconnect('P1');
    room.handleDisconnect('P1'); // e.g. heartbeat supervisor + socket close

    assert.strictEqual(ws2.ofType('player_disconnected_evt').length, 1);

    // Advance well past the grace period: exactly one rollback must happen.
    mock.timers.tick(60_000);

    const rollbacks = ws2.ofType('turn_rollback_evt');
    assert.strictEqual(rollbacks.length, 1);
    assert.strictEqual(room.boardState.activePlayerId, 'P2');
    assert.strictEqual(room.boardState.isGracePeriod, false);
    // P1 got the 3 penalty tiles exactly once
    assert.strictEqual(room.players.get('P1')!.rack.tiles.length, 17);
    room.dispose();
  } finally {
    mock.timers.reset();
  }
});

test('Room: leaving the lobby removes the player and hands the host role over', () => {
  const room = new Room('TEST04');
  const ws1 = ws();
  const ws2 = ws();
  room.addPlayer('P1', 'Alice', asWs(ws1));
  room.addPlayer('P2', 'Bob', asWs(ws2));

  room.handleDisconnect('P1');

  assert.strictEqual(room.players.size, 1);
  assert.strictEqual(room.players.get('P2')!.isHost, true);
  const left = ws2.ofType('player_left_evt');
  assert.strictEqual(left.length, 1);
  assert.strictEqual(left[0].playerId, 'P1');
  assert.strictEqual(left[0].newHostId, 'P2');
  assert.strictEqual(room.isEmpty, false);

  room.handleLeave('P2');
  assert.strictEqual(room.isEmpty, true);
});

test('Room: valid first meld is committed and the turn passes to the next player', () => {
  const room = new Room('TEST05', DEFAULT_GAME_CONFIG, orderedDeck);
  const ws1 = ws();
  const ws2 = ws();
  const { player: p1 } = room.addPlayer('P1', 'Alice', asWs(ws1));
  room.addPlayer('P2', 'Bob', asWs(ws2));
  room.startGame();

  // P1 holds BLACK 1..13 and BLUE 1. Play BLACK 11-12-13 (36 pts).
  const played = ['BLACK_11_1', 'BLACK_12_1', 'BLACK_13_1'];
  const meld: MeldSet = {
    id: 'm1',
    gridRow: 0,
    gridCol: 0,
    tiles: p1.rack.tiles.filter(t => played.includes(t.id))
  };
  const remaining = p1.rack.tiles.filter(t => !played.includes(t.id)).map(t => t.id);

  room.handleEndTurn('P1', [meld], remaining);

  assert.strictEqual(room.boardState.melds.length, 1);
  assert.strictEqual(room.boardState.activePlayerId, 'P2');
  assert.strictEqual(p1.rack.tiles.length, 11);
  assert.strictEqual(p1.rack.hasMadeInitialMeld, true);

  const committed = ws2.ofType('turn_committed_evt');
  assert.strictEqual(committed.length, 1);
  assert.strictEqual(committed[0].nextPlayerId, 'P2');
  assert.strictEqual(committed[0].boardState.activePlayerId, 'P2');
  // The player who played gets their updated rack
  const mine = ws1.ofType('turn_committed_evt');
  assert.strictEqual(mine.length, 1);
  assert.strictEqual(mine[0].updatedRack.tiles.length, 11);
  room.dispose();
});

test('Room: a proposal with a tampered or foreign tile is refused without penalty', () => {
  const room = new Room('TEST06', DEFAULT_GAME_CONFIG, orderedDeck);
  const ws1 = ws();
  const ws2 = ws();
  const { player: p1 } = room.addPlayer('P1', 'Alice', asWs(ws1));
  const { player: p2 } = room.addPlayer('P2', 'Bob', asWs(ws2));
  room.startGame();

  // P1 tries to play one of Bob's tiles (BLUE 5) together with two of their own.
  const stolen = p2.rack.tiles.find(t => t.id === 'BLUE_5_1')!;
  const meld: MeldSet = {
    id: 'm1', gridRow: 0, gridCol: 0,
    tiles: [
      p1.rack.tiles.find(t => t.id === 'BLACK_5_1')!,
      stolen,
      { id: 'BLACK_7_1', color: 'BLACK', number: 5, isJoker: false } // tampered number
    ]
  };
  const remaining = p1.rack.tiles.filter(t => t.id !== 'BLACK_5_1' && t.id !== 'BLACK_7_1').map(t => t.id);

  room.handleEndTurn('P1', [meld], remaining);

  const errors = ws1.ofType('error');
  assert.strictEqual(errors.length, 1);
  assert.strictEqual(errors[0].code, 'INVALID_PROPOSAL');
  // Nothing changed: still P1's turn, no penalty, board empty
  assert.strictEqual(room.boardState.activePlayerId, 'P1');
  assert.strictEqual(room.boardState.melds.length, 0);
  assert.strictEqual(p1.rack.tiles.length, 14);
  room.dispose();
});

test('Room: ending a turn without playing a tile does not pass the turn', () => {
  const room = new Room('TEST07', DEFAULT_GAME_CONFIG, orderedDeck);
  const ws1 = ws();
  const ws2 = ws();
  const { player: p1 } = room.addPlayer('P1', 'Alice', asWs(ws1));
  room.addPlayer('P2', 'Bob', asWs(ws2));
  room.startGame();

  room.handleEndTurn('P1', [], p1.rack.tiles.map(t => t.id));

  assert.strictEqual(ws1.ofType('error')[0].code, 'NO_TILES_PLAYED');
  assert.strictEqual(room.boardState.activePlayerId, 'P1');
  assert.strictEqual(ws2.ofType('turn_rollback_evt').length, 0);
  room.dispose();
});

test('Room: after game over the host can start a new round', () => {
  const room2 = new Room('TEST09', DEFAULT_GAME_CONFIG, orderedDeck);
  const a = ws();
  const b = ws();
  const { player: alice } = room2.addPlayer('P1', 'Alice', asWs(a));
  room2.addPlayer('P2', 'Bob', asWs(b));
  room2.startGame();
  const run: MeldSet = {
    id: 'run', gridRow: 0, gridCol: 0,
    tiles: alice.rack.tiles.filter(t => t.color === 'BLACK')
  };
  room2.handleEndTurn('P1', [run], ['BLUE_1_1']);
  assert.strictEqual(room2.status, 'IN_GAME');
  assert.strictEqual(alice.rack.tiles.length, 1);
  assert.strictEqual(room2.boardState.activePlayerId, 'P2');

  // Reach FINISHED by forfeit: Bob leaves mid-game.
  room2.handleLeave('P2');
  assert.strictEqual(room2.status, 'FINISHED');
  assert.strictEqual(a.ofType('game_over_evt').length, 1);
  assert.strictEqual(a.ofType('game_over_evt')[0].winnerId, 'P1');

  // A new player joins between rounds and the host restarts
  assert.strictEqual(room2.isJoinable, true);
  const c = ws();
  room2.addPlayer('P3', 'Chloé', asWs(c));
  assert.strictEqual(room2.startGame(), true);
  assert.strictEqual(room2.status, 'IN_GAME');
  assert.strictEqual(room2.boardState.melds.length, 0);
  assert.strictEqual(c.ofType('game_started_evt').length, 1);
  room2.dispose();
});

test('RoomManager: heartbeat timeout disconnects once and empty rooms are dropped', () => {
  mock.timers.enable({ apis: ['setInterval', 'setTimeout', 'Date'] });
  try {
    const manager = new RoomManager();
    const ws1 = ws();
    const ws2 = ws();
    const { room } = manager.createRoom('Alice', asWs(ws1));
    manager.joinRoom(room.roomCode, 'Bob', asWs(ws2));
    assert.strictEqual(manager.roomCount, 1);

    // Both players stop pinging: after the timeout they are disconnected and the lobby room is dropped.
    mock.timers.tick(30_000);
    assert.strictEqual(manager.roomCount, 0);

    // A terminated socket's late 'close' event must be a no-op
    manager.handleSocketDisconnect(asWs(ws1));
    assert.strictEqual(manager.roomCount, 0);
  } finally {
    mock.timers.reset();
  }
});

test('Room: scores accumulate over several rounds', () => {
  // Alice's 14 tiles: BLACK 1..13 + a Joker, playable as two runs of 7 -> she empties her rack at once.
  const deckFactory = () => {
    const all = createStandardDeck(false);
    const mine = all.filter(t => (t.color === 'BLACK' && t.id.endsWith('_1')) || t.id === 'JOKER_1');
    const rest = all.filter(t => !mine.includes(t));
    return [...mine, ...rest];
  };
  const room = new Room('TEST10', DEFAULT_GAME_CONFIG, deckFactory);
  const a = ws();
  const b = ws();
  const { player: alice } = room.addPlayer('P1', 'Alice', asWs(a));
  const { player: bob } = room.addPlayer('P2', 'Bob', asWs(b));

  const playRound = () => {
    room.startGame();
    const blacks = alice.rack.tiles.filter(t => t.color === 'BLACK').sort((x, y) => x.number - y.number);
    const joker = alice.rack.tiles.find(t => t.isJoker)!;
    const melds: MeldSet[] = [
      { id: 'r1', gridRow: 0, gridCol: 0, tiles: blacks.slice(0, 7) },
      { id: 'r2', gridRow: 1, gridCol: 0, tiles: [...blacks.slice(7), joker] }
    ];
    room.handleEndTurn('P1', melds, []);
  };

  playRound();
  assert.strictEqual(room.status, 'FINISHED');
  const over1 = a.ofType('game_over_evt')[0];
  assert.strictEqual(over1.roundNumber, 1);
  const bobPenalty1 = bob.rack.tiles.reduce((s, t) => s + (t.isJoker ? 30 : t.number), 0);
  assert.strictEqual(over1.scores.find((s: any) => s.playerId === 'P1').totalScore, bobPenalty1);
  assert.strictEqual(over1.scores.find((s: any) => s.playerId === 'P2').totalScore, -bobPenalty1);

  playRound();
  const over2 = a.ofType('game_over_evt')[1];
  assert.strictEqual(over2.roundNumber, 2);
  const bobPenalty2 = bob.rack.tiles.reduce((s, t) => s + (t.isJoker ? 30 : t.number), 0);
  assert.strictEqual(over2.scores.find((s: any) => s.playerId === 'P1').totalScore, bobPenalty1 + bobPenalty2);
  assert.strictEqual(room.getPlayerSummaries().find(p => p.id === 'P2').totalScore, -(bobPenalty1 + bobPenalty2));
  room.dispose();
});

test('Bots: computer players play whole games by themselves without breaking the rules', () => {
  mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  try {
    for (let game = 0; game < 6; game++) {
      const room = new Room('BOT' + game, { ...DEFAULT_GAME_CONFIG, initialMeldThreshold: game % 2 === 0 ? 30 : 0, turnDurationSeconds: 60 });
      const human = ws();
      room.addPlayer('H1', 'Humain', asWs(human));
      room.addBots(3);
      room.botThinkMs = [100, 200];
      assert.strictEqual(room.players.size, 4);
      room.startGame();

      // The human never acts: the 60 s turn timer penalises them, the bots keep playing.
      let seconds = 0;
      while (room.status === 'IN_GAME' && seconds < 3000) {
        mock.timers.tick(1000);
        seconds++;
      }

      // Tile conservation: every tile is in exactly one place
      const everything = [
        ...Array.from(room.players.values()).flatMap(p => p.rack.tiles.map(t => t.id)),
        ...room.boardState.melds.flatMap(m => m.tiles.map(t => t.id))
      ];
      assert.strictEqual(new Set(everything).size, everything.length, 'no tile may be duplicated');
      assert.ok(everything.length <= 106);

      // Whatever is on the table is valid
      const validation = BoardValidatorFor(room.boardState.melds);
      assert.strictEqual(validation, true, 'the table must only hold valid melds');

      const botsPlayed = room.boardState.melds.length > 0 || room.status === 'FINISHED';
      assert.ok(botsPlayed, 'bots must have laid down melds or finished the game');
      room.dispose();
    }
  } finally {
    mock.timers.reset();
  }
});

function BoardValidatorFor(melds: MeldSet[]): boolean {
  return melds.every(m => BoardValidator.validateMeld(m).isValid);
}

test('Bots: every bot-only game ends (empty pile and no move = blocked game, lowest total wins)', () => {
  mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  try {
    for (let game = 0; game < 6; game++) {
      const room = new Room('END' + game, { ...DEFAULT_GAME_CONFIG, turnDurationSeconds: 0 });
      room.addBots(3);
      room.botThinkMs = [20, 30];
      room.startGame();
      let ticks = 0;
      while (room.status === 'IN_GAME' && ticks < 20000) {
        mock.timers.tick(50);
        ticks++;
      }
      assert.strictEqual(room.status, 'FINISHED', 'the game must end by itself');
      room.dispose();
    }
  } finally {
    mock.timers.reset();
  }
});

test('Room: with a first-meld threshold of 0 players may touch existing melds right away', () => {
  const room = new Room('FREE1', { ...DEFAULT_GAME_CONFIG, initialMeldThreshold: 0, turnDurationSeconds: 0 }, orderedDeck);
  const w1 = ws();
  const { player: p1 } = room.addPlayer('P1', 'Alice', asWs(w1));
  room.addPlayer('P2', 'Bob', asWs(ws()));
  room.startGame();

  const t = (id: string, color: any, n: number) => ({ id, color, number: n, isJoker: false });
  const eights: MeldSet = { id: 'g8', gridRow: 0, gridCol: 0, tiles: [t('Y8', 'YELLOW', 8), t('B8', 'BLUE', 8), t('K8', 'BLACK', 8)] };
  const red8 = t('R8', 'RED', 8);
  const snap = (room as any).turnSnapshot;
  room.boardState.melds = [eights];
  snap.board.melds = JSON.parse(JSON.stringify([eights]));
  p1.rack.tiles = [red8, t('B3', 'BLUE', 3)];
  snap.rack = JSON.parse(JSON.stringify(p1.rack));

  room.handleEndTurn('P1', [{ ...eights, tiles: [eights.tiles[0], eights.tiles[1], red8, eights.tiles[2]] }], ['B3']);

  assert.strictEqual(w1.ofType('turn_rollback_evt').length, 0);
  assert.strictEqual(room.boardState.melds[0].tiles.length, 4);
  assert.strictEqual(room.boardState.activePlayerId, 'P2');
  room.dispose();
});

import test from 'node:test';
import assert from 'node:assert';
import { WebSocket } from 'ws';
import { RoomManager } from './room-manager.js';
import { Account, AccountBackend, AccountSessions, GameRecord, SupabaseAccounts } from './accounts.js';
import { createPacketHandler } from './packet-handler.js';
import { createStandardDeck } from '../engine/deck.js';
import { DEFAULT_GAME_CONFIG, MeldSet } from '../models/types.js';

class MockWebSocket {
  sentPackets: any[] = [];
  readyState: number = WebSocket.OPEN;
  send(data: string) { this.sentPackets.push(JSON.parse(data)); }
  terminate() { this.readyState = WebSocket.CLOSED; }
  ofType(type: string): any[] { return this.sentPackets.filter(p => p.type === type); }
  last(type: string): any { return this.ofType(type).slice(-1)[0]; }
}
const ws = () => new MockWebSocket();
const asWs = (m: MockWebSocket) => m as unknown as WebSocket;

class FakeBackend implements AccountBackend {
  enabled = true;
  tokens = new Map<string, Account>();
  friends = new Map<string, string[]>();
  saved: GameRecord[] = [];
  touched: string[] = [];
  async touch(userId: string) { this.touched.push(userId); }
  async verify(token: string) { return this.tokens.get(token) ?? null; }
  async friendIds(userId: string) { return this.friends.get(userId) ?? []; }
  async saveGame(r: GameRecord) { this.saved.push(r); }
}

function setup(requireAuth = false) {
  const backend = new FakeBackend();
  backend.tokens.set('tok-sami', { userId: 'u-sami', pseudo: 'Sami' });
  backend.tokens.set('tok-yas', { userId: 'u-yas', pseudo: 'Yasmine' });
  backend.tokens.set('tok-new', { userId: 'u-new', pseudo: null });
  backend.tokens.set('tok-stranger', { userId: 'u-str', pseudo: 'Inconnu' });
  backend.friends.set('u-sami', ['u-yas']);
  backend.friends.set('u-yas', ['u-sami']);
  const sessions = new AccountSessions();
  const manager = new RoomManager(null, backend);
  const handler = createPacketHandler(manager, backend, sessions, { requireAuth });
  const tick = () => new Promise(r => setTimeout(r, 5));
  return { backend, sessions, manager, handler, tick };
}

const cfg = { ...DEFAULT_GAME_CONFIG, turnDurationSeconds: 0 };

test('Accounts: a valid token logs the player in, a bad one is refused', async () => {
  const { handler, tick, sessions } = setup();
  const a = ws();
  handler.handle(asWs(a), { type: 'auth_req', accessToken: 'tok-sami' });
  await tick();
  assert.deepStrictEqual(
    { ok: a.last('auth_res').ok, pseudo: a.last('auth_res').pseudo, needsPseudo: a.last('auth_res').needsPseudo },
    { ok: true, pseudo: 'Sami', needsPseudo: false }
  );
  assert.strictEqual(sessions.isOnline('u-sami'), true);

  const b = ws();
  handler.handle(asWs(b), { type: 'auth_req', accessToken: 'forged' });
  await tick();
  assert.strictEqual(b.last('auth_res').ok, false);
  assert.strictEqual(sessions.onlineCount, 1);

  handler.onClose(asWs(a));
  assert.strictEqual(sessions.isOnline('u-sami'), false, 'closing the socket takes the player offline');
});

test('Accounts: the table name is the account pseudo, whatever the phone typed', async () => {
  const { handler, tick, manager, backend } = setup();
  const a = ws();
  handler.handle(asWs(a), { type: 'auth_req', accessToken: 'tok-sami' });
  await tick();
  handler.handle(asWs(a), { type: 'create_room_req', hostName: 'Faux Nom', config: cfg });
  const joined = a.last('room_joined_res');
  assert.strictEqual(joined.players[0].name, 'Sami');
  const room = manager.getRoom(joined.roomCode)!;
  assert.strictEqual(room.players.get(joined.playerId)!.userId, 'u-sami');

  const b = ws();
  handler.handle(asWs(b), { type: 'auth_req', accessToken: 'tok-yas' });
  await tick();
  handler.handle(asWs(b), { type: 'join_room_req', roomCode: joined.roomCode, playerName: 'Autre' });
  assert.deepStrictEqual(b.last('room_joined_res').players.map((p: any) => p.name), ['Sami', 'Yasmine']);
  assert.ok(backend.enabled);
  room.dispose();
});

test('Accounts: with REQUIRE_AUTH, anonymous players and players without pseudo cannot play', async () => {
  const { handler, tick } = setup(true);
  const anon = ws();
  handler.handle(asWs(anon), { type: 'create_room_req', hostName: 'Anon', config: cfg });
  assert.strictEqual(anon.last('error').code, 'AUTH_REQUIRED');
  assert.strictEqual(anon.ofType('room_joined_res').length, 0);

  const fresh = ws();
  handler.handle(asWs(fresh), { type: 'auth_req', accessToken: 'tok-new' });
  await tick();
  assert.strictEqual(fresh.last('auth_res').needsPseudo, true);
  handler.handle(asWs(fresh), { type: 'create_room_req', hostName: 'x', config: cfg });
  assert.strictEqual(fresh.last('error').code, 'PSEUDO_REQUIRED');

  const ok = ws();
  handler.handle(asWs(ok), { type: 'auth_req', accessToken: 'tok-sami' });
  await tick();
  handler.handle(asWs(ok), { type: 'create_room_req', hostName: 'x', config: cfg });
  assert.strictEqual(ok.ofType('room_joined_res').length, 1);
});

test('Accounts: without REQUIRE_AUTH anonymous players still work (older app versions)', () => {
  const { handler } = setup(false);
  const anon = ws();
  handler.handle(asWs(anon), { type: 'create_room_req', hostName: 'Ancien', config: cfg });
  assert.strictEqual(anon.last('room_joined_res').players[0].name, 'Ancien');
});

test('Friends: presence shows only online friends', async () => {
  const { handler, tick } = setup();
  const sami = ws();
  const yas = ws();
  const stranger = ws();
  for (const [sock, token] of [[sami, 'tok-sami'], [stranger, 'tok-stranger']] as const) {
    handler.handle(asWs(sock), { type: 'auth_req', accessToken: token });
  }
  await tick();
  handler.handle(asWs(sami), { type: 'friends_presence_req' });
  await tick();
  assert.deepStrictEqual(sami.last('friends_presence_res').online, [], 'Yasmine is not online yet');

  handler.handle(asWs(yas), { type: 'auth_req', accessToken: 'tok-yas' });
  await tick();
  handler.handle(asWs(sami), { type: 'friends_presence_req' });
  await tick();
  assert.deepStrictEqual(sami.last('friends_presence_res').online, ['u-yas'], 'the stranger is never listed');
});

test('Friends: a friend receives the invitation with the table code; non-friends cannot be invited', async () => {
  const { handler, tick, manager } = setup();
  const sami = ws();
  const yas = ws();
  const stranger = ws();
  handler.handle(asWs(sami), { type: 'auth_req', accessToken: 'tok-sami' });
  handler.handle(asWs(yas), { type: 'auth_req', accessToken: 'tok-yas' });
  handler.handle(asWs(stranger), { type: 'auth_req', accessToken: 'tok-stranger' });
  await tick();

  // no table yet
  handler.handle(asWs(sami), { type: 'invite_friend_req', friendUserId: 'u-yas' });
  await tick();
  assert.strictEqual(sami.last('error').code, 'INVITE_FAILED');

  handler.handle(asWs(sami), { type: 'create_room_req', config: cfg });
  const code = sami.last('room_joined_res').roomCode;

  handler.handle(asWs(sami), { type: 'invite_friend_req', friendUserId: 'u-yas' });
  await tick();
  assert.strictEqual(yas.last('room_invite_evt').roomCode, code);
  assert.strictEqual(yas.last('room_invite_evt').fromPseudo, 'Sami');
  assert.strictEqual(sami.last('invite_res').delivered, true);

  handler.handle(asWs(sami), { type: 'invite_friend_req', friendUserId: 'u-str' });
  await tick();
  assert.strictEqual(stranger.ofType('room_invite_evt').length, 0, 'a non-friend gets nothing');
  assert.strictEqual(sami.last('error').code, 'INVITE_FAILED');

  // the invited friend joins with the code carried by the invitation
  handler.handle(asWs(yas), { type: 'join_room_req', roomCode: code });
  assert.strictEqual(yas.last('room_joined_res').players.length, 2);
  manager.getRoom(code)?.dispose();
});

test('Friends: an offline friend is reported as not delivered', async () => {
  const { handler, tick, manager } = setup();
  const sami = ws();
  handler.handle(asWs(sami), { type: 'auth_req', accessToken: 'tok-sami' });
  await tick();
  handler.handle(asWs(sami), { type: 'create_room_req', config: cfg });
  handler.handle(asWs(sami), { type: 'invite_friend_req', friendUserId: 'u-yas' });
  await tick();
  assert.strictEqual(sami.last('invite_res').delivered, false);
  assert.strictEqual(sami.last('invite_res').queued, true);
  manager.getRoom(sami.last('room_joined_res').roomCode)?.dispose();
});

test('Friends: an invite sent while offline is queued and delivered as soon as the friend logs back in', async () => {
  const { handler, tick, manager } = setup();
  const sami = ws();
  const yas = ws();
  handler.handle(asWs(sami), { type: 'auth_req', accessToken: 'tok-sami' });
  await tick();
  handler.handle(asWs(sami), { type: 'create_room_req', config: cfg });
  const code = sami.last('room_joined_res').roomCode;

  // Yasmine's app is closed when the invite is sent: nothing to deliver to yet.
  handler.handle(asWs(sami), { type: 'invite_friend_req', friendUserId: 'u-yas' });
  await tick();
  assert.strictEqual(sami.last('invite_res').queued, true);

  // She opens the app a bit later: the queued invite reaches her right on login.
  handler.handle(asWs(yas), { type: 'auth_req', accessToken: 'tok-yas' });
  await tick();
  assert.strictEqual(yas.last('room_invite_evt').roomCode, code);
  assert.strictEqual(yas.last('room_invite_evt').fromPseudo, 'Sami');

  manager.getRoom(code)?.dispose();
});

test('Friends: a queued invite is dropped if the table is gone by the time the friend logs back in', async () => {
  const { handler, tick, manager } = setup();
  const sami = ws();
  const yas = ws();
  handler.handle(asWs(sami), { type: 'auth_req', accessToken: 'tok-sami' });
  await tick();
  handler.handle(asWs(sami), { type: 'create_room_req', config: cfg });
  const code = sami.last('room_joined_res').roomCode;
  handler.handle(asWs(sami), { type: 'invite_friend_req', friendUserId: 'u-yas' });
  await tick();

  manager.closeRoom(code);

  handler.handle(asWs(yas), { type: 'auth_req', accessToken: 'tok-yas' });
  await tick();
  assert.strictEqual(yas.ofType('room_invite_evt').length, 0);
});

test('History: a finished round is saved with the accounts of the human players only', async () => {
  const { handler, tick, manager, backend } = setup();
  // deterministic deck: the first player holds BLACK 1..13 + a Joker and empties his rack at once
  const sami = ws();
  handler.handle(asWs(sami), { type: 'auth_req', accessToken: 'tok-sami' });
  await tick();
  handler.handle(asWs(sami), { type: 'create_room_req', config: { ...cfg, botCount: 1, maxPlayers: 2, initialMeldThreshold: 30 } });
  const code = sami.last('room_joined_res').roomCode;
  const room = manager.getRoom(code)!;
  (room as any).deckFactory = () => {
    const all = createStandardDeck(false);
    const mine = all.filter(t => (t.color === 'BLACK' && t.id.endsWith('_1')) || t.id === 'JOKER_1');
    return [...mine, ...all.filter(t => !mine.includes(t))];
  };
  room.startGame();

  const me = Array.from(room.players.values()).find(p => !p.isBot)!;
  const blacks = me.rack.tiles.filter(t => t.color === 'BLACK').sort((x, y) => x.number - y.number);
  const joker = me.rack.tiles.find(t => t.isJoker)!;
  const melds: MeldSet[] = [
    { id: 'r1', gridRow: 0, gridCol: 0, tiles: blacks.slice(0, 7) },
    { id: 'r2', gridRow: 1, gridCol: 0, tiles: [...blacks.slice(7), joker] }
  ];
  room.handleEndTurn(me.id, melds, []);

  assert.strictEqual(room.status, 'FINISHED');
  assert.strictEqual(backend.saved.length, 1);
  const rec = backend.saved[0];
  assert.strictEqual(rec.roomCode, code);
  assert.strictEqual(rec.vsComputer, true);
  assert.strictEqual(rec.winnerName, 'Sami');
  const mine = rec.players.find(p => p.pseudo === 'Sami')!;
  assert.deepStrictEqual({ uid: mine.userId, win: mine.isWinner, bot: mine.isBot }, { uid: 'u-sami', win: true, bot: false });
  const bot = rec.players.find(p => p.isBot)!;
  assert.strictEqual(bot.userId, null);
  assert.ok(mine.totalScore > 0 && bot.totalScore < 0 && mine.totalScore === -bot.totalScore);
  room.dispose();
});

test('SupabaseAccounts: verifies the token with Supabase and reads the pseudo', async () => {
  const calls: Array<{ url: string; headers: any }> = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = (async (url: any, init: any) => {
    calls.push({ url: String(url), headers: init?.headers ?? {} });
    if (String(url).includes('/auth/v1/user')) {
      return init.headers.Authorization === 'Bearer good' ? { ok: true, status: 200, json: async () => ({ id: 'u-1' }) } : { ok: false, status: 401, json: async () => ({}) };
    }
    if (String(url).includes('/rest/v1/profiles')) return { ok: true, status: 200, json: async () => [{ pseudo: 'Karim' }] };
    if (String(url).includes('/rest/v1/friendships')) {
      return { ok: true, status: 200, json: async () => [{ requester: 'u-1', addressee: 'u-2' }, { requester: 'u-3', addressee: 'u-1' }] };
    }
    return { ok: true, status: 201, json: async () => [{ id: 'hist-1' }] };
  }) as any;
  try {
    const sb = new SupabaseAccounts('https://p.supabase.co', 'SERVICE');
    assert.deepStrictEqual(await sb.verify('good'), { userId: 'u-1', pseudo: 'Karim', avatar: null });
    assert.strictEqual(await sb.verify('bad'), null);
    assert.deepStrictEqual((await sb.friendIds('u-1')).sort(), ['u-2', 'u-3']);
    // the user's own token is what Supabase validates; the service key stays server side
    assert.strictEqual(calls[0].headers.Authorization, 'Bearer good');
    await sb.saveGame({ roomCode: 'AB12CD', roundNumber: 1, winnerName: 'Karim', vsComputer: false,
      players: [{ pseudo: 'Karim', userId: 'u-1', isBot: false, isWinner: true, remainingTiles: 0, roundDelta: 10, totalScore: 10 }] });
    assert.ok(calls.some(c => c.url.endsWith('/rest/v1/game_players')));
    assert.strictEqual(new SupabaseAccounts(undefined, undefined).enabled, false);
  } finally {
    globalThis.fetch = realFetch;
  }
});

test('Avatars: the avatar of an account is shown to the other players, computers get the robot', async () => {
  const { handler, tick, manager, backend } = setup();
  backend.tokens.set('tok-sami', { userId: 'u-sami', pseudo: 'Sami', avatar: 'emoji:🦊' });
  backend.tokens.set('tok-yas', { userId: 'u-yas', pseudo: 'Yasmine', avatar: 'url:https://x.supabase.co/storage/v1/object/public/avatars/u-yas/avatar.jpg' });
  const a = ws();
  const b = ws();
  handler.handle(asWs(a), { type: 'auth_req', accessToken: 'tok-sami' });
  handler.handle(asWs(b), { type: 'auth_req', accessToken: 'tok-yas' });
  await tick();
  assert.strictEqual(a.last('auth_res').avatar, 'emoji:🦊');

  handler.handle(asWs(a), { type: 'create_room_req', config: cfg });
  const code = a.last('room_joined_res').roomCode;
  handler.handle(asWs(b), { type: 'join_room_req', roomCode: code });
  const joined = b.last('room_joined_res').players;
  assert.deepStrictEqual(joined.map((p: any) => p.avatar), ['emoji:🦊', 'url:https://x.supabase.co/storage/v1/object/public/avatars/u-yas/avatar.jpg']);
  assert.strictEqual(a.last('player_joined_evt').player.avatar, joined[1].avatar);
  manager.getRoom(code)?.dispose();

  const c = ws();
  handler.handle(asWs(c), { type: 'auth_req', accessToken: 'tok-sami' });
  await tick();
  handler.handle(asWs(c), { type: 'create_room_req', config: { ...cfg, botCount: 2, maxPlayers: 3 } });
  const players = c.last('room_joined_res').players;
  assert.deepStrictEqual(players.filter((p: any) => p.name.includes('(ordi)')).map((p: any) => p.avatar), ['emoji:🤖', 'emoji:🤖']);
  manager.getRoom(c.last('room_joined_res').roomCode)?.dispose();
});

test('Session: the host ends the game, everybody gets the cumulated standings and the table closes', async () => {
  const { handler, tick, manager } = setup();
  const a = ws();
  const b = ws();
  handler.handle(asWs(a), { type: 'auth_req', accessToken: 'tok-sami' });
  handler.handle(asWs(b), { type: 'auth_req', accessToken: 'tok-yas' });
  await tick();
  handler.handle(asWs(a), { type: 'create_room_req', config: { ...cfg, initialMeldThreshold: 30 } });
  const code = a.last('room_joined_res').roomCode;
  handler.handle(asWs(b), { type: 'join_room_req', roomCode: code });
  const room = manager.getRoom(code)!;

  // a round in progress cannot be cut short
  room.startGame();
  handler.handle(asWs(a), { type: 'end_session_req' });
  assert.strictEqual(a.last('error').code, 'CANNOT_END');

  // two finished rounds (Sami then Yasmine win), then the host ends the game
  (room as any).status = 'FINISHED';
  (room as any).roundNumber = 2;
  (room as any).totalScores.set(Array.from(room.players.keys())[0], 40);
  (room as any).totalScores.set(Array.from(room.players.keys())[1], -40);
  (room as any).roundsWon.set(Array.from(room.players.keys())[0], 1);

  // only the host may end it
  handler.handle(asWs(b), { type: 'end_session_req' });
  assert.strictEqual(b.last('error').code, 'UNAUTHORIZED');
  assert.ok(manager.getRoom(code), 'the table is still open');

  handler.handle(asWs(a), { type: 'end_session_req' });
  const over = b.last('session_over_evt');
  assert.strictEqual(over.roundsPlayed, 2);
  assert.strictEqual(over.winnerName, 'Sami');
  assert.deepStrictEqual(over.standings.map((s: any) => [s.playerName, s.totalScore, s.roundsWon]), [['Sami', 40, 1], ['Yasmine', -40, 0]]);
  assert.strictEqual(a.ofType('session_over_evt').length, 1);
  assert.strictEqual(manager.getRoom(code), undefined, 'the table is closed');
});

test('Presence: "last seen" is recorded when a player connects and when his last socket closes', async () => {
  const { handler, tick, backend } = setup();
  const phone1 = ws();
  const phone2 = ws();
  handler.handle(asWs(phone1), { type: 'auth_req', accessToken: 'tok-sami' });
  await tick();
  assert.deepStrictEqual(backend.touched, ['u-sami'], 'seen when connecting');

  handler.handle(asWs(phone2), { type: 'auth_req', accessToken: 'tok-sami' });   // same player, second device
  await tick();
  handler.onClose(asWs(phone1));
  assert.strictEqual(backend.touched.length, 2, 'still online on the other device: no "went offline" record');

  handler.onClose(asWs(phone2));
  assert.strictEqual(backend.touched.length, 3, 'recorded when the last socket closes');
  assert.strictEqual(backend.touched[2], 'u-sami');
});

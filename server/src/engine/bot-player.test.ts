import test from 'node:test';
import assert from 'node:assert';
import { computeBotMove } from './bot-player.js';
import { BoardValidator } from './board-validator.js';
import { DEFAULT_GAME_CONFIG, MeldSet, Tile, TileColor } from '../models/types.js';

const t = (id: string, color: TileColor, n: number): Tile => ({ id, color, number: n, isJoker: false });
const joker = (id: string): Tile => ({ id, color: null, number: 0, isJoker: true });
const meld = (id: string, tiles: Tile[]): MeldSet => ({ id, tiles, gridRow: 0, gridCol: 0 });

function assertSound(rack: Tile[], board: MeldSet[], move: NonNullable<ReturnType<typeof computeBotMove>>) {
  // every table tile is still on the table, every tile exists exactly once
  const after = move.melds.flatMap(m => m.tiles.map(x => x.id));
  assert.strictEqual(new Set(after).size, after.length, 'no duplicated tile');
  for (const m of board) for (const x of m.tiles) assert.ok(after.includes(x.id), `table tile ${x.id} must stay on the table`);
  const all = new Set([...board.flatMap(m => m.tiles.map(x => x.id)), ...rack.map(x => x.id)]);
  for (const id of after) assert.ok(all.has(id), `unknown tile ${id}`);
  for (const id of move.remainingRackTileIds) assert.ok(!after.includes(id), 'a tile cannot be both kept and played');
  assert.strictEqual(BoardValidator.validateBoard({ melds: move.melds } as any).isValid, true, 'the whole table must be valid');
}

test('Bot: takes a tile from a group of four to build a run with two rack tiles', () => {
  const board = [meld('g7', [t('7R', 'RED', 7), t('7B', 'BLUE', 7), t('7K', 'BLACK', 7), t('7Y', 'YELLOW', 7)])];
  const rack = [t('8R', 'RED', 8), t('9R', 'RED', 9)];
  const move = computeBotMove(rack, board, true, DEFAULT_GAME_CONFIG);
  assert.ok(move, 'the bot must find the move');
  assertSound(rack, board, move!);
  assert.strictEqual(move!.remainingRackTileIds.length, 0, 'both rack tiles are played');
});

test('Bot: splits a run when a duplicate tile is added (4-5-6-7-8 + 6 = 4-5-6 and 6-7-8)', () => {
  const board = [meld('r', [t('4', 'RED', 4), t('5', 'RED', 5), t('6a', 'RED', 6), t('7', 'RED', 7), t('8', 'RED', 8)])];
  const rack = [t('6b', 'RED', 6)];
  const move = computeBotMove(rack, board, true, DEFAULT_GAME_CONFIG);
  assert.ok(move);
  assertSound(rack, board, move!);
  assert.strictEqual(move!.remainingRackTileIds.length, 0);
  assert.strictEqual(move!.melds.length, 2);
});

test('Bot: uses a Joker from the table by replacing it with the real tile from its rack', () => {
  // table: 4 5 Joker (Joker stands for 6). Rack: red 6 and blue 6, black 6 -> Joker is freed for a group
  const board = [meld('r', [t('4', 'RED', 4), t('5', 'RED', 5), joker('J1')])];
  const rack = [t('6R', 'RED', 6), t('6B', 'BLUE', 6), t('6K', 'BLACK', 6)];
  const move = computeBotMove(rack, board, true, DEFAULT_GAME_CONFIG);
  assert.ok(move);
  assertSound(rack, board, move!);
  assert.ok(rack.length - move!.remainingRackTileIds.length >= 3, 'plays all three 6s');
});

test('Bot: never invents moves, passes when nothing is playable', () => {
  const board = [meld('g', [t('7R', 'RED', 7), t('7B', 'BLUE', 7), t('7K', 'BLACK', 7)])];
  const rack = [t('2R', 'RED', 2), t('12B', 'BLUE', 12)];
  assert.strictEqual(computeBotMove(rack, board, true, DEFAULT_GAME_CONFIG), null);
});

test('Bot: first meld needs the threshold and never touches the table', () => {
  const board = [meld('g', [t('7R', 'RED', 7), t('7B', 'BLUE', 7), t('7K', 'BLACK', 7)])];
  // 10+11+12 red = 33 >= 30
  const rack = [t('10R', 'RED', 10), t('11R', 'RED', 11), t('12R', 'RED', 12), t('7Y', 'YELLOW', 7)];
  const move = computeBotMove(rack, board, false, DEFAULT_GAME_CONFIG);
  assert.ok(move);
  const untouched = move!.melds.find(m => m.id === 'g')!;
  assert.deepStrictEqual(untouched.tiles.map(x => x.id), ['7R', '7B', '7K']);
  // 7+7... too low for the threshold
  const weak = [t('2R', 'RED', 2), t('3R', 'RED', 3), t('4R', 'RED', 4)];
  assert.strictEqual(computeBotMove(weak, board, false, DEFAULT_GAME_CONFIG), null);
});

test('Bot: stays fast and sound on a crowded late-game table', () => {
  // a big table of runs and groups, and a rack of 12 loose tiles
  const colors: TileColor[] = ['BLACK', 'BLUE', 'RED', 'YELLOW'];
  let id = 0;
  const board: MeldSet[] = [];
  for (const c of colors) {
    board.push(meld('run' + c, [1, 2, 3, 4, 5].map(n => t('T' + id++, c, n))));
    board.push(meld('run2' + c, [8, 9, 10, 11].map(n => t('T' + id++, c, n))));
  }
  for (const n of [6, 7, 12, 13]) {
    board.push(meld('grp' + n, colors.slice(0, 3).map(c => t('T' + id++, c, n))));
  }
  const rack = [t('a', 'YELLOW', 6), t('b', 'YELLOW', 7), t('c', 'RED', 6), t('d', 'BLACK', 12), t('e', 'YELLOW', 12),
    t('f', 'RED', 13), t('g', 'RED', 7), t('h', 'BLUE', 13), t('i', 'BLACK', 6), t('j', 'BLUE', 12), t('k', 'BLACK', 13), joker('JJ')];
  const started = Date.now();
  const move = computeBotMove(rack, board, true, DEFAULT_GAME_CONFIG);
  const elapsed = Date.now() - started;
  assert.ok(move, 'there are playable tiles');
  assertSound(rack, board, move!);
  assert.ok(elapsed < 1500, `computing took ${elapsed} ms`);
  console.log(`  [bot] table de ${board.length} combinaisons : ${rack.length - move!.remainingRackTileIds.length} tuiles posées en ${elapsed} ms`);
});

import test from 'node:test';
import assert from 'node:assert';
import { BoardValidator } from './board-validator.js';
import { MeldSet, Tile } from '../models/types.js';

test('BoardValidator: valid run 3-4-5 Red', () => {
  const meld: MeldSet = {
    id: 'm1',
    gridRow: 0,
    gridCol: 0,
    tiles: [
      { id: '1', color: 'RED', number: 3, isJoker: false },
      { id: '2', color: 'RED', number: 4, isJoker: false },
      { id: '3', color: 'RED', number: 5, isJoker: false }
    ]
  };
  const res = BoardValidator.validateRun(meld);
  assert.strictEqual(res.isValid, true);
  assert.strictEqual(res.points, 12);
});

test('BoardValidator: valid run with Joker in middle', () => {
  const meld: MeldSet = {
    id: 'm2',
    gridRow: 0,
    gridCol: 0,
    tiles: [
      { id: '1', color: 'YELLOW', number: 4, isJoker: false },
      { id: 'j', color: null, number: 0, isJoker: true },
      { id: '3', color: 'YELLOW', number: 6, isJoker: false }
    ]
  };
  const res = BoardValidator.validateRun(meld);
  assert.strictEqual(res.isValid, true);
  assert.strictEqual(res.points, 15);
  assert.strictEqual(res.resolvedTiles[1].number, 5);
});

test('BoardValidator: invalid wrap-around 12-13-1', () => {
  const meld: MeldSet = {
    id: 'm3',
    gridRow: 0,
    gridCol: 0,
    tiles: [
      { id: '1', color: 'RED', number: 12, isJoker: false },
      { id: '2', color: 'RED', number: 13, isJoker: false },
      { id: '3', color: 'RED', number: 1, isJoker: false }
    ]
  };
  const res = BoardValidator.validateRun(meld);
  assert.strictEqual(res.isValid, false);
});

test('BoardValidator: valid group 7 Red, 7 Blue, 7 Black', () => {
  const meld: MeldSet = {
    id: 'm4',
    gridRow: 0,
    gridCol: 0,
    tiles: [
      { id: '1', color: 'RED', number: 7, isJoker: false },
      { id: '2', color: 'BLUE', number: 7, isJoker: false },
      { id: '3', color: 'BLACK', number: 7, isJoker: false }
    ]
  };
  const res = BoardValidator.validateGroup(meld);
  assert.strictEqual(res.isValid, true);
  assert.strictEqual(res.points, 21);
});

test('BoardValidator: invalid group with duplicate color', () => {
  const meld: MeldSet = {
    id: 'm5',
    gridRow: 0,
    gridCol: 0,
    tiles: [
      { id: '1', color: 'RED', number: 7, isJoker: false },
      { id: '2', color: 'RED', number: 7, isJoker: false },
      { id: '3', color: 'BLACK', number: 7, isJoker: false }
    ]
  };
  const res = BoardValidator.validateGroup(meld);
  assert.strictEqual(res.isValid, false);
});

test('BoardValidator: run in shuffled order is invalid', () => {
  const t = (id: string, n: number): Tile => ({ id, color: 'RED', number: n, isJoker: false });
  const shuffled: MeldSet = { id: 'm9', gridRow: 0, gridCol: 0, tiles: [t('a', 5), t('b', 3), t('c', 4)] };
  assert.strictEqual(BoardValidator.validateMeld(shuffled).isValid, false);
  const sorted: MeldSet = { id: 'm10', gridRow: 0, gridCol: 0, tiles: [t('b', 3), t('c', 4), t('a', 5)] };
  assert.strictEqual(BoardValidator.validateMeld(sorted).isValid, true);
  const descending: MeldSet = { id: 'm11', gridRow: 0, gridCol: 0, tiles: [t('a', 5), t('c', 4), t('b', 3)] };
  assert.strictEqual(BoardValidator.validateMeld(descending).isValid, false);
});

test('a joker placed between two consecutive numbers is stored at the end of the run', () => {
  const t = (id: string, n: number, joker = false): any =>
    joker ? { id, isJoker: true } : { id, color: 'BLACK', number: n, isJoker: false };
  const meld: any = { id: 'M1', tiles: [t('a', 4), t('j', 0, true), t('b', 5)] };
  const board: any = { versionId: 1, melds: [meld], drawPileCount: 0, activePlayerId: '', turnRemainingMillis: 0, isGracePeriod: false, disconnectedPlayerId: null };
  const result = BoardValidator.validateBoard(board);
  assert.strictEqual(result.isValid, true);
  const normalized = BoardValidator.normalizeRunOrder(board.melds, result.meldResults);
  assert.deepStrictEqual(normalized[0].tiles.map((x: any) => x.id), ['j', 'a', 'b']);
});

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

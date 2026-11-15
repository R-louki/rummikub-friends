import crypto from 'node:crypto';
import { BoardState, GameConfig, MeldSet, Tile, TileColor } from '../models/types.js';
import { BoardValidator } from './board-validator.js';

export interface BotMove {
  /** The whole proposed board (existing melds, possibly extended, plus new melds). */
  melds: MeldSet[];
  /** Ids of the rack tiles the bot keeps. */
  remainingRackTileIds: string[];
}

interface Candidate {
  kind: 'RUN' | 'GROUP';
  /** Tile keys in laying order; 'J' stands for a Joker. */
  slots: string[];
  /** Numbers the slots stand for (Joker counts as the number it replaces). */
  numbers: number[];
  points: number;
}

const COLORS: TileColor[] = ['BLACK', 'BLUE', 'RED', 'YELLOW'];
const MAX_NODES = 40_000;

const keyOf = (color: TileColor | null, number: number) => `${color}:${number}`;

/**
 * Computes a move for a computer player: the best set of new melds it can lay down from its
 * rack (most tiles first), then single-tile extensions of melds already on the table.
 * Returns null when there is nothing playable (the bot then draws a tile).
 *
 * The result is only a proposal: the Room validates it exactly like a human's turn.
 */
export function computeBotMove(
  rack: Tile[],
  board: MeldSet[],
  hasMadeInitialMeld: boolean,
  config: GameConfig
): BotMove | null {
  const strategy = config.jokerPointStrategy;
  const threshold = hasMadeInitialMeld ? 0 : config.initialMeldThreshold;
  const allowJokers = hasMadeInitialMeld || config.jokerAllowedInInitialMeld;

  // ---- 1. pool of rack tiles by (color, number) and Jokers
  const pool = new Map<string, Tile[]>();
  const jokers: Tile[] = [];
  for (const t of rack) {
    if (t.isJoker) {
      if (allowJokers) jokers.push(t);
    } else {
      const k = keyOf(t.color, t.number);
      if (!pool.has(k)) pool.set(k, []);
      pool.get(k)!.push(t);
    }
  }
  const have = (k: string) => pool.get(k)?.length ?? 0;

  // ---- 2. candidate melds
  const candidates: Candidate[] = [];
  const pts = (numbers: number[], jokerMask: boolean[]) =>
    numbers.reduce((s, n, i) => s + (jokerMask[i] && strategy === 'ZERO_POINTS' ? 0 : n), 0);

  for (const color of COLORS) {
    for (let s = 1; s <= 11; s++) {
      for (let len = 3; s + len - 1 <= 13 && len <= 9; len++) {
        const slots: string[] = [];
        const numbers: number[] = [];
        const mask: boolean[] = [];
        let missing = 0;
        for (let n = s; n < s + len; n++) {
          const k = keyOf(color, n);
          if (have(k) > 0) {
            slots.push(k);
            mask.push(false);
          } else {
            missing++;
            slots.push('J');
            mask.push(true);
          }
          numbers.push(n);
        }
        if (missing > 1 || (missing === 1 && jokers.length === 0)) continue;
        if (len - missing < 2) continue;
        candidates.push({ kind: 'RUN', slots, numbers, points: pts(numbers, mask) });
      }
    }
  }
  for (let n = 1; n <= 13; n++) {
    const avail = COLORS.filter(c => have(keyOf(c, n)) > 0);
    const subsets: TileColor[][] = [];
    const rec = (i: number, cur: TileColor[]) => {
      if (i === avail.length) {
        if (cur.length >= 2) subsets.push([...cur]);
        return;
      }
      rec(i + 1, [...cur, avail[i]]);
      rec(i + 1, cur);
    };
    rec(0, []);
    for (const sub of subsets) {
      if (sub.length >= 3) {
        const slots = sub.map(c => keyOf(c, n));
        candidates.push({ kind: 'GROUP', slots, numbers: slots.map(() => n), points: n * slots.length });
      } else if (jokers.length > 0) {
        const slots = [...sub.map(c => keyOf(c, n)), 'J'];
        candidates.push({ kind: 'GROUP', slots, numbers: slots.map(() => n), points: pts(slots.map(() => n), [false, false, true]) });
      }
    }
  }

  // Longer / joker-free candidates first: a better first solution is found early.
  candidates.sort((a, b) => {
    const ja = a.slots.filter(x => x === 'J').length;
    const jb = b.slots.filter(x => x === 'J').length;
    if (ja !== jb) return ja - jb;
    return b.slots.length - a.slots.length;
  });

  // ---- 3. depth-first search over disjoint candidates
  const counts = new Map<string, number>();
  for (const [k, v] of pool) counts.set(k, v.length);
  let jokersLeft = jokers.length;

  let best: { picks: number[]; score: number } | null = null;
  let nodes = 0;

  const dfs = (startIndex: number, picks: number[], tiles: number, points: number, jokersUsed: number) => {
    if (nodes++ > MAX_NODES) return;
    if (picks.length > 0 && points >= threshold) {
      const score = tiles * 100 + points - jokersUsed * 70;
      if (!best || score > best.score) best = { picks: [...picks], score };
    }
    for (let i = startIndex; i < candidates.length; i++) {
      const c = candidates[i];
      let ok = true;
      const need = new Map<string, number>();
      let jn = 0;
      for (const sl of c.slots) {
        if (sl === 'J') { jn++; continue; }
        need.set(sl, (need.get(sl) ?? 0) + 1);
      }
      if (jn > jokersLeft) continue;
      for (const [k, v] of need) if ((counts.get(k) ?? 0) < v) { ok = false; break; }
      if (!ok) continue;

      for (const [k, v] of need) counts.set(k, counts.get(k)! - v);
      jokersLeft -= jn;
      picks.push(i);
      dfs(i, picks, tiles + c.slots.length, points + c.points, jokersUsed + jn);
      picks.pop();
      jokersLeft += jn;
      for (const [k, v] of need) counts.set(k, counts.get(k)! + v);
      if (nodes > MAX_NODES) return;
    }
  };
  dfs(0, [], 0, 0, 0);

  // ---- 4. build the new melds with real tiles
  const working = new Map<string, Tile[]>();
  for (const [k, v] of pool) working.set(k, [...v]);
  const spareJokers = [...jokers];
  const newMelds: MeldSet[] = [];
  const used = new Set<string>();

  if (best) {
    for (const idx of (best as { picks: number[] }).picks) {
      const c = candidates[idx];
      const tiles: Tile[] = [];
      for (const sl of c.slots) {
        const t = sl === 'J' ? spareJokers.shift()! : working.get(sl)!.shift()!;
        tiles.push(t);
        used.add(t.id);
      }
      newMelds.push({ id: crypto.randomUUID(), tiles, gridRow: 0, gridCol: 0 });
    }
  } else if (!hasMadeInitialMeld) {
    return null; // cannot reach the first-meld threshold: draw
  }

  // ---- 5. extend melds already on the table with leftover tiles (only after the first meld)
  const boardMelds: MeldSet[] = board.map(m => ({ ...m, tiles: [...m.tiles] }));
  if (hasMadeInitialMeld) {
    const all = [...boardMelds, ...newMelds];
    let changed = true;
    while (changed) {
      changed = false;
      const leftovers = rack.filter(t => !used.has(t.id) && !t.isJoker);
      outer: for (const t of leftovers) {
        for (const m of all) {
          if (m.tiles.length >= 13) continue;
          for (const candidate of [[...m.tiles, t], [t, ...m.tiles]]) {
            const test: MeldSet = { ...m, tiles: candidate };
            if (BoardValidator.validateMeld(test, strategy).isValid) {
              m.tiles = candidate;
              used.add(t.id);
              changed = true;
              break outer;
            }
          }
        }
      }
    }
  }

  if (used.size === 0) return null;

  const proposed = [...boardMelds, ...newMelds];
  const validation = BoardValidator.validateBoard({ melds: proposed } as BoardState, strategy);
  if (!validation.isValid) return null;

  return {
    melds: proposed,
    remainingRackTileIds: rack.filter(t => !used.has(t.id)).map(t => t.id)
  };
}

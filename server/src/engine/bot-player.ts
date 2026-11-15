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
function computeSimpleMove(
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

// =====================================================================================
// SMART SOLVER: rearranges the whole table
// =====================================================================================

interface SmartCand {
  /** Slots in laying order: key index (color*13 + number-1) or -1 for a Joker. */
  slots: number[];
  /** Distinct key indexes used and how many tiles of each. */
  ks: number[];
  cs: number[];
  jokers: number;
}

const colorIndex = (c: TileColor | null) => COLORS.indexOf(c as TileColor);
const keyIdx = (t: Tile) => colorIndex(t.color) * 13 + (t.number - 1);
const JOKER_KEY = 52;

/**
 * Considers EVERY tile on the table together with the rack and looks for an arrangement of
 * valid melds that keeps all table tiles on the table and lays down as many rack tiles as possible.
 * This is what lets the computer split runs, steal from groups of four, move Jokers, etc.
 */
function* smartMoveGen(
  rack: Tile[],
  board: MeldSet[],
  config: GameConfig,
  nodeCap = 60_000,
  timeBudgetMs = 1500
): Generator<void, BotMove | null, void> {
  const strategy = config.jokerPointStrategy;

  const need = new Int8Array(52);
  const avail = new Int8Array(52);
  let needJ = 0;
  let availJ = 0;
  const tableTiles: Tile[][] = Array.from({ length: 52 }, () => []);
  const rackTiles: Tile[][] = Array.from({ length: 52 }, () => []);
  const tableJok: Tile[] = [];
  const rackJok: Tile[] = [];

  for (const m of board) {
    for (const t of m.tiles) {
      if (t.isJoker) { needJ++; tableJok.push(t); }
      else { const k = keyIdx(t); need[k]++; tableTiles[k].push(t); }
    }
  }
  for (const t of rack) {
    if (t.isJoker) { availJ++; rackJok.push(t); }
    else { const k = keyIdx(t); avail[k]++; rackTiles[k].push(t); }
  }
  const totalRackTiles = rack.length;
  const total = (k: number) => need[k] + avail[k];
  const totalJ = needJ + availJ;

  // ---- candidate melds
  const cands: SmartCand[] = [];
  const seen = new Set<string>();
  const add = (slots: number[]) => {
    const sig = slots.join(',');
    if (seen.has(sig)) return;
    seen.add(sig);
    const counts = new Map<number, number>();
    let j = 0;
    for (const s of slots) {
      if (s === -1) j++;
      else counts.set(s, (counts.get(s) ?? 0) + 1);
    }
    if (j > totalJ) return;
    for (const [k, c] of counts) if (total(k) < c) return;
    cands.push({ slots, ks: Array.from(counts.keys()), cs: Array.from(counts.values()), jokers: j });
  };

  // identity candidates: the melds already on the table come first (always a valid fallback)
  const identity: SmartCand[] = [];
  for (const m of board) {
    const slots = m.tiles.map(t => (t.isJoker ? -1 : keyIdx(t)));
    const before = cands.length;
    add(slots);
    if (cands.length > before) identity.push(cands[cands.length - 1]);
  }

  // runs
  for (let c = 0; c < 4; c++) {
    for (let s = 1; s <= 11; s++) {
      for (let len = 3; s + len - 1 <= 13; len++) {
        const base: number[] = [];
        let missing = 0;
        for (let n = s; n < s + len; n++) {
          const k = c * 13 + (n - 1);
          if (total(k) > 0) base.push(k); else { base.push(-1); missing++; }
        }
        if (missing > 2 || len - missing < 1) continue;
        if (missing <= totalJ) add(base);
        // one present tile replaced by a Joker (frees that tile for another meld)
        if (missing + 1 <= Math.min(2, totalJ)) {
          for (let p = 0; p < len; p++) {
            if (base[p] === -1) continue;
            const variant = base.slice();
            variant[p] = -1;
            if (len - (missing + 1) >= 1) add(variant);
          }
        }
      }
    }
  }
  // groups
  for (let n = 1; n <= 13; n++) {
    const colors: number[] = [];
    for (let c = 0; c < 4; c++) if (total(c * 13 + (n - 1)) > 0) colors.push(c);
    const subsets: number[][] = [];
    const rec = (i: number, cur: number[]) => {
      if (i === colors.length) { if (cur.length >= 1) subsets.push(cur.slice()); return; }
      rec(i + 1, [...cur, colors[i]]);
      rec(i + 1, cur);
    };
    rec(0, []);
    for (const sub of subsets) {
      const real = sub.map(c => c * 13 + (n - 1));
      const size = real.length;
      for (let j = 0; j <= 2; j++) {
        const len = size + j;
        if (len < 3 || len > 4) continue;
        add([...real, ...Array(j).fill(-1)]);
      }
    }
  }

  // order: identity first, then joker-free and longer melds first
  const identitySet = new Set(identity);
  cands.sort((a, b) => {
    const ia = identitySet.has(a) ? 0 : 1;
    const ib = identitySet.has(b) ? 0 : 1;
    if (ia !== ib) return ia - ib;
    if (a.jokers !== b.jokers) return a.jokers - b.jokers;
    return b.slots.length - a.slots.length;
  });

  const byKey: number[][] = Array.from({ length: 53 }, () => []);
  cands.forEach((c, i) => {
    for (const k of c.ks) byKey[k].push(i);
    if (c.jokers > 0) byKey[JOKER_KEY].push(i);
  });
  // melds made only of rack tiles (the optional phase after the table is covered)
  const rackOnly: number[] = [];
  cands.forEach((c, i) => {
    if (c.ks.every((k, x) => avail[k] >= c.cs[x]) && c.jokers <= availJ) rackOnly.push(i);
  });

  // ---- depth-first search
  let rackUsed = 0;
  let jokersFromRack = 0;
  let nodes = 0;
  const started = Date.now();
  let best: { picks: number[]; score: number } | null = null;

  const feasible = (c: SmartCand) => {
    for (let i = 0; i < c.ks.length; i++) if (need[c.ks[i]] + avail[c.ks[i]] < c.cs[i]) return false;
    return needJ + availJ >= c.jokers;
  };
  const take = (c: SmartCand) => {
    const fn: number[] = [];
    for (let i = 0; i < c.ks.length; i++) {
      const k = c.ks[i];
      const f = Math.min(need[k], c.cs[i]);
      fn.push(f);
      need[k] -= f;
      avail[k] -= c.cs[i] - f;
      rackUsed += c.cs[i] - f;
    }
    const fj = Math.min(needJ, c.jokers);
    needJ -= fj;
    availJ -= c.jokers - fj;
    rackUsed += c.jokers - fj;
    jokersFromRack += c.jokers - fj;
    return { fn, fj };
  };
  const give = (c: SmartCand, u: { fn: number[]; fj: number }) => {
    for (let i = 0; i < c.ks.length; i++) {
      const k = c.ks[i];
      need[k] += u.fn[i];
      avail[k] += c.cs[i] - u.fn[i];
      rackUsed -= c.cs[i] - u.fn[i];
    }
    needJ += u.fj;
    availJ += c.jokers - u.fj;
    rackUsed -= c.jokers - u.fj;
    jokersFromRack -= c.jokers - u.fj;
  };

  const picks: number[] = [];
  // The search yields every ~1000 nodes so a server can interleave other work (other players' commands)
  function* dfs(optStart: number): Generator<void, void, void> {
    if (nodes++ > nodeCap) return;
    if ((nodes & 1023) === 0) {
      if (Date.now() - started > timeBudgetMs) { nodes = nodeCap + 1; return; }
      yield;
    }

    let mk = -1;
    for (let k = 0; k < 52; k++) if (need[k] > 0) { mk = k; break; }
    if (mk === -1 && needJ > 0) mk = JOKER_KEY;

    if (mk !== -1) {
      // a table tile is still uncovered: it must end up in some meld
      for (const ci of byKey[mk]) {
        const c = cands[ci];
        if (!feasible(c)) continue;
        const u = take(c);
        picks.push(ci);
        yield* dfs(optStart);
        picks.pop();
        give(c, u);
        if (nodes > nodeCap) return;
      }
      return;
    }

    // every table tile is covered: score this arrangement
    const left = totalRackTiles - rackUsed;
    const score = rackUsed * 100 - jokersFromRack * 90 + (left === 0 ? 1000 : 0);
    if (rackUsed > 0 && (!best || score > best.score)) best = { picks: picks.slice(), score };

    // optionally lay down more melds made of rack tiles only
    for (let i = optStart; i < rackOnly.length; i++) {
      const c = cands[rackOnly[i]];
      if (!feasible(c)) continue;
      const u = take(c);
      picks.push(rackOnly[i]);
      yield* dfs(i);
      picks.pop();
      give(c, u);
      if (nodes > nodeCap) return;
    }
  }
  yield; // candidates are built: let others run before the search starts
  yield* dfs(0);

  if (!best) return null;

  // ---- rebuild real melds from the winning picks
  const tPool = tableTiles.map(a => a.slice());
  const rPool = rackTiles.map(a => a.slice());
  const tJ = tableJok.slice();
  const rJ = rackJok.slice();
  const usedFromRack = new Set<string>();
  const melds: MeldSet[] = [];
  const oldById = board.map(m => ({ id: m.id, ids: m.tiles.map(t => t.id), gridRow: m.gridRow, gridCol: m.gridCol }));
  const claimed = new Set<string>();

  for (const ci of (best as { picks: number[] }).picks) {
    const c = cands[ci];
    const tiles: Tile[] = [];
    for (const s of c.slots) {
      let t: Tile | undefined;
      if (s === -1) {
        if (tJ.length > 0) t = tJ.shift();
        else { t = rJ.shift(); if (t) usedFromRack.add(t.id); }
      } else if (tPool[s].length > 0) {
        t = tPool[s].shift();
      } else {
        t = rPool[s].shift();
        if (t) usedFromRack.add(t.id);
      }
      if (!t) return null;
      tiles.push(t);
    }
    // keep the id (and position) of an unchanged table meld so the screen does not reshuffle
    const sameAs = oldById.find(o => !claimed.has(o.id) && o.ids.length === tiles.length && o.ids.every((id, i) => id === tiles[i].id));
    if (sameAs) claimed.add(sameAs.id);
    melds.push({
      id: sameAs ? sameAs.id : crypto.randomUUID(),
      tiles,
      gridRow: sameAs ? sameAs.gridRow : 0,
      gridCol: sameAs ? sameAs.gridCol : 0
    });
  }

  if (usedFromRack.size === 0) return null;
  // every table tile must still be on the table
  const onTable = new Set(melds.flatMap(m => m.tiles.map(t => t.id)));
  for (const m of board) for (const t of m.tiles) if (!onTable.has(t.id)) return null;

  const validation = BoardValidator.validateBoard({ melds } as BoardState, strategy);
  if (!validation.isValid) return null;

  return {
    melds,
    remainingRackTileIds: rack.filter(t => !usedFromRack.has(t.id)).map(t => t.id)
  };
}

/**
 * Computer player move: after the first meld it solves the whole table (splitting runs, taking
 * tiles from groups, moving Jokers...). The simpler "new melds + extensions" move is kept as a
 * safety net, and whichever lays down more tiles wins.
 *
 * It is a generator that yields while thinking, so the server can keep answering players:
 * drive it with {@link solveBotMove} (production) or {@link computeBotMove} (tests, synchronous).
 */
export function* botMoveGen(
  rack: Tile[],
  board: MeldSet[],
  hasMadeInitialMeld: boolean,
  config: GameConfig
): Generator<void, BotMove | null, void> {
  const simple = computeSimpleMove(rack, board, hasMadeInitialMeld, config);
  yield;
  if (!hasMadeInitialMeld) return simple;

  let smart: BotMove | null = null;
  try {
    smart = yield* smartMoveGen(rack, board, config);
  } catch (err: any) {
    console.error('[Bot] solveur:', err?.message);
  }
  if (!smart) return simple;
  if (!simple) return smart;
  const placed = (m: BotMove) => rack.length - m.remainingRackTileIds.length;
  return placed(smart) > placed(simple) ? smart : simple;
}

/** Runs the whole computation at once (tests, tools). */
export function computeBotMove(
  rack: Tile[],
  board: MeldSet[],
  hasMadeInitialMeld: boolean,
  config: GameConfig
): BotMove | null {
  const gen = botMoveGen(rack, board, hasMadeInitialMeld, config);
  let r = gen.next();
  while (!r.done) r = gen.next();
  return r.value;
}

/**
 * Runs the computation in small slices, giving the event loop a turn between slices, and calls
 * [done] with the move. The server stays responsive while a computer player is thinking.
 */
export function solveBotMove(
  rack: Tile[],
  board: MeldSet[],
  hasMadeInitialMeld: boolean,
  config: GameConfig,
  done: (move: BotMove | null) => void
): void {
  const gen = botMoveGen(rack, board, hasMadeInitialMeld, config);
  const step = () => {
    let r: IteratorResult<void, BotMove | null>;
    try {
      r = gen.next();
    } catch (err: any) {
      console.error('[Bot] erreur:', err?.message);
      done(null);
      return;
    }
    if (r.done) done(r.value);
    else setImmediate(step);
  };
  step();
}

/**
 * The boards a spectator sees while the computer "moves tiles": the unchanged melds stay, and each
 * changed or new meld appears one after the other while the tiles it takes disappear from where
 * they were. The last state equals the final board. No tile is ever shown twice.
 */
export function intermediateBoards(before: MeldSet[], finalMelds: MeldSet[]): MeldSet[][] {
  const sameMeld = (a: MeldSet, b: MeldSet) =>
    a.id === b.id && a.tiles.length === b.tiles.length && a.tiles.every((t, i) => t.id === b.tiles[i].id);
  const unchanged = finalMelds.filter(f => before.some(b => sameMeld(b, f)));
  const changed = finalMelds.filter(f => !before.some(b => sameMeld(b, f)));
  const staleOld = before.filter(b => !finalMelds.some(f => sameMeld(b, f)));

  const states: MeldSet[][] = [];
  for (let k = 1; k <= changed.length; k++) {
    const revealed = changed.slice(0, k);
    const taken = new Set(revealed.flatMap(m => m.tiles.map(t => t.id)));
    const remainingOld = staleOld
      .map(m => ({ ...m, tiles: m.tiles.filter(t => !taken.has(t.id)) }))
      .filter(m => m.tiles.length > 0);
    states.push([...unchanged, ...remainingOld, ...revealed]);
  }
  return states;
}

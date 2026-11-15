import { BoardState, GameConfig, JokerPointStrategy, MeldSet, MeldType, Tile, TileColor } from '../models/types.js';

export interface MeldValidationResult {
  meldId: string;
  meldType: MeldType;
  isValid: boolean;
  errorMessage?: string;
  resolvedTiles: Tile[];
  points: number;
}

export interface BoardValidationResult {
  isValid: boolean;
  meldResults: MeldValidationResult[];
  totalBoardPoints: number;
  diagnostics: string[];
}

export class BoardValidator {
  static validateBoard(
    boardState: BoardState,
    strategy: JokerPointStrategy = 'REPLACED_TILE_VALUE'
  ): BoardValidationResult {
    if (boardState.melds.length === 0) {
      return { isValid: true, meldResults: [], totalBoardPoints: 0, diagnostics: [] };
    }

    const results = boardState.melds.map(m => this.validateMeld(m, strategy));
    const allValid = results.every(r => r.isValid);

    if (allValid) {
      const total = results.reduce((acc, r) => acc + r.points, 0);
      return { isValid: true, meldResults: results, totalBoardPoints: total, diagnostics: [] };
    }

    const invalid = results.filter(r => !r.isValid);
    return {
      isValid: false,
      meldResults: results,
      totalBoardPoints: results.filter(r => r.isValid).reduce((acc, r) => acc + r.points, 0),
      diagnostics: invalid.map(r => `Meld [${r.meldId}]: ${r.errorMessage}`)
    };
  }

  /**
   * A run accepted through the flexible-joker path (e.g. 4 ★ 5 read as ★ 4 5) is stored in its real
   * order, so a joker never sits between two consecutive numbers on the table.
   */
  static normalizeRunOrder(melds: MeldSet[], results: MeldValidationResult[]): MeldSet[] {
    const order = new Map<string, string[]>();
    for (const r of results) {
      if (r.isValid && r.meldType === 'RUN') order.set(r.meldId, r.resolvedTiles.map(t => t.id));
    }
    return melds.map(m => {
      const ids = order.get(m.id);
      if (!ids) return m;
      const byId = new Map<string, Tile>(m.tiles.map(t => [t.id, t]));
      const reordered = ids.map(id => byId.get(id)).filter((t): t is Tile => !!t);
      return reordered.length === m.tiles.length ? { ...m, tiles: reordered } : m;
    });
  }

  static validateMeld(
    meld: MeldSet,
    strategy: JokerPointStrategy = 'REPLACED_TILE_VALUE'
  ): MeldValidationResult {
    const tiles = meld.tiles;
    if (tiles.length < 3) {
      return {
        meldId: meld.id,
        meldType: 'INVALID',
        isValid: false,
        errorMessage: `Une combinaison doit contenir au moins 3 tuiles (actuel: ${tiles.length})`,
        resolvedTiles: [],
        points: 0
      };
    }

    if (tiles.length > 13) {
      return {
        meldId: meld.id,
        meldType: 'INVALID',
        isValid: false,
        errorMessage: `Une combinaison ne peut pas dépasser 13 tuiles (actuel: ${tiles.length})`,
        resolvedTiles: [],
        points: 0
      };
    }

    const groupResult = this.validateGroup(meld, strategy);
    if (groupResult.isValid) return groupResult;

    const runResult = this.validateRun(meld, strategy);
    if (runResult.isValid) return runResult;

    return {
      meldId: meld.id,
      meldType: 'INVALID',
      isValid: false,
      errorMessage: `Combinaison invalide : non conforme en Groupe (${groupResult.errorMessage}) et en Série (${runResult.errorMessage})`,
      resolvedTiles: [],
      points: 0
    };
  }

  static validateGroup(
    meld: MeldSet,
    strategy: JokerPointStrategy = 'REPLACED_TILE_VALUE'
  ): MeldValidationResult {
    const tiles = meld.tiles;
    if (tiles.length < 3 || tiles.length > 4) {
      return {
        meldId: meld.id,
        meldType: 'GROUP',
        isValid: false,
        errorMessage: `Un groupe doit contenir 3 ou 4 tuiles (actuel: ${tiles.length})`,
        resolvedTiles: [],
        points: 0
      };
    }

    const nonJokers = tiles.filter(t => !t.isJoker);
    const allColors: TileColor[] = ['BLACK', 'BLUE', 'RED', 'YELLOW'];

    if (nonJokers.length === 0) {
      const resolved = tiles.map((t, idx) => ({
        id: t.id,
        color: allColors[idx % allColors.length],
        number: 10,
        isJoker: true
      }));
      return {
        meldId: meld.id,
        meldType: 'GROUP',
        isValid: true,
        resolvedTiles: resolved,
        points: this.calcPoints(resolved, strategy)
      };
    }

    const numbers = Array.from(new Set(nonJokers.map(t => t.number)));
    if (numbers.length > 1) {
      return {
        meldId: meld.id,
        meldType: 'GROUP',
        isValid: false,
        errorMessage: `Les tuiles d'un groupe doivent avoir la même valeur faciale`,
        resolvedTiles: [],
        points: 0
      };
    }

    const groupNumber = numbers[0];
    const usedColors = nonJokers.map(t => t.color!);
    if (new Set(usedColors).size !== usedColors.length) {
      return {
        meldId: meld.id,
        meldType: 'GROUP',
        isValid: false,
        errorMessage: `Un groupe ne peut pas contenir deux tuiles de la même couleur`,
        resolvedTiles: [],
        points: 0
      };
    }

    const unusedColors = allColors.filter(c => !usedColors.includes(c));
    const resolvedTiles = tiles.map(t => {
      if (t.isJoker) {
        return {
          id: t.id,
          color: unusedColors.shift() ?? 'BLACK',
          number: groupNumber,
          isJoker: true
        };
      }
      return t;
    });

    return {
      meldId: meld.id,
      meldType: 'GROUP',
      isValid: true,
      resolvedTiles,
      points: this.calcPoints(resolvedTiles, strategy)
    };
  }

  static validateRun(
    meld: MeldSet,
    strategy: JokerPointStrategy = 'REPLACED_TILE_VALUE'
  ): MeldValidationResult {
    const tiles = meld.tiles;
    if (tiles.length < 3 || tiles.length > 13) {
      return {
        meldId: meld.id,
        meldType: 'RUN',
        isValid: false,
        errorMessage: `Une série doit contenir entre 3 et 13 tuiles`,
        resolvedTiles: [],
        points: 0
      };
    }

    const nonJokers = tiles.filter(t => !t.isJoker);
    const jokerCount = tiles.filter(t => t.isJoker).length;

    const colors = Array.from(new Set(nonJokers.map(t => t.color!)));
    if (colors.length > 1) {
      return {
        meldId: meld.id,
        meldType: 'RUN',
        isValid: false,
        errorMessage: `Toutes les tuiles d'une série doivent être de la même couleur`,
        resolvedTiles: [],
        points: 0
      };
    }

    const runColor: TileColor = colors[0] ?? 'RED';
    const nonJokerNums = nonJokers.map(t => t.number);
    if (new Set(nonJokerNums).size !== nonJokerNums.length) {
      return {
        meldId: meld.id,
        meldType: 'RUN',
        isValid: false,
        errorMessage: `Une série ne peut pas contenir de doublons de numéros`,
        resolvedTiles: [],
        points: 0
      };
    }

    // Try sequential resolution first
    const sequential = this.trySequential(tiles, runColor);
    if (sequential) {
      return {
        meldId: meld.id,
        meldType: 'RUN',
        isValid: true,
        resolvedTiles: sequential,
        points: this.calcPoints(sequential, strategy)
      };
    }

    // The numbered tiles must be laid out in ascending order (Jokers stay flexible):
    // a shuffled run such as 5-3-4 is not a valid run on the table.
    const sorted = [...nonJokerNums].sort((a, b) => a - b);
    if (nonJokerNums.some((n, i) => n !== sorted[i])) {
      return {
        meldId: meld.id,
        meldType: 'RUN',
        isValid: false,
        errorMessage: `Les tuiles de la série ne sont pas dans l'ordre croissant`,
        resolvedTiles: [],
        points: 0
      };
    }
    const L = tiles.length;

    if (sorted.length === 0) {
      const resolved = tiles.map((t, idx) => ({
        id: t.id,
        color: runColor,
        number: idx + 1,
        isJoker: true
      }));
      return {
        meldId: meld.id,
        meldType: 'RUN',
        isValid: true,
        resolvedTiles: resolved,
        points: this.calcPoints(resolved, strategy)
      };
    }

    const minNum = sorted[0];
    const maxNum = sorted[sorted.length - 1];
    const internalGaps = (maxNum - minNum + 1) - sorted.length;

    if (internalGaps > jokerCount) {
      return {
        meldId: meld.id,
        meldType: 'RUN',
        isValid: false,
        errorMessage: `Écart de numéros trop grand pour les Jokers disponibles`,
        resolvedTiles: [],
        points: 0
      };
    }

    const minStart = Math.max(1, maxNum - L + 1);
    const maxStart = Math.min(minNum, 14 - L);

    if (minStart > maxStart) {
      return {
        meldId: meld.id,
        meldType: 'RUN',
        isValid: false,
        errorMessage: `Impossible de former une suite continue dans [1..13] sans wrap-around`,
        resolvedTiles: [],
        points: 0
      };
    }

    // Resolve run starting at minStart
    const nonJokerMap = new Map<number, Tile>(nonJokers.map(t => [t.number, t]));
    const jokers = tiles.filter(t => t.isJoker);
    const resolved: Tile[] = [];

    for (let num = minStart; num < minStart + L; num++) {
      const existing = nonJokerMap.get(num);
      if (existing) {
        resolved.push(existing);
      } else {
        const j = jokers.shift()!;
        resolved.push({ id: j.id, color: runColor, number: num, isJoker: true });
      }
    }

    return {
      meldId: meld.id,
      meldType: 'RUN',
      isValid: true,
      resolvedTiles: resolved,
      points: this.calcPoints(resolved, strategy)
    };
  }

  private static trySequential(tiles: Tile[], runColor: TileColor): Tile[] | null {
    const L = tiles.length;
    const anchorIdx = tiles.findIndex(t => !t.isJoker);
    if (anchorIdx === -1) return null;

    const startNum = tiles[anchorIdx].number - anchorIdx;
    if (startNum < 1 || (startNum + L - 1) > 13) return null;

    const resolved: Tile[] = [];
    for (let i = 0; i < L; i++) {
      const exp = startNum + i;
      const t = tiles[i];
      if (t.isJoker) {
        resolved.push({ id: t.id, color: runColor, number: exp, isJoker: true });
      } else {
        if (t.number !== exp || t.color !== runColor) return null;
        resolved.push(t);
      }
    }
    return resolved;
  }

  private static calcPoints(tiles: Tile[], strategy: JokerPointStrategy): number {
    return tiles.reduce((acc, t) => {
      if (t.isJoker && strategy === 'ZERO_POINTS') return acc;
      return acc + t.number;
    }, 0);
  }
}

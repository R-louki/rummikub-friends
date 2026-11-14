import { Tile, TileColor } from '../models/types.js';

/**
 * Builds the standard 106-tile deck (2 sets x 4 colors x 1..13 + 2 Jokers).
 * @param shuffle false returns the deck in deterministic order (used by tests).
 */
export function createStandardDeck(shuffle: boolean = true): Tile[] {
  const colors: TileColor[] = ['BLACK', 'BLUE', 'RED', 'YELLOW'];
  const deck: Tile[] = [];

  for (let setIndex = 1; setIndex <= 2; setIndex++) {
    for (const color of colors) {
      for (let num = 1; num <= 13; num++) {
        deck.push({
          id: `${color}_${num}_${setIndex}`,
          color,
          number: num,
          isJoker: false
        });
      }
    }
  }

  deck.push({ id: 'JOKER_1', color: null, number: 0, isJoker: true });
  deck.push({ id: 'JOKER_2', color: null, number: 0, isJoker: true });

  return shuffle ? shuffleDeck(deck) : deck;
}

export function shuffleDeck<T>(array: T[]): T[] {
  const copy = [...array];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}

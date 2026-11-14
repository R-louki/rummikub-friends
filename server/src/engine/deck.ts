import { Tile, TileColor } from '../models/types.js';

export function createStandardDeck(): Tile[] {
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

  return shuffleDeck(deck);
}

export function shuffleDeck<T>(array: T[]): T[] {
  const copy = [...array];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}

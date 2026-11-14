export type TileColor = 'BLACK' | 'BLUE' | 'RED' | 'YELLOW';

export interface Tile {
  id: string;
  color: TileColor | null;
  number: number; // 1..13, 0 for Joker
  isJoker: boolean;
}

export type MeldType = 'RUN' | 'GROUP' | 'INVALID' | 'EMPTY';

export interface MeldSet {
  id: string;
  tiles: Tile[];
  gridRow: number;
  gridCol: number;
}

export interface BoardState {
  versionId: number;
  melds: MeldSet[];
  drawPileCount: number;
  activePlayerId: string;
  turnRemainingMillis: number;
  isGracePeriod: boolean;
  disconnectedPlayerId: string | null;
}

export interface PlayerRack {
  playerId: string;
  playerName: string;
  tiles: Tile[];
  hasMadeInitialMeld: boolean;
  initialMeldScore: number;
  isConnected: boolean;
}

export type JokerPointStrategy = 'REPLACED_TILE_VALUE' | 'ZERO_POINTS';
export type ValidationMode = 'STRICT_AUTOMATIC' | 'FREE_ARBITRATION';

export interface GameConfig {
  turnDurationSeconds: number;
  initialMeldThreshold: number;
  jokerAllowedInInitialMeld: boolean;
  jokerPointStrategy: JokerPointStrategy;
  validationMode: ValidationMode;
  penaltyTilesCount: number;
  maxPlayers: number;
  gracePeriodSeconds: number;
}

export const DEFAULT_GAME_CONFIG: GameConfig = {
  turnDurationSeconds: 60,
  initialMeldThreshold: 30,
  jokerAllowedInInitialMeld: true,
  jokerPointStrategy: 'REPLACED_TILE_VALUE',
  validationMode: 'STRICT_AUTOMATIC',
  penaltyTilesCount: 3,
  maxPlayers: 4,
  gracePeriodSeconds: 30
};

import { 
  BoardState, 
  GameConfig, 
  MeldSet, 
  PlayerRack, 
  Tile, 
  TileColor, 
  DEFAULT_GAME_CONFIG 
} from '../models/types.js';

export interface ServerPlayer {
  id: string;
  name: string;
  sessionToken: string;
  isHost: boolean;
  /** Computer player: no socket, plays by itself on its turn. */
  isBot?: boolean;
  isConnected: boolean;
  rack: PlayerRack;
  lastHeartbeatTime: number;
}

export interface StateDelta {
  fromVersionId: number;
  toVersionId: number;
  melds: MeldSet[];
  activePlayerId: string;
  turnRemainingMillis: number;
}

export type RoomStatus = 'LOBBY' | 'IN_GAME' | 'FINISHED';

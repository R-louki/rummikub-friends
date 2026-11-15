import { WebSocket } from 'ws';
import { GameConfig } from '../models/types.js';
import { Room } from './room.js';
import { RoomPersistence } from './room-store.js';

/** A client pings every 3 s; we tolerate several missed pings (app in background, weak signal). */
const HEARTBEAT_TIMEOUT_MS = 20_000;
const HEARTBEAT_CHECK_INTERVAL_MS = 5_000;
/** A lobby whose players are all offline is dropped after this delay. */
const EMPTY_ROOM_TTL_MS = 5 * 60_000;
/** A game in progress is kept longer: players may be away for a restart or a bad network. */
const EMPTY_GAME_TTL_MS = 20 * 60_000;
/** Saves are grouped: one write per table at most every this many ms. */
const SAVE_DEBOUNCE_MS = 400;

export class RoomManager {
  private rooms = new Map<string, Room>();
  private socketToRoom = new Map<WebSocket, { roomCode: string; playerId: string }>();
  private emptyRoomTimers = new Map<string, NodeJS.Timeout>();

  private saveTimers = new Map<string, NodeJS.Timeout>();

  constructor(private readonly store: RoomPersistence | null = null) {
    // Heartbeat supervisor (unref'd to not hold event loop)
    setInterval(() => this.superviseHeartbeats(), HEARTBEAT_CHECK_INTERVAL_MS).unref();
  }

  // ==========================================
  // PERSISTENCE (survive restarts / redeploys)
  // ==========================================

  private watch(room: Room): void {
    room.onChange = () => this.scheduleSave(room);
  }

  private scheduleSave(room: Room): void {
    if (!this.store?.enabled) return;
    if (this.saveTimers.has(room.roomCode)) return;
    const timer = setTimeout(() => {
      this.saveTimers.delete(room.roomCode);
      void this.saveNow(room);
    }, SAVE_DEBOUNCE_MS);
    timer.unref?.();
    this.saveTimers.set(room.roomCode, timer);
  }

  private async saveNow(room: Room): Promise<void> {
    if (!this.store?.enabled) return;
    // Lobbies are cheap to recreate; only games (running or between rounds) are worth saving.
    if (room.status === 'LOBBY' || room.isEmpty || !this.rooms.has(room.roomCode)) return;
    try {
      await this.store.save(room.roomCode, room.toSnapshot());
    } catch (err: any) {
      console.error(`[RoomManager] Sauvegarde du salon ${room.roomCode} impossible: ${err?.message}`);
    }
  }

  /** Writes every table right now (called when the server is asked to stop). */
  async flushAll(): Promise<void> {
    for (const timer of this.saveTimers.values()) clearTimeout(timer);
    this.saveTimers.clear();
    await Promise.all(Array.from(this.rooms.values()).map(r => this.saveNow(r)));
  }

  /** Loads the tables saved before the last stop. Must finish before the server accepts connections. */
  async restore(): Promise<number> {
    if (!this.store?.enabled) return 0;
    let restored = 0;
    try {
      const rows = await this.store.loadAll();
      for (const row of rows) {
        try {
          const room = Room.fromSnapshot(row.state);
          if (room.isEmpty || room.status === 'LOBBY') continue;
          this.rooms.set(room.roomCode, room);
          this.watch(room);
          this.scheduleEmptyRoomDeletion(room, EMPTY_GAME_TTL_MS);
          restored++;
        } catch (err: any) {
          console.error(`[RoomManager] Salon ${row.room_code} illisible, ignoré: ${err?.message}`);
        }
      }
    } catch (err: any) {
      console.error(`[RoomManager] Restauration impossible: ${err?.message}`);
    }
    console.log(`[RoomManager] ${restored} salon(s) restauré(s)`);
    return restored;
  }

  get roomCount(): number {
    return this.rooms.size;
  }

  getRoom(roomCode: string): Room | undefined {
    return this.rooms.get(roomCode.toUpperCase());
  }

  createRoom(hostName: string, socket: WebSocket, config?: GameConfig): { room: Room; playerId: string; sessionToken: string } {
    const roomCode = this.generateRoomCode();
    const botCount = Math.max(0, Math.min(3, Math.floor(config?.botCount ?? 0)));
    // Against the computer the table is exactly the human + the bots: nobody else can join.
    const effectiveConfig = botCount > 0 && config ? { ...config, botCount, maxPlayers: botCount + 1 } : config;
    const room = new Room(roomCode, effectiveConfig);
    this.rooms.set(roomCode, room);
    this.watch(room);

    const playerId = 'P_' + Math.random().toString(36).substring(2, 9);
    const { sessionToken } = room.addPlayer(playerId, hostName, socket);
    if (botCount > 0) room.addBots(botCount);

    this.socketToRoom.set(socket, { roomCode, playerId });
    console.log(`[RoomManager] Salon créé: ${roomCode} par ${hostName} (${playerId})`);

    return { room, playerId, sessionToken };
  }

  joinRoom(roomCode: string, playerName: string, socket: WebSocket): { room: Room; playerId: string; sessionToken: string } | null {
    const room = this.rooms.get((roomCode ?? '').toUpperCase());
    if (!room || !room.isJoinable) {
      return null;
    }

    const playerId = 'P_' + Math.random().toString(36).substring(2, 9);
    const { sessionToken } = room.addPlayer(playerId, playerName, socket);

    this.socketToRoom.set(socket, { roomCode: room.roomCode, playerId });
    this.cancelEmptyRoomTimer(room.roomCode);
    console.log(`[RoomManager] Joueur ${playerName} (${playerId}) a rejoint le salon ${room.roomCode}`);

    return { room, playerId, sessionToken };
  }

  reconnect(
    roomCode: string,
    playerId: string,
    sessionToken: string,
    lastKnownVersionId: number,
    socket: WebSocket
  ): { room: Room; success: boolean } | null {
    const room = this.rooms.get((roomCode ?? '').toUpperCase());
    if (!room) return null;

    const ok = room.reconnectPlayer(playerId, sessionToken, lastKnownVersionId, socket);
    if (ok) {
      // Any older socket of this player is stale: forget it first so its (late) close event
      // can never be mistaken for a disconnection of the player who is already back.
      for (const [other, entry] of Array.from(this.socketToRoom.entries())) {
        if (other !== socket && entry.playerId === playerId && entry.roomCode === room.roomCode) {
          this.socketToRoom.delete(other);
          try { other.terminate(); } catch { /* already closed */ }
        }
      }
      this.socketToRoom.set(socket, { roomCode: room.roomCode, playerId });
      this.cancelEmptyRoomTimer(room.roomCode);
    }
    return { room, success: ok };
  }

  /** Socket closed or errored. */
  handleSocketDisconnect(socket: WebSocket): void {
    const entry = this.socketToRoom.get(socket);
    if (!entry) return;

    this.socketToRoom.delete(socket);
    const room = this.rooms.get(entry.roomCode);
    if (room) {
      // Only the player's CURRENT socket counts: a stale socket closing says nothing about him.
      const current = room.sockets.get(entry.playerId);
      if (current && current !== socket) return;
      room.handleDisconnect(entry.playerId);
      this.checkRoomLifecycle(room);
    }
  }

  /** Explicit leave request from the client. */
  handleLeave(socket: WebSocket): void {
    const entry = this.socketToRoom.get(socket);
    if (!entry) return;

    this.socketToRoom.delete(socket);
    const room = this.rooms.get(entry.roomCode);
    if (room) {
      room.handleLeave(entry.playerId);
      this.checkRoomLifecycle(room);
    }
  }

  handleClientHeartbeat(socket: WebSocket, clientTimestamp: number, stateVersionId: number): void {
    const entry = this.socketToRoom.get(socket);
    if (!entry) return;

    const room = this.rooms.get(entry.roomCode);
    if (!room) return;

    const player = room.players.get(entry.playerId);
    if (player) {
      player.lastHeartbeatTime = Date.now();
      // Reply Pong
      if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({
          type: 'pong',
          clientTimestamp,
          serverTimestamp: Date.now(),
          serverStateVersionId: room.boardState.versionId
        }));
      }
    }
  }

  getRoomBySocket(socket: WebSocket): { room: Room; playerId: string } | null {
    const entry = this.socketToRoom.get(socket);
    if (!entry) return null;
    const room = this.rooms.get(entry.roomCode);
    if (!room) return null;
    return { room, playerId: entry.playerId };
  }

  private superviseHeartbeats(): void {
    const now = Date.now();
    for (const [code, room] of this.rooms) {
      for (const [pid, player] of room.players) {
        if (!player.isBot && player.isConnected && (now - player.lastHeartbeatTime) > HEARTBEAT_TIMEOUT_MS) {
          console.warn(`[RoomManager] Heartbeat timeout pour ${player.name} (${pid}) dans le salon ${code}`);
          const socket = room.sockets.get(pid);
          if (socket) {
            // Forget the socket first so its 'close' event does not trigger a second disconnect.
            this.socketToRoom.delete(socket);
            socket.terminate();
          }
          room.handleDisconnect(pid);
          this.checkRoomLifecycle(room);
        }
      }
    }
  }

  /** Drops empty rooms right away and schedules deletion of rooms where everyone is offline. */
  private checkRoomLifecycle(room: Room): void {
    if (room.isEmpty) {
      this.deleteRoom(room.roomCode, 'vide');
      return;
    }
    if (room.allDisconnected) {
      this.scheduleEmptyRoomDeletion(room, room.status === 'LOBBY' ? EMPTY_ROOM_TTL_MS : EMPTY_GAME_TTL_MS);
    }
  }

  private scheduleEmptyRoomDeletion(room: Room, ttlMs: number): void {
    if (this.emptyRoomTimers.has(room.roomCode)) return;
    const timer = setTimeout(() => {
      const current = this.rooms.get(room.roomCode);
      if (current && current.allDisconnected) {
        this.deleteRoom(room.roomCode, `tous les joueurs hors-ligne depuis ${Math.round(ttlMs / 60000)} min`);
      }
      this.emptyRoomTimers.delete(room.roomCode);
    }, ttlMs);
    timer.unref?.();
    this.emptyRoomTimers.set(room.roomCode, timer);
  }

  private cancelEmptyRoomTimer(roomCode: string): void {
    const timer = this.emptyRoomTimers.get(roomCode);
    if (timer) {
      clearTimeout(timer);
      this.emptyRoomTimers.delete(roomCode);
    }
  }

  private deleteRoom(roomCode: string, why: string): void {
    const room = this.rooms.get(roomCode);
    if (!room) return;
    room.dispose();
    this.rooms.delete(roomCode);
    this.cancelEmptyRoomTimer(roomCode);
    const pending = this.saveTimers.get(roomCode);
    if (pending) { clearTimeout(pending); this.saveTimers.delete(roomCode); }
    if (this.store?.enabled) {
      this.store.remove(roomCode).catch((err: any) => console.error(`[RoomManager] Suppression distante impossible: ${err?.message}`));
    }
    console.log(`[RoomManager] Salon ${roomCode} supprimé (${why}).`);
  }

  private generateRoomCode(): string {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let code = '';
    do {
      code = '';
      for (let i = 0; i < 6; i++) {
        code += chars.charAt(Math.floor(Math.random() * chars.length));
      }
    } while (this.rooms.has(code));
    return code;
  }
}

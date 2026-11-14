import { WebSocket } from 'ws';
import { GameConfig } from '../models/types.js';
import { Room } from './room.js';

export class RoomManager {
  private rooms = new Map<string, Room>();
  private socketToRoom = new Map<WebSocket, { roomCode: string; playerId: string }>();

  constructor() {
    // Heartbeat supervisor every 3 seconds (unref'd to not hold event loop)
    setInterval(() => this.superviseHeartbeats(), 3000).unref();
  }

  createRoom(hostName: string, socket: WebSocket, config?: GameConfig): { room: Room; playerId: string; sessionToken: string } {
    const roomCode = this.generateRoomCode();
    const room = new Room(roomCode, config);
    this.rooms.set(roomCode, room);

    const playerId = 'P_' + Math.random().toString(36).substring(2, 9);
    const { sessionToken } = room.addPlayer(playerId, hostName, socket);

    this.socketToRoom.set(socket, { roomCode, playerId });
    console.log(`[RoomManager] Salon créé: ${roomCode} par ${hostName} (${playerId})`);

    return { room, playerId, sessionToken };
  }

  joinRoom(roomCode: string, playerName: string, socket: WebSocket): { room: Room; playerId: string; sessionToken: string } | null {
    const room = this.rooms.get(roomCode.toUpperCase());
    if (!room || room.status !== 'LOBBY' || room.players.size >= room.config.maxPlayers) {
      return null;
    }

    const playerId = 'P_' + Math.random().toString(36).substring(2, 9);
    const { sessionToken } = room.addPlayer(playerId, playerName, socket);

    this.socketToRoom.set(socket, { roomCode: room.roomCode, playerId });
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
    const room = this.rooms.get(roomCode.toUpperCase());
    if (!room) return null;

    const ok = room.reconnectPlayer(playerId, sessionToken, lastKnownVersionId, socket);
    if (ok) {
      this.socketToRoom.set(socket, { roomCode: room.roomCode, playerId });
    }
    return { room, success: ok };
  }

  handleSocketDisconnect(socket: WebSocket): void {
    const entry = this.socketToRoom.get(socket);
    if (!entry) return;

    this.socketToRoom.delete(socket);
    const room = this.rooms.get(entry.roomCode);
    if (room) {
      room.handleDisconnect(entry.playerId);

      // Clean up room if all players are disconnected and room has been empty for > 5 min
      const allDisconnected = Array.from(room.players.values()).every(p => !p.isConnected);
      if (allDisconnected && room.status === 'LOBBY') {
        this.rooms.delete(entry.roomCode);
        console.log(`[RoomManager] Salon ${entry.roomCode} supprimé (vide).`);
      }
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
        if (player.isConnected && (now - player.lastHeartbeatTime) > 6000) {
          console.warn(`[RoomManager] Heartbeat timeout pour ${player.name} (${pid}) dans le salon ${code}`);
          const socket = room.sockets.get(pid);
          if (socket) {
            socket.terminate();
          }
          room.handleDisconnect(pid);
        }
      }
    }
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

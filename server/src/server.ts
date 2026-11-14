import { WebSocketServer, WebSocket } from 'ws';
import http from 'node:http';
import { RoomManager } from './network/room-manager.js';

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 8080;

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ status: 'UP', service: 'Rummikub Authoritative Real-Time Server' }));
});

const wss = new WebSocketServer({ server });
const roomManager = new RoomManager();

wss.on('connection', (ws: WebSocket) => {
  console.log('[WebSocket] Nouvelle connexion entrante');

  ws.on('message', (data: Buffer | string) => {
    try {
      const packet = JSON.parse(data.toString());
      handlePacket(ws, packet);
    } catch (err: any) {
      console.error('[WebSocket] Erreur parsing paquet JSON:', err.message);
      ws.send(JSON.stringify({ type: 'error', code: 'INVALID_JSON', message: err.message }));
    }
  });

  ws.on('close', () => {
    roomManager.handleSocketDisconnect(ws);
  });

  ws.on('error', (err) => {
    console.error('[WebSocket] Erreur socket:', err.message);
    roomManager.handleSocketDisconnect(ws);
  });
});

function handlePacket(ws: WebSocket, packet: any): void {
  switch (packet.type) {
    case 'ping': {
      roomManager.handleClientHeartbeat(ws, packet.clientTimestamp, packet.stateVersionId);
      break;
    }

    case 'create_room_req': {
      const { room, playerId, sessionToken } = roomManager.createRoom(
        packet.hostName || 'Hôte',
        ws,
        packet.config
      );
      ws.send(JSON.stringify({
        type: 'room_joined_res',
        roomCode: room.roomCode,
        playerId,
        sessionToken,
        isHost: true,
        config: room.config,
        players: room.getPlayerSummaries()
      }));
      break;
    }

    case 'join_room_req': {
      const result = roomManager.joinRoom(packet.roomCode, packet.playerName, ws);
      if (!result) {
        ws.send(JSON.stringify({
          type: 'error',
          code: 'ROOM_NOT_FOUND_OR_FULL',
          message: `Impossible de rejoindre le salon ${packet.roomCode}. Salon introuvable, déjà plein ou en cours.`
        }));
        return;
      }
      const { room, playerId, sessionToken } = result;
      // Send response to joining player
      ws.send(JSON.stringify({
        type: 'room_joined_res',
        roomCode: room.roomCode,
        playerId,
        sessionToken,
        isHost: false,
        config: room.config,
        players: room.getPlayerSummaries()
      }));
      // Broadcast player joined to others
      const joinedPlayer = room.players.get(playerId)!;
      room.broadcast({
        type: 'player_joined_evt',
        player: {
          id: joinedPlayer.id,
          name: joinedPlayer.name,
          isHost: false,
          isConnected: true,
          tileCount: 0,
          hasMadeInitialMeld: false,
          isSpeaking: false,
          isMuted: false
        }
      }, playerId);
      break;
    }

    case 'reconnect_req': {
      const result = roomManager.reconnect(
        packet.roomCode,
        packet.playerId,
        packet.sessionToken,
        packet.lastKnownVersionId,
        ws
      );
      if (!result || !result.success) {
        ws.send(JSON.stringify({
          type: 'error',
          code: 'RECONNECT_FAILED',
          message: "Session expirée ou code de salon invalide. Veuillez rejoindre à nouveau."
        }));
      }
      break;
    }

    case 'start_game_req': {
      const ctx = roomManager.getRoomBySocket(ws);
      if (!ctx) return;
      const player = ctx.room.players.get(ctx.playerId);
      if (!player || !player.isHost) {
        ws.send(JSON.stringify({ type: 'error', code: 'UNAUTHORIZED', message: "Seul l'hôte peut lancer la partie." }));
        return;
      }
      ctx.room.startGame();
      break;
    }

    case 'update_config_req': {
      const ctx = roomManager.getRoomBySocket(ws);
      if (!ctx) return;
      const player = ctx.room.players.get(ctx.playerId);
      if (player && player.isHost && ctx.room.status === 'LOBBY') {
        ctx.room.config = { ...ctx.room.config, ...packet.config };
        ctx.room.broadcast({ type: 'config_updated_evt', config: ctx.room.config });
      }
      break;
    }

    case 'tile_dragged_evt': {
      const ctx = roomManager.getRoomBySocket(ws);
      if (ctx && ctx.room.boardState.activePlayerId === ctx.playerId) {
        // Broadcast movement to all other players in room
        ctx.room.broadcast({
          type: 'tile_dragged_evt',
          playerId: ctx.playerId,
          tileId: packet.tileId,
          posX: packet.posX,
          posY: packet.posY
        }, ctx.playerId);
      }
      break;
    }

    case 'set_modified_evt': {
      const ctx = roomManager.getRoomBySocket(ws);
      if (ctx && ctx.room.boardState.activePlayerId === ctx.playerId) {
        ctx.room.broadcast({
          type: 'set_modified_evt',
          playerId: ctx.playerId,
          intermediateMelds: packet.intermediateMelds
        }, ctx.playerId);
      }
      break;
    }

    case 'end_turn_req': {
      const ctx = roomManager.getRoomBySocket(ws);
      if (ctx) {
        ctx.room.handleEndTurn(ctx.playerId, packet.proposedMelds, packet.remainingRackTileIds);
      }
      break;
    }

    case 'draw_tile_req': {
      const ctx = roomManager.getRoomBySocket(ws);
      if (ctx) {
        ctx.room.handleDrawTile(ctx.playerId);
      }
      break;
    }

    case 'voice_signal': {
      const ctx = roomManager.getRoomBySocket(ws);
      if (ctx) {
        if (packet.toPlayerId) {
          ctx.room.sendTo(packet.toPlayerId, packet);
        } else {
          ctx.room.broadcast(packet, ctx.playerId);
        }
      }
      break;
    }

    default:
      console.warn('[WebSocket] Paquet non reconnu:', packet.type);
  }
}

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[Rummikub Server] Serveur WebSocket autoritaire démarré sur le port ${PORT}`);
});

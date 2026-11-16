import { WebSocket } from 'ws';
import { RoomManager } from './room-manager.js';
import { AccountBackend, AccountSessions } from './accounts.js';

export interface PacketHandlerOptions {
  /** When true, only logged-in players with a pseudo can create or join tables. */
  requireAuth?: boolean;
}

const send = (ws: WebSocket, packet: unknown) => {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(packet));
};

/**
 * Routes every message a phone sends to the game logic.
 * Accounts are optional: an anonymous socket keeps working as before unless requireAuth is on.
 */
export function createPacketHandler(
  roomManager: RoomManager,
  backend: AccountBackend,
  sessions: AccountSessions,
  options: PacketHandlerOptions = {}
) {
  const requireAuth = !!options.requireAuth && backend.enabled;

  // Quick reactions (emojis) between players: whitelisted and rate-limited
  const REACTIONS = ['👍', '😂', '😮', '😡', '👏', '🔥', '🤔', '😭'];
  const lastReactionAt = new Map<string, number>();

  // An invitation sent while the friend's app is closed is not lost: it waits here and is
  // delivered as soon as that friend logs back in (one pending invite per recipient, latest wins).
  const INVITE_TTL_MS = 2 * 60_000;
  const pendingInvites = new Map<string, { roomCode: string; fromUserId: string; fromPseudo: string; expiresAt: number }>();

  /** The name shown at the table: the account pseudo when logged in, the typed name otherwise. */
  const identityFor = (ws: WebSocket, typedName: string | undefined, fallback: string) => {
    const account = sessions.accountOf(ws);
    return {
      name: account?.pseudo ?? (typedName || fallback),
      userId: account?.userId,
      avatar: account?.avatar ?? null
    };
  };

  const authRequired = (ws: WebSocket): boolean => {
    if (!requireAuth) return false;
    const account = sessions.accountOf(ws);
    if (account?.pseudo) return false;
    send(ws, {
      type: 'error',
      code: account ? 'PSEUDO_REQUIRED' : 'AUTH_REQUIRED',
      message: account ? 'Choisissez un pseudo pour jouer.' : 'Connectez-vous avec votre compte Google pour jouer.'
    });
    return true;
  };

  async function handleAuth(ws: WebSocket, packet: any): Promise<void> {
    if (!backend.enabled) {
      send(ws, { type: 'auth_res', ok: false, reason: 'ACCOUNTS_DISABLED' });
      return;
    }
    let account = null;
    try {
      account = await backend.verify(packet.accessToken);
    } catch (err: any) {
      console.error('[Auth] vérification impossible:', err?.message);
    }
    if (!account) {
      send(ws, { type: 'auth_res', ok: false, reason: 'INVALID_TOKEN' });
      return;
    }
    sessions.bind(ws, account);
    backend.touch(account.userId).catch((err: any) => console.error('[Auth] présence:', err?.message));
    send(ws, { type: 'auth_res', ok: true, userId: account.userId, pseudo: account.pseudo, avatar: account.avatar ?? null, needsPseudo: !account.pseudo });
    deliverPendingInvite(ws, account.userId);
  }

  /** Hands over an invite that arrived while this player was offline, if it is still valid. */
  function deliverPendingInvite(ws: WebSocket, userId: string): void {
    const pending = pendingInvites.get(userId);
    if (!pending) return;
    pendingInvites.delete(userId);
    if (pending.expiresAt < Date.now()) return;
    if (!roomManager.getRoom(pending.roomCode)?.isJoinable) return;
    send(ws, {
      type: 'room_invite_evt',
      roomCode: pending.roomCode,
      fromUserId: pending.fromUserId,
      fromPseudo: pending.fromPseudo
    });
  }

  async function handlePresence(ws: WebSocket): Promise<void> {
    const account = sessions.accountOf(ws);
    if (!account) { send(ws, { type: 'friends_presence_res', online: [] }); return; }
    let friends: string[] = [];
    try { friends = await backend.friendIds(account.userId); } catch { /* keep empty */ }
    send(ws, { type: 'friends_presence_res', online: friends.filter(id => sessions.isOnline(id)) });
  }

  async function handleInvite(ws: WebSocket, packet: any): Promise<void> {
    const account = sessions.accountOf(ws);
    const ctx = roomManager.getRoomBySocket(ws);
    const friendUserId = String(packet.friendUserId ?? '');
    if (!account || !ctx) {
      send(ws, { type: 'error', code: 'INVITE_FAILED', message: 'Créez une table avant d\'inviter un ami.' });
      return;
    }
    if (!ctx.room.isJoinable) {
      send(ws, { type: 'error', code: 'INVITE_FAILED', message: 'La table est pleine ou la partie a déjà commencé.' });
      return;
    }
    let friends: string[] = [];
    try { friends = await backend.friendIds(account.userId); } catch { /* none */ }
    if (!friends.includes(friendUserId)) {
      send(ws, { type: 'error', code: 'INVITE_FAILED', message: 'Cette personne n\'est pas dans vos amis.' });
      return;
    }
    const targets = sessions.socketsOf(friendUserId);
    for (const target of targets) {
      send(target, {
        type: 'room_invite_evt',
        roomCode: ctx.room.roomCode,
        fromUserId: account.userId,
        fromPseudo: account.pseudo ?? 'Un ami'
      });
    }
    const delivered = targets.length > 0;
    if (!delivered) {
      pendingInvites.set(friendUserId, {
        roomCode: ctx.room.roomCode,
        fromUserId: account.userId,
        fromPseudo: account.pseudo ?? 'Un ami',
        expiresAt: Date.now() + INVITE_TTL_MS
      });
    }
    send(ws, { type: 'invite_res', friendUserId, delivered, queued: !delivered });
  }

  function handle(ws: WebSocket, packet: any): void {
    switch (packet.type) {
      case 'ping': {
        roomManager.handleClientHeartbeat(ws, packet.clientTimestamp, packet.stateVersionId);
        break;
      }

      case 'auth_req': {
        void handleAuth(ws, packet);
        break;
      }

      case 'friends_presence_req': {
        void handlePresence(ws);
        break;
      }

      case 'invite_friend_req': {
        void handleInvite(ws, packet);
        break;
      }

      case 'create_room_req': {
        if (authRequired(ws)) return;
        const who = identityFor(ws, packet.hostName, 'Hôte');
        const { room, playerId, sessionToken } = roomManager.createRoom(who.name, ws, packet.config, who.userId, who.avatar);
        send(ws, {
          type: 'room_joined_res',
          roomCode: room.roomCode,
          playerId,
          sessionToken,
          isHost: true,
          config: room.config,
          players: room.getPlayerSummaries()
        });
        // Against the computer there is nobody to wait for: deal the cards right away.
        if (room.hasBots) {
          setTimeout(() => { if (room.status === 'LOBBY') room.startGame(); }, 800).unref?.();
        }
        break;
      }

      case 'join_room_req': {
        if (authRequired(ws)) return;
        const who = identityFor(ws, packet.playerName, 'Ami');
        const result = roomManager.joinRoom(packet.roomCode, who.name, ws, who.userId, who.avatar);
        if (!result) {
          send(ws, {
            type: 'error',
            code: 'ROOM_NOT_FOUND_OR_FULL',
            message: `Impossible de rejoindre le salon ${packet.roomCode}. Salon introuvable, déjà plein ou en cours.`
          });
          return;
        }
        const { room, playerId, sessionToken } = result;
        send(ws, {
          type: 'room_joined_res',
          roomCode: room.roomCode,
          playerId,
          sessionToken,
          isHost: false,
          config: room.config,
          players: room.getPlayerSummaries()
        });
        const joinedPlayer = room.players.get(playerId)!;
        room.broadcast({
          type: 'player_joined_evt',
          player: {
            id: joinedPlayer.id,
            name: joinedPlayer.name,
            isHost: false,
            isConnected: true,
            isBot: false,
            avatar: joinedPlayer.avatar ?? null,
            tileCount: 0,
            hasMadeInitialMeld: false,
            totalScore: 0,
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
          send(ws, {
            type: 'error',
            code: 'RECONNECT_FAILED',
            message: "Session expirée ou code de salon invalide. Veuillez rejoindre à nouveau."
          });
        }
        break;
      }

      case 'leave_room_req': {
        roomManager.handleLeave(ws);
        break;
      }

      case 'end_session_req': {
        const ctx = roomManager.getRoomBySocket(ws);
        if (!ctx) return;
        const player = ctx.room.players.get(ctx.playerId);
        if (!player || !player.isHost) {
          send(ws, { type: 'error', code: 'UNAUTHORIZED', message: "Seul l'hôte peut terminer la partie." });
          return;
        }
        if (ctx.room.status === 'IN_GAME') {
          send(ws, { type: 'error', code: 'CANNOT_END', message: "Terminez d'abord la manche en cours." });
          return;
        }
        ctx.room.endSession();
        roomManager.closeRoom(ctx.room.roomCode);
        break;
      }

      case 'start_game_req': {
        const ctx = roomManager.getRoomBySocket(ws);
        if (!ctx) return;
        const player = ctx.room.players.get(ctx.playerId);
        if (!player || !player.isHost) {
          send(ws, { type: 'error', code: 'UNAUTHORIZED', message: "Seul l'hôte peut lancer la partie." });
          return;
        }
        if (!ctx.room.startGame(packet.newSession === true)) {
          send(ws, {
            type: 'error',
            code: 'CANNOT_START',
            message: ctx.room.status === 'IN_GAME' ? 'La partie est déjà en cours.' : 'Il faut au moins 2 joueurs pour démarrer.'
          });
        }
        break;
      }

      case 'update_config_req': {
        const ctx = roomManager.getRoomBySocket(ws);
        if (!ctx) return;
        const player = ctx.room.players.get(ctx.playerId);
        if (player && player.isHost && ctx.room.status !== 'IN_GAME') {
          ctx.room.config = { ...ctx.room.config, ...packet.config };
          ctx.room.broadcast({ type: 'config_updated_evt', config: ctx.room.config });
        }
        break;
      }

      case 'tile_dragged_evt': {
        const ctx = roomManager.getRoomBySocket(ws);
        if (ctx && ctx.room.boardState.activePlayerId === ctx.playerId) {
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

      case 'reaction_req': {
        const ctx = roomManager.getRoomBySocket(ws);
        if (!ctx || !REACTIONS.includes(packet.emoji)) break;
        const key = `${ctx.room.roomCode}:${ctx.playerId}`;
        const now = Date.now();
        if (now - (lastReactionAt.get(key) ?? 0) < 600) break;
        lastReactionAt.set(key, now);
        ctx.room.broadcast({ type: 'reaction_evt', playerId: ctx.playerId, emoji: packet.emoji });
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

  /** Called when a socket closes or fails. */
  function onClose(ws: WebSocket): void {
    const wentOffline = sessions.unbind(ws);
    if (wentOffline) backend.touch(wentOffline.userId).catch((err: any) => console.error('[Auth] présence:', err?.message));
    roomManager.handleSocketDisconnect(ws);
  }

  return { handle, onClose };
}

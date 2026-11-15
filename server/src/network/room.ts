import { WebSocket } from 'ws';
import {
  BoardState,
  GameConfig,
  MeldSet,
  PlayerRack,
  Tile,
  DEFAULT_GAME_CONFIG
} from '../models/types.js';
import { ServerPlayer, StateDelta, RoomStatus } from '../models/server-models.js';
import { createStandardDeck } from '../engine/deck.js';
import { BoardValidator } from '../engine/board-validator.js';
import { InitialMeldEvaluator } from '../engine/initial-meld-evaluator.js';
import { computeBotMove } from '../engine/bot-player.js';
import crypto from 'node:crypto';

export type DeckFactory = () => Tile[];

export class Room {
  readonly roomCode: string;
  config: GameConfig;
  status: RoomStatus = 'LOBBY';

  readonly players = new Map<string, ServerPlayer>();
  readonly sockets = new Map<string, WebSocket>();

  private deck: Tile[] = [];
  private turnOrder: string[] = [];
  private activePlayerIndex: number = 0;
  private currentTurnNumber: number = 0;
  private readonly deckFactory: DeckFactory;

  /** Running total of each player's points over all the rounds played in this room. */
  private readonly totalScores = new Map<string, number>();
  private roundNumber = 0;
  /** Consecutive turns passed with an empty draw pile; a full round of passes blocks the game. */
  private consecutivePasses = 0;

  boardState: BoardState = {
    versionId: 0,
    melds: [],
    drawPileCount: 0,
    activePlayerId: '',
    turnRemainingMillis: 0,
    isGracePeriod: false,
    disconnectedPlayerId: null
  };

  private turnSnapshot: {
    board: BoardState;
    rack: PlayerRack;
    turnNumber: number;
  } | null = null;

  private deltaHistory: StateDelta[] = [];
  private readonly MAX_DELTA_HISTORY = 20;

  // Timers
  private turnRemainingMs: number = 0;
  private turnTickInterval: NodeJS.Timeout | null = null;

  private botTimer: NodeJS.Timeout | null = null;
  /** Delay before a computer player acts, so humans can follow what it does. */
  botThinkMs: [number, number] = [1200, 2600];

  private gracePeriodSecondsRemaining: number = 0;
  private gracePeriodTickInterval: NodeJS.Timeout | null = null;

  constructor(roomCode: string, initialConfig: GameConfig = DEFAULT_GAME_CONFIG, deckFactory: DeckFactory = () => createStandardDeck()) {
    this.roomCode = roomCode;
    this.config = { ...DEFAULT_GAME_CONFIG, ...initialConfig };
    this.deckFactory = deckFactory;
  }

  // ==========================================
  // PLAYER JOIN, LEAVE & RECONNECT
  // ==========================================

  /** A room accepts new players while no game is running (lobby or between two rounds). */
  get isJoinable(): boolean {
    return this.status !== 'IN_GAME' && this.players.size < this.config.maxPlayers;
  }

  get humanPlayers(): ServerPlayer[] {
    return Array.from(this.players.values()).filter(p => !p.isBot);
  }

  /** A room with no human left is dead: computer players alone never keep it alive. */
  get isEmpty(): boolean {
    return this.humanPlayers.length === 0;
  }

  get allDisconnected(): boolean {
    return this.humanPlayers.every(p => !p.isConnected);
  }

  get hasBots(): boolean {
    return Array.from(this.players.values()).some(p => p.isBot);
  }

  get hostId(): string | null {
    for (const p of this.players.values()) {
      if (p.isHost) return p.id;
    }
    return null;
  }

  addPlayer(id: string, name: string, socket: WebSocket): { player: ServerPlayer; sessionToken: string } {
    const isHost = this.hostId === null;
    const sessionToken = crypto.randomUUID();

    const player: ServerPlayer = {
      id,
      name,
      sessionToken,
      isHost,
      isConnected: true,
      rack: {
        playerId: id,
        playerName: name,
        tiles: [],
        hasMadeInitialMeld: false,
        initialMeldScore: 0,
        isConnected: true
      },
      lastHeartbeatTime: Date.now()
    };

    this.players.set(id, player);
    this.sockets.set(id, socket);

    return { player, sessionToken };
  }

  /** Adds computer players (names are fixed, ids start with BOT_). */
  addBots(count: number): void {
    const names = ['Sami (ordi)', 'Yasmine (ordi)', 'Karim (ordi)'];
    for (let i = 0; i < count && i < names.length; i++) {
      const id = 'BOT_' + (i + 1);
      this.players.set(id, {
        id,
        name: names[i],
        sessionToken: crypto.randomUUID(),
        isHost: false,
        isBot: true,
        isConnected: true,
        rack: {
          playerId: id,
          playerName: names[i],
          tiles: [],
          hasMadeInitialMeld: false,
          initialMeldScore: 0,
          isConnected: true
        },
        lastHeartbeatTime: Date.now()
      });
    }
  }

  /**
   * Socket lost (network cut, app killed). Idempotent: a second call for an already
   * disconnected player does nothing, so heartbeat supervision + socket close cannot
   * start two grace periods.
   */
  handleDisconnect(playerId: string): void {
    const player = this.players.get(playerId);
    if (!player || player.isBot || !player.isConnected) return;

    player.isConnected = false;
    player.rack.isConnected = false;
    this.sockets.delete(playerId);

    console.log(`[Room ${this.roomCode}] Joueur déconnecté: ${player.name} (${playerId})`);

    if (this.status !== 'IN_GAME') {
      // Not playing: the player simply leaves the room.
      this.removePlayer(playerId);
      return;
    }

    if (this.allDisconnected) {
      // Nobody left to play: freeze all timers until someone reconnects.
      this.clearTurnTimers();
      this.clearGraceTimers();
      this.boardState.isGracePeriod = false;
      this.boardState.disconnectedPlayerId = null;
      console.log(`[Room ${this.roomCode}] Tous les joueurs sont déconnectés, partie en pause.`);
      return;
    }

    const isActive = this.boardState.activePlayerId === playerId;
    if (isActive) {
      this.startGracePeriod(playerId);
    } else {
      this.broadcast({
        type: 'player_disconnected_evt',
        playerId,
        gracePeriodSeconds: 0
      });
    }
  }

  /**
   * Explicit "leave room" from the client. The player is removed for good.
   */
  handleLeave(playerId: string): void {
    const player = this.players.get(playerId);
    if (!player) return;

    console.log(`[Room ${this.roomCode}] Joueur quitte le salon: ${player.name} (${playerId})`);
    this.sockets.delete(playerId);
    player.isConnected = false;
    player.rack.isConnected = false;

    if (this.status !== 'IN_GAME') {
      this.removePlayer(playerId);
      return;
    }

    // Leaving mid-game: drop the player from the turn order and continue with the others.
    const wasActive = this.boardState.activePlayerId === playerId;
    if (wasActive) {
      this.clearTurnTimers();
      this.clearGraceTimers();
      this.boardState.isGracePeriod = false;
      this.boardState.disconnectedPlayerId = null;
      if (this.turnSnapshot) {
        this.boardState.melds = JSON.parse(JSON.stringify(this.turnSnapshot.board.melds));
      }
    }

    const removedIndex = this.turnOrder.indexOf(playerId);
    this.removePlayer(playerId);
    if (removedIndex >= 0) {
      this.turnOrder.splice(removedIndex, 1);
      if (this.turnOrder.length > 0) {
        if (wasActive) {
          // The next player now sits at removedIndex (wrapped).
          this.activePlayerIndex = removedIndex % this.turnOrder.length;
        } else if (removedIndex < this.activePlayerIndex) {
          this.activePlayerIndex -= 1;
        }
      }
    }

    if (this.turnOrder.length < 2) {
      this.finishGameByForfeit();
      return;
    }

    if (wasActive) {
      const nextPlayerId = this.turnOrder[this.activePlayerIndex];
      this.boardState.versionId++;
      this.boardState.activePlayerId = nextPlayerId;
      this.boardState.turnRemainingMillis = this.config.turnDurationSeconds * 1000;
      this.broadcast({
        type: 'turn_rollback_evt',
        playerId,
        reason: `${player.name} a quitté la partie. Le tour passe au joueur suivant.`,
        restoredBoardState: this.boardState,
        nextPlayerId,
        playerTilesCount: this.getPlayerTilesCount()
      });
      this.startPlayerTurn(nextPlayerId);
    }
  }

  /** Removes a player from the room and hands the host role over if needed. */
  private removePlayer(playerId: string): void {
    const player = this.players.get(playerId);
    if (!player) return;

    this.players.delete(playerId);
    this.sockets.delete(playerId);
    this.totalScores.delete(playerId);

    let newHostId: string | null = null;
    if (player.isHost) {
      const next = this.humanPlayers[0];
      if (next) {
        next.isHost = true;
        newHostId = next.id;
      }
    }

    this.broadcast({
      type: 'player_left_evt',
      playerId,
      newHostId
    });
  }

  reconnectPlayer(
    playerId: string,
    sessionToken: string,
    lastKnownVersionId: number,
    newSocket: WebSocket
  ): boolean {
    const player = this.players.get(playerId);
    if (!player || player.sessionToken !== sessionToken) {
      return false;
    }

    const wasPaused = this.status === 'IN_GAME' && this.allDisconnected;

    // Drop the previous (stale) socket, if any, so only one connection per player stays alive.
    const previous = this.sockets.get(playerId);
    if (previous && previous !== newSocket) {
      try { previous.terminate(); } catch { /* already closed */ }
    }

    player.isConnected = true;
    player.rack.isConnected = true;
    player.lastHeartbeatTime = Date.now();
    this.sockets.set(playerId, newSocket);

    console.log(`[Room ${this.roomCode}] Joueur reconnecté: ${player.name} (v${lastKnownVersionId} -> v${this.boardState.versionId})`);

    // If active player reconnected during grace period, resume turn
    if (this.boardState.isGracePeriod && this.boardState.disconnectedPlayerId === playerId) {
      this.cancelGracePeriod();
    } else if (wasPaused) {
      // Game was frozen with everybody offline: resume the active player's turn.
      this.resumeAfterPause();
    }

    // Always send the complete authoritative state: the rack may have changed while the
    // player was away (penalty tiles, rollback), which a meld-only delta cannot convey.
    this.boardState.turnRemainingMillis = Math.max(0, this.turnRemainingMs);
    this.sendTo(playerId, {
      type: 'full_snapshot_res',
      roomCode: this.roomCode,
      boardState: this.boardState,
      playerRack: player.rack,
      players: this.getPlayerSummaries(),
      config: this.config
    });

    // Broadcast reconnected event to all other players
    this.broadcast({
      type: 'player_reconnected_evt',
      playerId
    }, playerId);

    return true;
  }

  private resumeAfterPause(): void {
    const activeId = this.boardState.activePlayerId;
    const active = this.players.get(activeId);
    if (!active) return;
    if (active.isConnected) {
      this.resumeTurnTimer();
    } else {
      this.startGracePeriod(activeId);
    }
  }

  // ==========================================
  // GAME LIFECYCLE
  // ==========================================

  startGame(): boolean {
    if (this.status === 'IN_GAME') return false;
    if (this.players.size < 2) return false;

    this.clearTurnTimers();
    this.clearGraceTimers();
    this.status = 'IN_GAME';
    this.roundNumber++;
    this.consecutivePasses = 0;
    this.deltaHistory = [];
    this.currentTurnNumber = 0;
    this.turnSnapshot = null;

    // 1. Generate & shuffle 106-tile deck
    this.deck = this.deckFactory();

    // 2. Deal 14 tiles to each player
    this.turnOrder = Array.from(this.players.keys());
    for (const pid of this.turnOrder) {
      const p = this.players.get(pid)!;
      p.rack.tiles = this.deck.splice(0, 14);
      p.rack.hasMadeInitialMeld = false;
      p.rack.initialMeldScore = 0;
    }

    // 3. Initialize Board
    this.activePlayerIndex = 0;
    const firstPlayerId = this.turnOrder[0];
    this.boardState = {
      versionId: 1,
      melds: [],
      drawPileCount: this.deck.length,
      activePlayerId: firstPlayerId,
      turnRemainingMillis: this.config.turnDurationSeconds * 1000,
      isGracePeriod: false,
      disconnectedPlayerId: null
    };

    // 4. Send GameStarted to each player with their private rack
    for (const [pid, player] of this.players) {
      this.sendTo(pid, {
        type: 'game_started_evt',
        roomCode: this.roomCode,
        boardState: this.boardState,
        initialRack: player.rack,
        players: this.getPlayerSummaries()
      });
    }

    this.startPlayerTurn(firstPlayerId);
    return true;
  }

  private startPlayerTurn(playerId: string): void {
    this.clearTurnTimers();
    this.currentTurnNumber++;

    const player = this.players.get(playerId)!;
    this.boardState.activePlayerId = playerId;
    this.turnRemainingMs = this.config.turnDurationSeconds * 1000;
    this.boardState.turnRemainingMillis = this.turnRemainingMs;

    // Capture Authoritative Turn Snapshot
    this.turnSnapshot = {
      board: JSON.parse(JSON.stringify(this.boardState)),
      rack: JSON.parse(JSON.stringify(player.rack)),
      turnNumber: this.currentTurnNumber
    };

    if (!player.isConnected) {
      // The player is away: give them the grace period instead of a silent full turn.
      this.startGracePeriod(playerId);
      return;
    }

    this.resumeTurnTimer();
    if (player.isBot) this.scheduleBotTurn(playerId);
  }

  // ==========================================
  // COMPUTER PLAYERS
  // ==========================================

  private scheduleBotTurn(botId: string): void {
    this.clearBotTimer();
    const [min, max] = this.botThinkMs;
    const delay = min + Math.random() * Math.max(0, max - min);
    const turnAtSchedule = this.currentTurnNumber;
    this.botTimer = setTimeout(() => {
      this.botTimer = null;
      if (this.status !== 'IN_GAME' || this.boardState.activePlayerId !== botId) return;
      if (this.currentTurnNumber !== turnAtSchedule || this.boardState.isGracePeriod) return;
      this.playBotTurn(botId);
    }, delay);
    this.botTimer.unref?.();
  }

  private clearBotTimer(): void {
    if (this.botTimer) {
      clearTimeout(this.botTimer);
      this.botTimer = null;
    }
  }

  private playBotTurn(botId: string): void {
    const bot = this.players.get(botId);
    const snapshot = this.turnSnapshot;
    if (!bot || !snapshot) return;
    const turn = this.currentTurnNumber;

    let move = null;
    try {
      move = computeBotMove(bot.rack.tiles, this.boardState.melds, bot.rack.hasMadeInitialMeld, this.config);
    } catch (err: any) {
      console.error(`[Room ${this.roomCode}] Erreur IA ${botId}:`, err?.message);
    }

    if (move) {
      this.handleEndTurn(botId, move.melds, move.remainingRackTileIds);
      // The proposal was refused (should not happen): never leave the table waiting, draw instead.
      if (this.status === 'IN_GAME' && this.currentTurnNumber === turn && this.boardState.activePlayerId === botId) {
        this.handleDrawTile(botId);
      }
    } else {
      this.handleDrawTile(botId);
    }
  }

  private resumeTurnTimer(): void {
    this.clearTurnTimers();
    if (this.config.turnDurationSeconds <= 0 || this.turnRemainingMs <= 0) return;

    this.turnTickInterval = setInterval(() => {
      this.turnRemainingMs -= 1000;
      this.boardState.turnRemainingMillis = Math.max(0, this.turnRemainingMs);

      if (this.turnRemainingMs <= 0) {
        this.handleTurnTimeout(this.boardState.activePlayerId);
      }
    }, 1000);
    this.turnTickInterval.unref?.();
  }

  // ==========================================
  // TURN ACTIONS & VALIDATION
  // ==========================================

  handleEndTurn(
    playerId: string,
    proposedMelds: MeldSet[],
    remainingRackTileIds: string[]
  ): void {
    if (this.status !== 'IN_GAME') return;
    if (this.boardState.activePlayerId !== playerId) return;
    if (this.boardState.isGracePeriod) return;
    const player = this.players.get(playerId)!;
    const snapshot = this.turnSnapshot;
    if (!snapshot) return;

    const safeRemaining = Array.isArray(remainingRackTileIds) ? remainingRackTileIds : [];
    const safeMelds = Array.isArray(proposedMelds) ? proposedMelds : [];

    // Tiles played from the rack this turn
    const remainingSet = new Set(safeRemaining);
    const playedTiles = snapshot.rack.tiles.filter(t => !remainingSet.has(t.id));

    if (playedTiles.length === 0) {
      // Not a rule violation: just tell the player, the turn keeps running.
      this.sendTo(playerId, {
        type: 'error',
        code: 'NO_TILES_PLAYED',
        message: "Aucune tuile n'a été déposée sur la table. Posez au moins une tuile ou piochez pour passer."
      });
      return;
    }

    // Rebuild the proposed board from tiles the server knows (ignore client-side tile data)
    const rebuilt = this.rebuildProposedMelds(safeMelds, snapshot, playedTiles);
    if (!rebuilt.ok) {
      this.sendTo(playerId, {
        type: 'error',
        code: 'INVALID_PROPOSAL',
        message: rebuilt.reason
      });
      return;
    }

    const proposedBoard: BoardState = {
      ...this.boardState,
      melds: rebuilt.melds
    };

    // Validate board
    const boardValidation = BoardValidator.validateBoard(proposedBoard, this.config.jokerPointStrategy);
    if (!boardValidation.isValid) {
      this.executeTurnRollback(
        playerId,
        `Combinaisons non conformes : ${boardValidation.diagnostics.join('; ')}`,
        true
      );
      return;
    }

    // Free play (threshold 0): no first-meld restriction at all
    if (!player.rack.hasMadeInitialMeld && this.config.initialMeldThreshold <= 0) {
      player.rack.hasMadeInitialMeld = true;
    }

    // Validate Initial Meld if needed
    if (!player.rack.hasMadeInitialMeld) {
      const initialMeld = InitialMeldEvaluator.evaluate(
        snapshot.board,
        proposedBoard,
        snapshot.rack,
        this.config
      );

      if (!initialMeld.isValid) {
        this.executeTurnRollback(
          playerId,
          initialMeld.failureReason ?? "Première pose non conforme aux règles (30 points requis).",
          true
        );
        return;
      }

      player.rack.hasMadeInitialMeld = true;
      player.rack.initialMeldScore = initialMeld.score;
    }

    // Turn is Valid! Commit state
    this.consecutivePasses = 0;
    this.boardState.versionId++;
    this.boardState.melds = proposedBoard.melds;
    this.recordDelta(this.boardState.melds);

    // Update player rack
    player.rack.tiles = snapshot.rack.tiles.filter(t => remainingSet.has(t.id));

    // Check Win Condition (Rack is empty!)
    if (player.rack.tiles.length === 0) {
      this.finishGame(playerId);
      return;
    }

    // Advance to next player
    const nextPlayerId = this.advanceToNextPlayer();

    this.broadcast({
      type: 'turn_committed_evt',
      activePlayerId: playerId,
      nextPlayerId,
      boardState: this.boardState,
      playerTilesCount: this.getPlayerTilesCount()
    }, playerId);

    // Send updated rack to the player who just played
    this.sendTo(playerId, {
      type: 'turn_committed_evt',
      activePlayerId: playerId,
      nextPlayerId,
      boardState: this.boardState,
      updatedRack: player.rack,
      playerTilesCount: this.getPlayerTilesCount()
    });

    this.startPlayerTurn(nextPlayerId);
  }

  /**
   * Validates tile conservation and rebuilds melds using the server's own tile objects:
   * every tile on the proposed board must be either a tile that was already on the table
   * at the start of the turn or a tile from the player's starting rack, each used exactly once.
   */
  private rebuildProposedMelds(
    proposedMelds: MeldSet[],
    snapshot: { board: BoardState; rack: PlayerRack },
    playedTiles: Tile[]
  ): { ok: true; melds: MeldSet[] } | { ok: false; reason: string } {
    const known = new Map<string, Tile>();
    for (const m of snapshot.board.melds) for (const t of m.tiles) known.set(t.id, t);
    for (const t of snapshot.rack.tiles) known.set(t.id, t);

    const expected = new Set<string>();
    for (const m of snapshot.board.melds) for (const t of m.tiles) expected.add(t.id);
    for (const t of playedTiles) expected.add(t.id);

    const seen = new Set<string>();
    const melds: MeldSet[] = [];

    for (const m of proposedMelds) {
      if (!m || !Array.isArray(m.tiles)) continue;
      if (m.tiles.length === 0) continue;
      const tiles: Tile[] = [];
      for (const t of m.tiles) {
        const id = t?.id;
        if (typeof id !== 'string') return { ok: false, reason: 'Tuile sans identifiant.' };
        const canonical = known.get(id);
        if (!canonical) return { ok: false, reason: `Tuile inconnue sur la table (${id}).` };
        if (!expected.has(id)) return { ok: false, reason: `La tuile ${id} est encore sur votre chevalet.` };
        if (seen.has(id)) return { ok: false, reason: `La tuile ${id} apparaît deux fois.` };
        seen.add(id);
        tiles.push(canonical);
      }
      melds.push({
        id: typeof m.id === 'string' && m.id.length > 0 ? m.id : crypto.randomUUID(),
        tiles,
        gridRow: Number.isFinite(m.gridRow) ? m.gridRow : 0,
        gridCol: Number.isFinite(m.gridCol) ? m.gridCol : 0
      });
    }

    if (seen.size !== expected.size) {
      const missing = Array.from(expected).filter(id => !seen.has(id));
      return { ok: false, reason: `Des tuiles ont disparu de la table (${missing.length}).` };
    }

    return { ok: true, melds };
  }

  handleDrawTile(playerId: string): void {
    if (this.status !== 'IN_GAME') return;
    if (this.boardState.activePlayerId !== playerId) return;
    if (this.boardState.isGracePeriod) return;
    const player = this.players.get(playerId)!;

    // If player had modified the board during this turn before deciding to draw, rollback board
    if (this.turnSnapshot) {
      this.boardState.melds = JSON.parse(JSON.stringify(this.turnSnapshot.board.melds));
    }
    this.boardState.versionId++;

    let drawnTile: Tile | null = null;
    if (this.deck.length > 0) {
      drawnTile = this.deck.shift()!;
      player.rack.tiles.push(drawnTile);
      this.boardState.drawPileCount = this.deck.length;
      this.consecutivePasses = 0;
    } else {
      this.consecutivePasses++;
    }

    const nextPlayerId = this.advanceToNextPlayer();

    // Broadcast to room
    this.broadcast({
      type: 'tile_drawn_evt',
      playerId,
      newDrawPileCount: this.deck.length,
      nextPlayerId,
      playerTilesCount: this.getPlayerTilesCount()
    }, playerId);

    // Send private drawn tile payload to drawing player
    this.sendTo(playerId, {
      type: 'tile_drawn_evt',
      playerId,
      drawnTile,
      newDrawPileCount: this.deck.length,
      nextPlayerId,
      playerTilesCount: this.getPlayerTilesCount()
    });

    if (this.isBlocked()) {
      this.finishBlockedGame();
      return;
    }
    this.startPlayerTurn(nextPlayerId);
  }

  private handleTurnTimeout(playerId: string): void {
    console.log(`[Room ${this.roomCode}] Temps écoulé pour le joueur ${playerId}`);
    this.executeTurnRollback(
      playerId,
      "Temps écoulé ! Vos tuiles ont été remises sur votre chevalet et une pénalité a été appliquée.",
      true
    );
  }

  private executeTurnRollback(playerId: string, reason: string, applyPenalty: boolean): void {
    this.clearTurnTimers();
    const snapshot = this.turnSnapshot;
    const player = this.players.get(playerId);
    if (!player) return;

    const penaltyTiles: Tile[] = [];
    if (applyPenalty && this.config.penaltyTilesCount > 0 && this.deck.length > 0) {
      const count = Math.min(this.config.penaltyTilesCount, this.deck.length);
      penaltyTiles.push(...this.deck.splice(0, count));
      this.boardState.drawPileCount = this.deck.length;
    }

    // Restore pristine board from snapshot (only if it belongs to this player's turn)
    if (snapshot && snapshot.rack.playerId === playerId) {
      this.boardState.melds = JSON.parse(JSON.stringify(snapshot.board.melds));
      player.rack = JSON.parse(JSON.stringify(snapshot.rack));
      player.rack.isConnected = player.isConnected;
    }

    player.rack.tiles.push(...penaltyTiles);
    if (this.deck.length === 0 && penaltyTiles.length === 0) this.consecutivePasses++;
    this.boardState.versionId++;

    const nextPlayerId = this.advanceToNextPlayer();

    // Broadcast rollback to all players
    this.broadcast({
      type: 'turn_rollback_evt',
      playerId,
      reason,
      restoredBoardState: this.boardState,
      nextPlayerId,
      playerTilesCount: this.getPlayerTilesCount()
    }, playerId);

    // Send restored rack + penalty tiles to the player concerned
    this.sendTo(playerId, {
      type: 'turn_rollback_evt',
      playerId,
      reason,
      restoredBoardState: this.boardState,
      restoredRack: player.rack,
      penaltyTiles,
      nextPlayerId,
      playerTilesCount: this.getPlayerTilesCount()
    });

    if (this.isBlocked()) {
      this.finishBlockedGame();
      return;
    }
    this.startPlayerTurn(nextPlayerId);
  }

  /** Draw pile empty and every player has passed in a row: nobody can progress. */
  private isBlocked(): boolean {
    return this.deck.length === 0 && this.turnOrder.length > 0 && this.consecutivePasses >= this.turnOrder.length;
  }

  /**
   * Blocked game: the round ends and the player with the lowest rack total wins
   * (Joker = 30 points, ties go to the fewest tiles).
   */
  private finishBlockedGame(): void {
    const total = (p: ServerPlayer) => p.rack.tiles.reduce((s, t) => s + (t.isJoker ? 30 : t.number), 0);
    const candidates = this.turnOrder.map(id => this.players.get(id)!).filter(Boolean);
    candidates.sort((a, b) => total(a) - total(b) || a.rack.tiles.length - b.rack.tiles.length);
    this.finishGame(candidates[0].id);
  }

  private finishGame(winnerId: string): void {
    const winner = this.players.get(winnerId)!;
    this.status = 'FINISHED';
    this.clearTurnTimers();
    this.clearGraceTimers();
    this.turnSnapshot = null;
    this.boardState.isGracePeriod = false;
    this.boardState.disconnectedPlayerId = null;

    // Calcul officiel des scores Rummikub
    let totalLoserPenaltySum = 0;
    const scores: any[] = [];

    for (const [pid, p] of this.players) {
      if (pid === winnerId) continue;

      let playerPenalty = 0;
      let hadJoker = false;
      for (const t of p.rack.tiles) {
        if (t.isJoker) {
          playerPenalty += 30; // Pénalité Joker = 30 points !
          hadJoker = true;
        } else {
          playerPenalty += t.number;
        }
      }
      totalLoserPenaltySum += playerPenalty;
      scores.push({
        playerId: pid,
        playerName: p.name,
        isWinner: false,
        remainingTileCount: p.rack.tiles.length,
        scoreDelta: -playerPenalty,
        hadJokerPenalty: hadJoker
      });
      this.addToTotal(pid, -playerPenalty);
    }
    // A blocked game can be won with tiles left: the winner then gives up his own remaining points.
    const winnerOwn = winner.rack.tiles.reduce((acc, t) => acc + (t.isJoker ? 30 : t.number), 0);
    const winnerDelta = totalLoserPenaltySum - winnerOwn;
    this.addToTotal(winnerId, winnerDelta);

    // Le vainqueur marque la somme positive des pénalités adverses
    scores.push({
      playerId: winnerId,
      playerName: winner.name,
      isWinner: true,
      remainingTileCount: winner.rack.tiles.length,
      scoreDelta: winnerDelta,
      hadJokerPenalty: false
    });

    for (const sc of scores) sc.totalScore = this.totalScores.get(sc.playerId) ?? 0;

    this.broadcast({
      type: 'game_over_evt',
      roomCode: this.roomCode,
      winnerId,
      winnerName: winner.name,
      roundNumber: this.roundNumber,
      scores
    });
  }

  /** Everyone else left: the last player standing wins the round with 0 points. */
  private finishGameByForfeit(): void {
    this.status = 'FINISHED';
    this.clearTurnTimers();
    this.clearGraceTimers();
    this.turnSnapshot = null;
    this.boardState.isGracePeriod = false;
    this.boardState.disconnectedPlayerId = null;

    const remaining = this.turnOrder[0] ? this.players.get(this.turnOrder[0]) : undefined;
    if (!remaining) return;

    this.broadcast({
      type: 'game_over_evt',
      roomCode: this.roomCode,
      winnerId: remaining.id,
      winnerName: remaining.name,
      roundNumber: this.roundNumber,
      scores: [{
        playerId: remaining.id,
        playerName: remaining.name,
        isWinner: true,
        remainingTileCount: remaining.rack.tiles.length,
        scoreDelta: 0,
        totalScore: this.totalScores.get(remaining.id) ?? 0,
        hadJokerPenalty: false
      }]
    });
  }

  // ==========================================
  // GRACE PERIOD FOR RECONNECTION
  // ==========================================

  private startGracePeriod(playerId: string): void {
    // Idempotent: never run two grace countdowns at once.
    if (this.boardState.isGracePeriod && this.boardState.disconnectedPlayerId === playerId && this.gracePeriodTickInterval) {
      return;
    }
    this.clearTurnTimers();
    this.clearGraceTimers();

    this.boardState.isGracePeriod = true;
    this.boardState.disconnectedPlayerId = playerId;
    this.gracePeriodSecondsRemaining = this.config.gracePeriodSeconds;

    console.log(`[Room ${this.roomCode}] Début de la période de grâce (${this.gracePeriodSecondsRemaining}s) pour ${playerId}`);

    this.broadcast({
      type: 'player_disconnected_evt',
      playerId,
      gracePeriodSeconds: this.gracePeriodSecondsRemaining
    });

    this.gracePeriodTickInterval = setInterval(() => {
      this.gracePeriodSecondsRemaining--;
      if (this.gracePeriodSecondsRemaining <= 0) {
        this.handleGracePeriodExpired(playerId);
      }
    }, 1000);
    this.gracePeriodTickInterval.unref?.();
  }

  private cancelGracePeriod(): void {
    this.clearGraceTimers();
    this.boardState.isGracePeriod = false;
    this.boardState.disconnectedPlayerId = null;
    this.resumeTurnTimer();
  }

  private handleGracePeriodExpired(disconnectedPlayerId: string): void {
    console.log(`[Room ${this.roomCode}] Période de grâce expirée pour ${disconnectedPlayerId}`);
    this.clearGraceTimers();
    this.boardState.isGracePeriod = false;
    this.boardState.disconnectedPlayerId = null;

    this.broadcast({
      type: 'grace_period_expired_evt',
      disconnectedPlayerId,
      nextPlayerId: this.peekNextPlayerId()
    });

    // Auto-rollback active turn and advance
    this.executeTurnRollback(
      disconnectedPlayerId,
      "Délai de reconnexion dépassé. Le tour passe au joueur suivant.",
      true
    );
  }

  // ==========================================
  // HELPERS, DELTA SYNC & BROADCAST
  // ==========================================

  private peekNextPlayerId(): string {
    if (this.turnOrder.length === 0) return '';
    return this.turnOrder[(this.activePlayerIndex + 1) % this.turnOrder.length];
  }

  private advanceToNextPlayer(): string {
    const nextIndex = (this.activePlayerIndex + 1) % this.turnOrder.length;
    this.activePlayerIndex = nextIndex;
    const nextPlayerId = this.turnOrder[this.activePlayerIndex];
    // Reflect the hand-over in the board state BEFORE it is broadcast,
    // so clients receive a boardState whose activePlayerId is already the next player.
    this.boardState.activePlayerId = nextPlayerId;
    this.boardState.turnRemainingMillis = this.config.turnDurationSeconds * 1000;
    return nextPlayerId;
  }

  private recordDelta(melds: MeldSet[]): void {
    this.deltaHistory.push({
      fromVersionId: this.boardState.versionId - 1,
      toVersionId: this.boardState.versionId,
      melds: JSON.parse(JSON.stringify(melds)),
      activePlayerId: this.boardState.activePlayerId,
      turnRemainingMillis: this.turnRemainingMs
    });

    if (this.deltaHistory.length > this.MAX_DELTA_HISTORY) {
      this.deltaHistory.shift();
    }
  }

  private clearTurnTimers(): void {
    this.clearBotTimer();
    if (this.turnTickInterval) {
      clearInterval(this.turnTickInterval);
      this.turnTickInterval = null;
    }
  }

  private clearGraceTimers(): void {
    if (this.gracePeriodTickInterval) {
      clearInterval(this.gracePeriodTickInterval);
      this.gracePeriodTickInterval = null;
    }
  }

  private addToTotal(playerId: string, delta: number): void {
    this.totalScores.set(playerId, (this.totalScores.get(playerId) ?? 0) + delta);
  }

  /** Stops every timer; called by the RoomManager before dropping the room. */
  dispose(): void {
    this.clearTurnTimers();
    this.clearGraceTimers();
  }

  broadcast(packet: any, excludePlayerId?: string): void {
    const json = JSON.stringify(packet);
    for (const [pid, socket] of this.sockets) {
      if (pid !== excludePlayerId && socket.readyState === WebSocket.OPEN) {
        socket.send(json);
      }
    }
  }

  sendTo(playerId: string, packet: any): void {
    const socket = this.sockets.get(playerId);
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(packet));
    }
  }

  getPlayerSummaries(): any[] {
    return Array.from(this.players.values()).map(p => ({
      id: p.id,
      name: p.name,
      isHost: p.isHost,
      isConnected: p.isConnected,
      isBot: !!p.isBot,
      tileCount: p.rack.tiles.length,
      hasMadeInitialMeld: p.rack.hasMadeInitialMeld,
      totalScore: this.totalScores.get(p.id) ?? 0,
      isSpeaking: false,
      isMuted: false
    }));
  }

  getPlayerTilesCount(): Record<string, number> {
    const counts: Record<string, number> = {};
    for (const [pid, p] of this.players) {
      counts[pid] = p.rack.tiles.length;
    }
    return counts;
  }
}

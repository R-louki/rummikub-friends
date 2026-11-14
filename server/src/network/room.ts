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
import crypto from 'node:crypto';

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
  private turnTimer: NodeJS.Timeout | null = null;
  private turnRemainingMs: number = 0;
  private turnTickInterval: NodeJS.Timeout | null = null;

  private gracePeriodTimer: NodeJS.Timeout | null = null;
  private gracePeriodSecondsRemaining: number = 0;
  private gracePeriodTickInterval: NodeJS.Timeout | null = null;

  constructor(roomCode: string, initialConfig: GameConfig = DEFAULT_GAME_CONFIG) {
    this.roomCode = roomCode;
    this.config = { ...initialConfig };
  }

  // ==========================================
  // PLAYER JOIN, LEAVE & RECONNECT
  // ==========================================

  addPlayer(id: string, name: string, socket: WebSocket): { player: ServerPlayer; sessionToken: string } {
    const isHost = this.players.size === 0;
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

  handleDisconnect(playerId: string): void {
    const player = this.players.get(playerId);
    if (!player) return;

    player.isConnected = false;
    player.rack.isConnected = false;
    this.sockets.delete(playerId);

    console.log(`[Room ${this.roomCode}] Joueur déconnecté: ${player.name} (${playerId})`);

    if (this.status === 'IN_GAME') {
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
    } else {
      this.broadcast({
        type: 'player_left_evt',
        playerId
      });
    }
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

    player.isConnected = true;
    player.rack.isConnected = true;
    player.lastHeartbeatTime = Date.now();
    this.sockets.set(playerId, newSocket);

    console.log(`[Room ${this.roomCode}] Joueur reconnecté: ${player.name} (v${lastKnownVersionId} -> v${this.boardState.versionId})`);

    // If active player reconnected during grace period, resume turn
    if (this.boardState.isGracePeriod && this.boardState.disconnectedPlayerId === playerId) {
      this.cancelGracePeriod();
    }

    // Determine whether to send Delta or Full Snapshot
    const canDeltaSync = this.canProvideDeltaSync(lastKnownVersionId);
    if (canDeltaSync) {
      const deltas = this.getDeltasSince(lastKnownVersionId);
      const latestDelta = deltas[deltas.length - 1];
      this.sendTo(playerId, {
        type: 'sync_delta_res',
        baseVersionId: lastKnownVersionId,
        targetVersionId: this.boardState.versionId,
        meldModifications: latestDelta ? latestDelta.melds : this.boardState.melds,
        removedMeldIds: [],
        activePlayerId: this.boardState.activePlayerId,
        turnRemainingMillis: this.turnRemainingMs
      });
    } else {
      this.sendTo(playerId, {
        type: 'full_snapshot_res',
        roomCode: this.roomCode,
        boardState: this.boardState,
        playerRack: player.rack,
        players: this.getPlayerSummaries(),
        config: this.config
      });
    }

    // Broadcast reconnected event to all other players
    this.broadcast({
      type: 'player_reconnected_evt',
      playerId
    }, playerId);

    return true;
  }

  // ==========================================
  // GAME LIFECYCLE
  // ==========================================

  startGame(): boolean {
    if (this.players.size < 2) return false;
    this.status = 'IN_GAME';

    // 1. Generate & shuffle 106-tile deck
    this.deck = createStandardDeck();

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

    // If turnDurationSeconds > 0, start countdown
    if (this.config.turnDurationSeconds > 0) {
      this.turnTickInterval = setInterval(() => {
        this.turnRemainingMs -= 1000;
        this.boardState.turnRemainingMillis = Math.max(0, this.turnRemainingMs);

        if (this.turnRemainingMs <= 0) {
          this.handleTurnTimeout(playerId);
        }
      }, 1000);
      this.turnTickInterval.unref();
    }
  }

  // ==========================================
  // TURN ACTIONS & VALIDATION
  // ==========================================

  handleEndTurn(
    playerId: string,
    proposedMelds: MeldSet[],
    remainingRackTileIds: string[]
  ): void {
    if (this.boardState.activePlayerId !== playerId) return;
    const player = this.players.get(playerId)!;
    const snapshot = this.turnSnapshot;

    if (!snapshot) return;

    // Check if player played at least 1 tile
    const initialRackTileIds = new Set(snapshot.rack.tiles.map(t => t.id));
    const playedTiles = snapshot.rack.tiles.filter(t => !remainingRackTileIds.includes(t.id));

    if (playedTiles.length === 0) {
      this.executeTurnRollback(playerId, "Aucune tuile n'a été déposée sur le plateau. Piochez une tuile pour passer.", false);
      return;
    }

    // Propose board
    const proposedBoard: BoardState = {
      ...this.boardState,
      melds: proposedMelds.filter(m => m.tiles.length > 0)
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
    this.boardState.versionId++;
    this.boardState.melds = proposedBoard.melds;
    this.recordDelta(this.boardState.melds);

    // Update player rack
    const remainingTiles = snapshot.rack.tiles.filter(t => remainingRackTileIds.includes(t.id));
    player.rack.tiles = remainingTiles;

    // Check Win Condition (Rack is empty!)
    if (player.rack.tiles.length === 0) {
      this.status = 'FINISHED';
      this.clearTurnTimers();

      // Calcul officiel des scores Rummikub
      let totalLoserPenaltySum = 0;
      const scores: any[] = [];

      for (const [pid, p] of this.players) {
        if (pid === playerId) continue;

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
      }

      // Le vainqueur marque la somme positive des pénalités adverses
      scores.push({
        playerId: playerId,
        playerName: player.name,
        isWinner: true,
        remainingTileCount: 0,
        scoreDelta: totalLoserPenaltySum,
        hadJokerPenalty: false
      });

      this.broadcast({
        type: 'game_over_evt',
        roomCode: this.roomCode,
        winnerId: playerId,
        winnerName: player.name,
        scores
      });
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
    });

    // Send updated rack to active player
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

  handleDrawTile(playerId: string): void {
    if (this.boardState.activePlayerId !== playerId) return;
    const player = this.players.get(playerId)!;

    // If player had modified the board during this turn before deciding to draw, rollback board
    if (this.turnSnapshot) {
      this.boardState.melds = JSON.parse(JSON.stringify(this.turnSnapshot.board.melds));
      this.boardState.versionId++;
    }

    let drawnTile: Tile | null = null;
    if (this.deck.length > 0) {
      drawnTile = this.deck.shift()!;
      player.rack.tiles.push(drawnTile);
      this.boardState.drawPileCount = this.deck.length;
    }

    const nextPlayerId = this.advanceToNextPlayer();

    // Broadcast to room
    this.broadcast({
      type: 'tile_drawn_evt',
      playerId,
      newDrawPileCount: this.deck.length,
      nextPlayerId,
      playerTilesCount: this.getPlayerTilesCount()
    });

    // Send private drawn tile payload to drawing player
    if (drawnTile) {
      this.sendTo(playerId, {
        type: 'tile_drawn_evt',
        playerId,
        drawnTile,
        newDrawPileCount: this.deck.length,
        nextPlayerId,
        playerTilesCount: this.getPlayerTilesCount()
      });
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
    const snapshot = this.turnSnapshot;
    const player = this.players.get(playerId)!;

    const penaltyTiles: Tile[] = [];
    if (applyPenalty && this.config.penaltyTilesCount > 0 && this.deck.length > 0) {
      const count = Math.min(this.config.penaltyTilesCount, this.deck.length);
      penaltyTiles.push(...this.deck.splice(0, count));
      this.boardState.drawPileCount = this.deck.length;
    }

    // Restore pristine board from snapshot
    if (snapshot) {
      this.boardState.melds = JSON.parse(JSON.stringify(snapshot.board.melds));
      player.rack = JSON.parse(JSON.stringify(snapshot.rack));
    }

    player.rack.tiles.push(...penaltyTiles);
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
    });

    // Send restored rack + penalty tiles to active player
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

    this.startPlayerTurn(nextPlayerId);
  }

  // ==========================================
  // GRACE PERIOD FOR RECONNECTION
  // ==========================================

  private startGracePeriod(playerId: string): void {
    this.clearTurnTimers();

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
    this.gracePeriodTickInterval.unref();
  }

  private cancelGracePeriod(): void {
    if (this.gracePeriodTickInterval) {
      clearInterval(this.gracePeriodTickInterval);
      this.gracePeriodTickInterval = null;
    }
    this.boardState.isGracePeriod = false;
    this.boardState.disconnectedPlayerId = null;

    // Resume turn timer
    if (this.config.turnDurationSeconds > 0 && this.turnRemainingMs > 0) {
      this.turnTickInterval = setInterval(() => {
        this.turnRemainingMs -= 1000;
        this.boardState.turnRemainingMillis = Math.max(0, this.turnRemainingMs);
        if (this.turnRemainingMs <= 0) {
          this.handleTurnTimeout(this.boardState.activePlayerId);
        }
      }, 1000);
    }
  }

  private handleGracePeriodExpired(disconnectedPlayerId: string): void {
    console.log(`[Room ${this.roomCode}] Période de grâce expirée pour ${disconnectedPlayerId}`);
    if (this.gracePeriodTickInterval) {
      clearInterval(this.gracePeriodTickInterval);
      this.gracePeriodTickInterval = null;
    }
    this.boardState.isGracePeriod = false;
    this.boardState.disconnectedPlayerId = null;

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

  private advanceToNextPlayer(): string {
    let nextIndex = (this.activePlayerIndex + 1) % this.turnOrder.length;
    // Skip completely disconnected players if needed or let them have grace period
    this.activePlayerIndex = nextIndex;
    return this.turnOrder[this.activePlayerIndex];
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

  private canProvideDeltaSync(clientVersionId: number): boolean {
    if (this.deltaHistory.length === 0) return clientVersionId === this.boardState.versionId;
    const oldest = this.deltaHistory[0].fromVersionId;
    return clientVersionId >= oldest && clientVersionId <= this.boardState.versionId;
  }

  private getDeltasSince(clientVersionId: number): StateDelta[] {
    return this.deltaHistory.filter(d => d.fromVersionId >= clientVersionId);
  }

  private clearTurnTimers(): void {
    if (this.turnTickInterval) {
      clearInterval(this.turnTickInterval);
      this.turnTickInterval = null;
    }
    if (this.turnTimer) {
      clearTimeout(this.turnTimer);
      this.turnTimer = null;
    }
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
      tileCount: p.rack.tiles.length,
      hasMadeInitialMeld: p.rack.hasMadeInitialMeld,
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

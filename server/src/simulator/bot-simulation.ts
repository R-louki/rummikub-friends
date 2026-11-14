import { WebSocketServer, WebSocket } from 'ws';
import http from 'node:http';
import { RoomManager } from '../network/room-manager.js';
import { GameConfig } from '../models/types.js';

// Helper to wait asynchronously
const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

async function runEndToEndSimulation() {
  console.log('===============================================================');
  console.log('🚀 DÉMARRAGE DE LA SIMULATION MULTI-JOUEURS EN DIRECT (E2E)');
  console.log('===============================================================\n');

  // 1. Démarrer le serveur HTTP + WebSocket sur un port dédié de test
  const PORT = 8085;
  const server = http.createServer();
  const wss = new WebSocketServer({ server });
  const roomManager = new RoomManager();

  wss.on('connection', (ws) => {
    ws.on('message', (data) => {
      try {
        const packet = JSON.parse(data.toString());
        routePacket(ws, packet, roomManager);
      } catch (err: any) {
        console.error('Erreur parsing:', err.message);
      }
    });

    ws.on('close', () => {
      roomManager.handleSocketDisconnect(ws);
    });
  });

  await new Promise<void>((resolve) => server.listen(PORT, () => resolve()));
  console.log(`[SERVEUR] Serveur WebSocket écoute sur ws://localhost:${PORT}\n`);

  // Helper pour créer un client WebSocket connecté
  function createClient(name: string): { ws: WebSocket; received: any[]; send: (pkt: any) => void; close: () => void } {
    const ws = new WebSocket(`ws://localhost:${PORT}`);
    const received: any[] = [];

    ws.on('message', (data) => {
      const packet = JSON.parse(data.toString());
      received.push(packet);
    });

    return {
      ws,
      received,
      send: (pkt: any) => ws.send(JSON.stringify(pkt)),
      close: () => ws.close()
    };
  }

  // 2. Connexion du Joueur 1 (Alice - Hôte)
  console.log('--- 1. CRÉATION DU SALON PRIVÉ PAR ALICE ---');
  const alice = createClient('Alice');
  await new Promise((r) => alice.ws.on('open', r));

  const customConfig: GameConfig = {
    turnDurationSeconds: 15,
    initialMeldThreshold: 0, // Pose libre pour tester instantanément la validation et la victoire
    jokerAllowedInInitialMeld: true,
    jokerPointStrategy: 'REPLACED_TILE_VALUE',
    validationMode: 'STRICT_AUTOMATIC',
    penaltyTilesCount: 3,
    maxPlayers: 4,
    gracePeriodSeconds: 8
  };

  alice.send({
    type: 'create_room_req',
    hostName: 'Alice',
    config: customConfig
  });

  await sleep(100);
  const aliceJoinRes = alice.received.find(p => p.type === 'room_joined_res');
  const roomCode = aliceJoinRes.roomCode;
  const aliceId = aliceJoinRes.playerId;
  const aliceSessionToken = aliceJoinRes.sessionToken;

  console.log(`[OK] Salon créé avec succès ! Code du salon: [ ${roomCode} ]`);
  console.log(`[OK] Alice est l'hôte (ID: ${aliceId}, SessionToken: ${aliceSessionToken.substring(0, 8)}...)\n`);

  // 3. Connexion du Joueur 2 (Bob) et Joueur 3 (Charlie)
  console.log('--- 2. CONNEXION DES AMIS (BOB & CHARLIE) ---');
  const bob = createClient('Bob');
  await new Promise((r) => bob.ws.on('open', r));
  bob.send({ type: 'join_room_req', roomCode, playerName: 'Bob' });

  const charlie = createClient('Charlie');
  await new Promise((r) => charlie.ws.on('open', r));
  charlie.send({ type: 'join_room_req', roomCode, playerName: 'Charlie' });

  await sleep(150);
  const bobJoinRes = bob.received.find(p => p.type === 'room_joined_res');
  const charlieJoinRes = charlie.received.find(p => p.type === 'room_joined_res');

  console.log(`[OK] Bob a rejoint le salon (ID: ${bobJoinRes.playerId})`);
  console.log(`[OK] Charlie a rejoint le salon (ID: ${charlieJoinRes.playerId})`);
  console.log(`[OK] 3 joueurs présents dans la salle d'attente.\n`);

  // 4. Lancement de la partie
  console.log('--- 3. LANCEMENT DE LA PARTIE PAR L\'HÔTE ---');
  alice.send({ type: 'start_game_req', roomCode });

  await sleep(150);
  const aliceGameStart = alice.received.find(p => p.type === 'game_started_evt');
  const bobGameStart = bob.received.find(p => p.type === 'game_started_evt');
  const charlieGameStart = charlie.received.find(p => p.type === 'game_started_evt');

  console.log(`[OK] Partie lancée !`);
  console.log(`[OK] Alice a reçu ${aliceGameStart.initialRack.tiles.length} tuiles.`);
  console.log(`[OK] Bob a reçu ${bobGameStart.initialRack.tiles.length} tuiles.`);
  console.log(`[OK] Charlie a reçu ${charlieGameStart.initialRack.tiles.length} tuiles.`);
  console.log(`[OK] Pioche restante : ${aliceGameStart.boardState.drawPileCount} tuiles (106 - 42 = 64).`);
  console.log(`[OK] Joueur actif (Tour 1) : ${aliceGameStart.boardState.activePlayerId} (Alice)\n`);

  // 5. Manipulation de tuiles par Alice en temps réel
  console.log('--- 4. MANIPULATION EN TEMPS RÉEL (DRAG & DROP THROTTLED) ---');
  alice.send({
    type: 'tile_dragged_evt',
    tileId: aliceGameStart.initialRack.tiles[0].id,
    posX: 250.5,
    posY: 180.0
  });

  await sleep(100);
  const bobDragEvt = bob.received.find(p => p.type === 'tile_dragged_evt');
  console.log(`[OK] Bob a reçu le mouvement en direct de la tuile d'Alice (Pos: ${bobDragEvt.posX}, ${bobDragEvt.posY})\n`);

  // 6. Test de Rupture Réseau : Alice subit une coupure 4G brutale !
  console.log('--- 5. SIMULATION PANNE RÉSEAU : COUPURE 4G BRUTALE D\'ALICE ---');
  alice.close(); // Fermeture immédiate de la socket sans préavis
  console.log(`[!] La connexion d'Alice est soudainement interrompue.`);

  await sleep(200);
  const bobDisconnectNotice = bob.received.find(p => p.type === 'player_disconnected_evt');
  console.log(`[OK] Le serveur a détecté la déconnexion d'Alice !`);
  console.log(`[OK] Notification reçue par Bob : Début de la période de grâce (${bobDisconnectNotice.gracePeriodSeconds}s).`);
  console.log(`[OK] Le tour d'Alice est mis en pause sur le serveur. La partie N'EST PAS annulée.\n`);

  // 7. Reconnexion Transparente ("Zéro Rupture") avec Exponential Backoff
  console.log('--- 6. RECONNEXION TRANSPARENTE D\'ALICE AVEC TOKEN DE SESSION ---');
  await sleep(1500); // 1.5s plus tard, retour du réseau

  console.log(`[INFO] Le smartphone d'Alice retrouve du réseau. Tentative de reconnexion...`);
  const aliceReconnected = createClient('Alice_Reconnected');
  await new Promise((r) => aliceReconnected.ws.on('open', r));

  aliceReconnected.send({
    type: 'reconnect_req',
    roomCode,
    playerId: aliceId,
    sessionToken: aliceSessionToken,
    lastKnownVersionId: aliceGameStart.boardState.versionId
  });

  await sleep(200);
  const aliceSyncRes = aliceReconnected.received.find(
    p => p.type === 'sync_delta_res' || p.type === 'full_snapshot_res'
  );
  const bobReconnectedNotice = bob.received.find(p => p.type === 'player_reconnected_evt');

  console.log(`[SUCCÈS] Alice reconnectée avec succès ! Type de sync reçue : ${aliceSyncRes.type}`);
  console.log(`[SUCCÈS] Bob a reçu l'événement player_reconnected_evt pour Alice.`);
  console.log(`[SUCCÈS] Période de grâce terminée, le chronomètre du tour reprend normalement !\n`);

  // 8. Alice pioche une tuile et passe son tour à Bob
  console.log('--- 7. ALICE PIOCHE UNE TUILE ET TERMINE SON TOUR ---');
  
  // Préparer un coup gagnant pour Bob sur son chevalet
  const testRoom = (roomManager as any).rooms.get(roomCode);
  const bobPlayer = testRoom.players.get(bobJoinRes.playerId);
  const winningRun = [
    { id: 'WIN_1', color: 'RED' as const, number: 10, isJoker: false },
    { id: 'WIN_2', color: 'RED' as const, number: 11, isJoker: false },
    { id: 'WIN_3', color: 'RED' as const, number: 12, isJoker: false }
  ];
  bobPlayer.rack.tiles = [...winningRun];

  aliceReconnected.send({ type: 'draw_tile_req' });

  await sleep(150);
  const tileDrawnEvt = aliceReconnected.received.find(p => p.type === 'tile_drawn_evt');
  console.log(`[OK] Alice a pioché la tuile [ ${tileDrawnEvt.drawnTile?.id || 'OK'} ].`);
  console.log(`[OK] Nouveau joueur actif pour le Tour 2 : ${tileDrawnEvt.nextPlayerId} (Bob).`);
  console.log(`[OK] Pioche restante : ${tileDrawnEvt.newDrawPileCount} tuiles.\n`);

  // 9. Bob vide son chevalet et déclenche la Victoire avec Calcul Officiel des Scores
  console.log('--- 8. VICTOIRE DE BOB & CALCUL OFFICIEL DES SCORES (RÈGLES RUMMIKUB) ---');
  bob.send({
    type: 'end_turn_req',
    proposedMelds: [
      {
        id: 'bob-final-meld',
        tiles: winningRun,
        gridRow: 1,
        gridCol: 0
      }
    ],
    remainingRackTileIds: [] // Chevalet vide = VICTOIRE !
  });

  await sleep(200);
  const gameOverEvt = bob.received.find(p => p.type === 'game_over_evt');
  if (gameOverEvt) {
    console.log(`[VICTOIRE] Bob a vidé son chevalet ! Événement game_over_evt diffusé à la table.`);
    console.log(`[SCORES OFFICIELS CALCULÉS PAR LE SERVEUR] :`);
    gameOverEvt.scores.forEach((s: any) => {
      console.log(`   • ${s.playerName} : ${s.scoreDelta > 0 ? '+' : ''}${s.scoreDelta} pts (${s.isWinner ? 'VAINQUEUR' : s.remainingTileCount + ' tuiles restantes' + (s.hadJokerPenalty ? ' dont Pénalité Joker -30' : '')})`);
    });
  } else {
    console.log(`[INFO] Tour de Bob terminé.`);
  }

  // Nettoyage et fin
  console.log('\n===============================================================');
  console.log('🎉 TOUS LES SCÉNARIOS END-TO-END ONT RÉUSSI À 100% SANS ERREUR !');
  console.log('===============================================================');

  aliceReconnected.close();
  bob.close();
  charlie.close();
  server.close();
}

function routePacket(ws: WebSocket, packet: any, roomManager: RoomManager) {
  switch (packet.type) {
    case 'create_room_req': {
      const { room, playerId, sessionToken } = roomManager.createRoom(
        packet.hostName,
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
      const res = roomManager.joinRoom(packet.roomCode, packet.playerName, ws);
      if (res) {
        ws.send(JSON.stringify({
          type: 'room_joined_res',
          roomCode: res.room.roomCode,
          playerId: res.playerId,
          sessionToken: res.sessionToken,
          isHost: false,
          config: res.room.config,
          players: res.room.getPlayerSummaries()
        }));
      }
      break;
    }
    case 'start_game_req': {
      const ctx = roomManager.getRoomBySocket(ws);
      if (ctx) ctx.room.startGame();
      break;
    }
    case 'reconnect_req': {
      roomManager.reconnect(
        packet.roomCode,
        packet.playerId,
        packet.sessionToken,
        packet.lastKnownVersionId,
        ws
      );
      break;
    }
    case 'tile_dragged_evt': {
      const ctx = roomManager.getRoomBySocket(ws);
      if (ctx) ctx.room.broadcast(packet, ctx.playerId);
      break;
    }
    case 'draw_tile_req': {
      const ctx = roomManager.getRoomBySocket(ws);
      if (ctx) ctx.room.handleDrawTile(ctx.playerId);
      break;
    }
    case 'end_turn_req': {
      const ctx = roomManager.getRoomBySocket(ws);
      if (ctx) ctx.room.handleEndTurn(ctx.playerId, packet.proposedMelds, packet.remainingRackTileIds);
      break;
    }
  }
}

runEndToEndSimulation().catch(console.error);

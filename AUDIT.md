# Audit Rummikub Friends — 14 novembre 2026

Périmètre : serveur Node/TypeScript (`server/`), application Android Kotlin/Compose (`android/`), déploiement Render et CI GitHub Actions.

## Ce qui a été vérifié et fonctionne

- Tests serveur : 7/7 passent (`npm test` dans `server/`).
- Serveur déployé : `https://rummikub-friends.onrender.com` répond. Premier appel HTTP après veille : 14 s. Une fois réveillé, création de salon + pong en WebSocket en moins d'une seconde.
- Moteur de règles (séries, groupes, jokers, première pose à 30 pts, scores de fin de manche) : logique correcte et identique côté serveur et côté Android.
- Lobby, création et rejoindre un salon, salle d'attente, démarrage de partie : le protocole fonctionne de bout en bout.

Non vérifié : les tests Android (pas de JDK ni de SDK Android sur cette machine, seule la CI les exécute).

## Diagnostic principal

**L'écran de jeu n'est pas relié au réseau.** Le serveur et le moteur de règles sont complets, mais la couche Compose de la partie est une maquette : les boutons n'envoient rien et il n'existe aucun glisser-déposer entre le chevalet et le plateau. C'est la cause de la majorité des « fonctionnalités qui ne marchent pas ».

---

## P0 — Bloquant : la partie n'est pas jouable

### 1. Boutons du chevalet sans effet
[GameScreen.kt:190-213](android/app/src/main/java/com/rummikub/app/ui/GameScreen.kt:190)
- « Piocher », « Annuler Coup », « Séries », « Groupes » : callbacks vides.
- « Valider Tour » joue un son (inexistant, voir P2) mais n'appelle jamais `commitTurn`.
- Conséquence : le joueur actif ne peut rien faire, le timer serveur expire après 60 s, rollback + 3 tuiles de pénalité à chaque tour.

### 2. Aucun glisser-déposer
[PlayerRackView.kt:181-191](android/app/src/main/java/com/rummikub/app/ui/rack/PlayerRackView.kt:181), [BoardGrid.kt:109-123](android/app/src/main/java/com/rummikub/app/ui/board/BoardGrid.kt:109)
- Les gestes de drag consomment l'événement mais ne déplacent rien.
- Il n'existe pas d'état local « plateau en cours de modification » ni de zones de dépôt. Impossible de poser une tuile, de créer une combinaison ou d'en modifier une.
- `TurnSnapshotManager` (moteur local pour « Annuler Coup ») existe mais n'est utilisé nulle part dans l'app.

### 3. Erreurs et rollbacks invisibles
[GameNetworkRepository.kt:70-76](android/core-network/src/main/java/com/rummikub/core/network/GameNetworkRepository.kt:70)
- Les flux `errors` et `turnRollbackEvents` ne sont collectés par aucun écran.
- Code de salon invalide, salon plein, première pose insuffisante, temps écoulé : rien n'est affiché. L'utilisateur reste sur l'écran sans explication.

### 4. Timer figé
[SynchronizedTimerView.kt](android/app/src/main/java/com/rummikub/app/ui/components/SynchronizedTimerView.kt), [GameScreen.kt:139](android/app/src/main/java/com/rummikub/app/ui/GameScreen.kt:139)
- Le serveur ne diffuse jamais les ticks ; le client affiche `turnRemainingMillis` reçu au début du tour et ne décompte pas localement. Le chrono reste à 60.

---

## P1 — Serveur : bugs de logique

### 5. Double déconnexion → rollbacks en boucle
[room-manager.ts:117-126](server/src/network/room-manager.ts:117), [room.ts:92-120](server/src/network/room.ts:92)
- Le superviseur de heartbeat appelle `socket.terminate()` puis `room.handleDisconnect()`. L'événement `close` du socket rappelle `handleSocketDisconnect`, qui rappelle `handleDisconnect`.
- Si le joueur actif est concerné, `startGracePeriod` est lancé deux fois : deux intervalles décrémentent le même compteur, la grâce expire en 15 s au lieu de 30, et le premier intervalle n'est jamais arrêté. Après expiration, il déclenche un rollback avec pénalité **chaque seconde**, en restaurant le chevalet du mauvais joueur depuis le snapshot courant.
- Correctif : supprimer l'entrée `socketToRoom` avant `terminate()` ou ignorer `handleDisconnect` si le joueur est déjà marqué déconnecté ; annuler tout intervalle de grâce existant dans `startGracePeriod`.

### 6. Joueur fantôme dans le salon
[room.ts:92-120](server/src/network/room.ts:92), [MainActivity.kt:137](android/app/src/main/java/com/rummikub/app/MainActivity.kt:137)
- En salle d'attente, un joueur qui se déconnecte reste dans `players` : le salon compte comme plein, `newHostId` n'est jamais envoyé, et la partie peut démarrer avec un fantôme qui subira 60 s de timer et des pénalités à chaque tour.
- Côté client, « Quitter le salon » change juste d'écran sans fermer la connexion ni prévenir le serveur.

### 7. Reconnexion : chevalet jamais resynchronisé
[room.ts:148-170](server/src/network/room.ts:148)
- `sync_delta_res` n'inclut pas le chevalet ni `drawPileCount`. Un joueur dont la grâce a expiré reçoit 3 tuiles de pénalité qu'il ne voit jamais.
- `handleDrawTile` et `executeTurnRollback` incrémentent `versionId` sans enregistrer de delta : le delta renvoyé peut contenir un plateau obsolète.
- Correctif simple : toujours renvoyer `full_snapshot_res` à la reconnexion.

### 8. Pas de contrôle de conservation des tuiles
[room.ts:252-320](server/src/network/room.ts:252)
- Au commit, le serveur vérifie les combinaisons mais pas que le plateau proposé contient exactement les tuiles du plateau précédent + celles jouées depuis le chevalet. Des tuiles peuvent disparaître ou apparaître (bug client ou triche).

### 9. Heartbeat trop agressif pour Android
[room-manager.ts:9-12,117](server/src/network/room-manager.ts:117), [HeartbeatManager.kt:21-22](android/core-network/src/main/java/com/rummikub/core/network/HeartbeatManager.kt:21)
- Ping toutes les 3 s, coupure serveur au-delà de 6 s. Un changement d'application, un appel ou un écran éteint suffisent à déclencher une déconnexion puis une pénalité. Recommandation : 15 à 20 s.

### 10. Fin de partie incohérente
[MainActivity.kt:149-155](android/app/src/main/java/com/rummikub/app/MainActivity.kt:149), [room.ts:184](server/src/network/room.ts:184)
- « Rejouer une Manche » renvoie l'hôte en salle d'attente ; `startGame` relance sans vérifier le statut, mais les autres joueurs restent sur l'écran de victoire et ne basculent plus en jeu.
- Les salons terminés ne sont jamais supprimés du serveur.

### 11. Message trompeur
[room.ts:264](server/src/network/room.ts:264)
- « Piochez une tuile pour passer » alors que le rollback a déjà fait passer le tour.

---

## P2 — Audio et voix : non implémentés

### 12. Chat vocal
- [NetworkPacket.kt:273](android/core-model/src/main/java/com/rummikub/core/model/NetworkPacket.kt:273) : `VoiceSignalPacket` a une propriété `type` qui entre en conflit avec le discriminant JSON `type`. kotlinx.serialization refuse d'encoder ce paquet (exception) et ne peut pas le décoder. Renommer avec `@SerialName("signalType")`.
- Aucun WebRTC réel : la bibliothèque est déclarée dans `libs.versions.toml` mais jamais importée, aucune capture micro, `updateLocalVadAudioEnergy` jamais appelé, permission `RECORD_AUDIO` jamais demandée à l'exécution.
- [MainActivity.kt:48](android/app/src/main/java/com/rummikub/app/MainActivity.kt:48) : l'identifiant de joueur utilisé pour la voix est généré localement et ne correspond pas à l'identifiant attribué par le serveur.

### 13. Musique et bruitages
- `FocusMusicPlayer` ne pilote aucun ExoPlayer ; `SoundEffectPlayer` est construit avec un callback vide. Les curseurs de volume n'ont aucun effet audible.

---

## P3 — Connexion et expérience utilisateur

### 14. Réveil du serveur Render
[RummikubWebSocketClient.kt:44](android/core-network/src/main/java/com/rummikub/core/network/RummikubWebSocketClient.kt:44), [GameNetworkRepository.kt:170-181](android/core-network/src/main/java/com/rummikub/core/network/GameNetworkRepository.kt:170)
- Plan gratuit : le serveur s'endort après inactivité et met 15 à 60 s à repartir. Le client coupe à 5 s et, hors salon, passe simplement en `Disconnected` sans message. Première connexion de la journée = échec silencieux.
- Correctif : timeout 60 s, message « Réveil du serveur… », nouvel essai automatique.

### 15. Aucun indicateur de connexion dans le lobby et la salle d'attente.

---

## Travaux réalisés le 14 novembre 2026 (P0, non compilés localement)

- Nouveau `TurnController` (`android/app/src/main/java/com/rummikub/app/game/`) : état local du tour (plateau et chevalet de travail), snapshot de début de tour, déplacement de tuiles avec scission de combinaison, tri, « Annuler Coup », « Piocher », « Valider Tour » avec vérification locale avant envoi (aucune pénalité pour une erreur évidente).
- Glisser-déposer (`ui/dnd/TileDragDrop.kt`) : appui long pour soulever une tuile, zones de dépôt aux extrémités et entre les tuiles de chaque combinaison, zone « Nouvelle combinaison », retour sur le chevalet. Mode alternatif : toucher une tuile puis toucher une zone.
- `GameScreen` : tous les boutons câblés, ligne d'état du tour (tuiles posées, points de première pose, combinaisons invalides), décompte local du chrono et de la période de grâce.
- `MainActivity` : affichage des erreurs serveur, des rollbacks et des messages locaux dans une Snackbar.
- Bug supplémentaire trouvé et corrigé : après un commit ou un rollback, le serveur diffusait un `boardState` dont `activePlayerId` était encore l'ancien joueur. Corrigé côté serveur (`advanceToNextPlayer`) et côté client (`TurnCommittedEvent`, `TurnRollbackEvent`, `TileDrawnEvent`).
- À vérifier : compilation Android en CI (pas de JDK ni de SDK sur ce poste), puis test réel à deux téléphones.

## Travaux réalisés le 15 novembre 2026 (retour du test sur deux téléphones)

Serveur (14 tests passent) :
- Double déconnexion corrigée : `handleDisconnect` est idempotent, `startGracePeriod` n'empile plus deux compteurs, le superviseur de heartbeat oublie le socket avant de le fermer. Heartbeat porté à 20 s (client 15 s).
- Joueurs fantômes : départ hors partie = retrait du salon avec transfert d'hôte (`player_left_evt.newHostId`). Nouveau paquet `leave_room_req`. En partie, un départ retire le joueur de l'ordre des tours ; s'il ne reste qu'un joueur, il gagne par forfait.
- Reconnexion : toujours un `full_snapshot_res` (chevalet inclus). Partie mise en pause si tout le monde est hors-ligne, reprise à la première reconnexion. Salons supprimés quand vides, ou 5 min après que tout le monde soit parti.
- Conservation des tuiles au commit : le plateau proposé est reconstruit à partir des tuiles connues du serveur ; tuile étrangère, falsifiée, dupliquée ou disparue = refus sans pénalité (`INVALID_PROPOSAL`). Valider sans tuile posée ne passe plus le tour (`NO_TILES_PLAYED`).
- Rejouer : `startGame` accepté après une partie terminée, nouveaux joueurs acceptés entre deux manches.

Android :
- Connexion : réveil du serveur dès l'ouverture de l'app (GET HTTP), tentatives pendant 100 s avec overlay « Connexion en cours / Réveil du serveur », bouton Annuler, message d'échec clair. Détection immédiate d'une fermeture de socket.
- Mise en page téléphone : plateau plein écran, ruban gauche (chrono, joueurs avec halo de parole, connexion, pioche, code, réglages), ruban droit (Valider, Piocher, Annuler, Séries, Groupes, micro). Écran maintenu allumé.
- Sélection multiple : toucher plusieurs tuiles puis la table libre crée une combinaison en un geste ; toute la table est une zone de dépôt.
- Audio réel : bruitages synthétisés (aucun fichier requis), ambiance via ExoPlayer (3 pistes vérifiées en ligne), chat vocal WebRTC en maillage via la signalisation du serveur (STUN Google, pas de TURN : peut échouer entre certains réseaux mobiles). Permission micro demandée à l'entrée dans un salon. Bug de sérialisation du paquet vocal corrigé (`signalType`).
- Quitter le salon / Retour au salon préviennent le serveur et réinitialisent l'état. Navigation vers la table à chaque `game_started_evt` (rejouer fonctionne pour tous).
- Signature : clé de release générée dans `android/keystore/` (hors git), `build.gradle.kts` signe debug et release avec cette clé si présente, CI prête à recevoir la clé en secret GitHub. `versionCode` 2 / `versionName` 1.1.0.

## Ordre de travail recommandé

1. **Écran de jeu (P0)** : état local du tour, glisser-déposer chevalet ↔ plateau, câblage des boutons, affichage des erreurs et rollbacks, décompte local du timer. C'est le gros morceau.
2. **Serveur (P1)** : points 5 à 9, petits correctifs ciblés avec tests.
3. **Connexion (P3)** : timeout et retours visuels.
4. **Audio/voix (P2)** : soit implémenter réellement (ExoPlayer, WebRTC), soit retirer ces options de l'interface tant qu'elles ne sont pas prêtes.

## Sauvegarde des parties (15 novembre 2026)

- Projet Supabase dédié `rummikub-friends` (id `wanqpgxhyoybrpbzsjgu`, région eu-central-1, palier gratuit). Table `public.rooms` (room_code, state jsonb, updated_at), sécurité RLS activée sans aucune politique : seul le serveur, avec la clé secrète, peut lire ou écrire.
- Le serveur enregistre l'état de chaque table en cours à chaque tour (écritures regroupées sur 400 ms), à l'arrêt (SIGTERM envoyé par Render lors d'un redéploiement) et supprime la ligne quand la table disparaît. Les lobbys ne sont pas sauvegardés.
- Au démarrage, les tables sauvegardées depuis moins de 24 h sont restaurées AVANT d'accepter les connexions. Les joueurs se reconnectent avec leur jeton de session, la partie reprend, les robots rejouent seuls.
- Activation : variables d'environnement Render `SUPABASE_URL` (https://wanqpgxhyoybrpbzsjgu.supabase.co) et `SUPABASE_SERVICE_KEY` (clé secrète du projet, jamais dans le dépôt). Sans elles la sauvegarde est simplement désactivée.

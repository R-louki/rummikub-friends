import { WebSocketServer, WebSocket } from 'ws';
import http from 'node:http';
import { RoomManager } from './network/room-manager.js';
import { RoomStore } from './network/room-store.js';
import { SupabaseAccounts, AccountSessions } from './network/accounts.js';
import { createPacketHandler } from './network/packet-handler.js';

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 8080;

/**
 * ICE servers handed to the phones for live voice. STUN is always included.
 *
 * Without a TURN relay, two phones that cannot reach each other directly (common on mobile data,
 * behind a symmetric NAT) simply never hear each other for the rest of the match: STUN only helps
 * discover the public address, it cannot relay the actual audio. A dedicated relay is added when
 * configured through TURN_URLS (comma separated, e.g. "turn:host:3478,turns:host:443?transport=tcp"),
 * TURN_USERNAME and TURN_CREDENTIAL environment variables; until that is set up, the Open Relay
 * Project's free public TURN (openrelay.metered.ca, no account needed, published for exactly this
 * use) is included as a fallback so voice still has a relay path.
 */
function buildIceServers(): Array<{ urls: string[]; username?: string; credential?: string }> {
  const servers: Array<{ urls: string[]; username?: string; credential?: string }> = [
    { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }
  ];
  const turnUrls = (process.env.TURN_URLS ?? '').split(',').map(s => s.trim()).filter(Boolean);
  if (turnUrls.length > 0 && process.env.TURN_USERNAME && process.env.TURN_CREDENTIAL) {
    servers.push({
      urls: turnUrls,
      username: process.env.TURN_USERNAME,
      credential: process.env.TURN_CREDENTIAL
    });
  } else {
    servers.push({
      urls: [
        'turn:openrelay.metered.ca:80',
        'turn:openrelay.metered.ca:443',
        'turn:openrelay.metered.ca:443?transport=tcp'
      ],
      username: 'openrelayproject',
      credential: 'openrelayproject'
    });
  }
  return servers;
}

/** Where the latest APK lives: the GitHub release of the project (stable "latest" link). */
const APK_URL = process.env.DOWNLOAD_APK_URL
  ?? 'https://github.com/R-louki/rummikub-friends/releases/latest/download/Rummikub-Friends.apk';

const DOWNLOAD_PAGE = `<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Rummikub Friends - Télécharger</title>
<meta property="og:title" content="Rummikub Friends"><meta property="og:description" content="Jouez au Rummikub entre amis, avec la voix en direct. Téléchargez l'application Android.">
<style>
body{margin:0;font-family:system-ui,sans-serif;background:#0c2b18;color:#fff;display:flex;min-height:100vh;align-items:center;justify-content:center}
.card{max-width:420px;margin:16px;padding:24px;background:#26140b;border:2px solid #3e2316;border-radius:18px;text-align:center}
h1{margin:0;color:#ffd54f;letter-spacing:3px}p{color:#e0d6cf;line-height:1.5}
a.btn{display:block;margin:18px 0 8px;padding:16px;background:#e65100;color:#fff;font-weight:700;text-decoration:none;border-radius:12px;font-size:18px}
ol{text-align:left;color:#cfc4bd;font-size:14px;line-height:1.6;padding-left:20px}small{color:#9a8f88}
</style></head><body><div class="card">
<h1>RUMMIKUB</h1><p><b>Friends &amp; Live Voice</b><br>Rummikub entre amis, avec la voix en direct.</p>
<a class="btn" href="/download.apk">Télécharger pour Android</a>
<ol><li>Ouvrez ce lien <b>sur votre téléphone Android</b> (dans Chrome si Messenger propose de choisir).</li>
<li>Appuyez sur « Télécharger », puis ouvrez le fichier.</li>
<li>Si Android le demande, autorisez l'installation depuis cette source.</li>
<li>Au premier lancement, connectez-vous avec Google et choisissez un pseudo.</li></ol>
<small>Les mises à jour suivantes se proposent directement dans l'application.</small>
</div></body></html>`;

const server = http.createServer((req, res) => {
  const path = (req.url ?? '/').split('?')[0];
  if (path === '/download') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'public, max-age=300' });
    res.end(DOWNLOAD_PAGE);
    return;
  }
  if (path === '/download.apk') {
    res.writeHead(302, { Location: APK_URL, 'Cache-Control': 'no-store' });
    res.end();
    return;
  }
  if (req.url?.startsWith('/ice-servers')) {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ iceServers: buildIceServers() }));
    return;
  }
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ status: 'UP', service: 'Rummikub Authoritative Real-Time Server' }));
});

const wss = new WebSocketServer({ server });
const roomStore = new RoomStore();
const accounts = new SupabaseAccounts();
const sessions = new AccountSessions();
const roomManager = new RoomManager(roomStore, accounts);
// REQUIRE_AUTH=1 forces a Google account to play; by default anonymous players are still accepted.
const requireAuth = process.env.REQUIRE_AUTH === '1';
const packets = createPacketHandler(roomManager, accounts, sessions, { requireAuth });
console.log(`[Rummikub Server] Persistance des parties: ${roomStore.enabled ? 'activée (Supabase)' : 'désactivée'}`);
console.log(`[Rummikub Server] Comptes: ${accounts.enabled ? (requireAuth ? 'activés et obligatoires' : 'activés (optionnels)') : 'désactivés'}`);

wss.on('connection', (ws: WebSocket) => {
  console.log('[WebSocket] Nouvelle connexion entrante');

  ws.on('message', (data: Buffer | string) => {
    try {
      const packet = JSON.parse(data.toString());
      packets.handle(ws, packet);
    } catch (err: any) {
      console.error('[WebSocket] Erreur parsing paquet JSON:', err.message);
      ws.send(JSON.stringify({ type: 'error', code: 'INVALID_JSON', message: err.message }));
    }
  });

  ws.on('close', () => {
    packets.onClose(ws);
  });

  ws.on('error', (err) => {
    console.error('[WebSocket] Erreur socket:', err.message);
    packets.onClose(ws);
  });
});

// Restore saved tables BEFORE accepting connections, so reconnecting players find their game.
roomManager.restore().finally(() => {
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`[Rummikub Server] Serveur WebSocket autoritaire démarré sur le port ${PORT}`);
  });
});

// Render stops the old instance with SIGTERM on a redeploy: write every table before leaving.
let stopping = false;
const shutdown = async (signal: string) => {
  if (stopping) return;
  stopping = true;
  console.log(`[Rummikub Server] ${signal} reçu: sauvegarde des parties...`);
  try {
    await Promise.race([roomManager.flushAll(), new Promise(r => setTimeout(r, 8000))]);
  } catch { /* nothing more to do */ }
  process.exit(0);
};
process.on('SIGTERM', () => void shutdown('SIGTERM'));
process.on('SIGINT', () => void shutdown('SIGINT'));

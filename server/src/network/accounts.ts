import { WebSocket } from 'ws';

/**
 * Player accounts (Supabase Auth with Google sign-in).
 *
 * The phone logs in with Google through Supabase and sends its access token to the game server
 * (`auth_req`). The server verifies the token with Supabase, learns the user id and the unique
 * pseudo, and from then on uses them for the player's name, friends presence, invitations and
 * the game history. Everything is optional: without SUPABASE_URL / SUPABASE_SERVICE_KEY the
 * server keeps working with anonymous players exactly as before.
 */

export interface Account {
  userId: string;
  /** null until the player has chosen a pseudo. */
  pseudo: string | null;
  /** "emoji:🦊" or "url:https://..." (uploaded photo); null = none. */
  avatar?: string | null;
}

export interface GamePlayerRecord {
  pseudo: string;
  userId: string | null;
  isBot: boolean;
  isWinner: boolean;
  remainingTiles: number;
  roundDelta: number;
  totalScore: number;
}

export interface GameRecord {
  roomCode: string;
  roundNumber: number;
  winnerName: string;
  vsComputer: boolean;
  players: GamePlayerRecord[];
}

export interface AccountBackend {
  readonly enabled: boolean;
  verify(accessToken: string): Promise<Account | null>;
  /** Ids of the accepted friends of [userId]. */
  friendIds(userId: string): Promise<string[]>;
  saveGame(record: GameRecord): Promise<void>;
}

/** Disabled backend: the server behaves as before, with anonymous players. */
export class NoAccounts implements AccountBackend {
  readonly enabled = false;
  async verify(): Promise<Account | null> { return null; }
  async friendIds(): Promise<string[]> { return []; }
  async saveGame(): Promise<void> { /* nothing */ }
}

export class SupabaseAccounts implements AccountBackend {
  private readonly base: string | null;
  private readonly key: string | null;

  constructor(url: string | undefined = process.env.SUPABASE_URL, key: string | undefined = process.env.SUPABASE_SERVICE_KEY) {
    this.base = url ? url.replace(/\/+$/, '') : null;
    this.key = key ?? null;
  }

  get enabled(): boolean {
    return !!this.base && !!this.key;
  }

  private headers(bearer?: string, extra: Record<string, string> = {}): Record<string, string> {
    return {
      apikey: this.key!,
      Authorization: `Bearer ${bearer ?? this.key}`,
      'Content-Type': 'application/json',
      ...extra
    };
  }

  async verify(accessToken: string): Promise<Account | null> {
    if (!this.enabled || !accessToken || typeof accessToken !== 'string') return null;
    // Supabase checks the signature and the expiry of the token for us.
    const who = await fetch(`${this.base}/auth/v1/user`, {
      headers: this.headers(accessToken),
      signal: AbortSignal.timeout(8000)
    });
    if (!who.ok) return null;
    const user = (await who.json()) as { id?: string };
    if (!user?.id) return null;

    const prof = await fetch(`${this.base}/rest/v1/profiles?user_id=eq.${encodeURIComponent(user.id)}&select=pseudo,avatar`, {
      headers: this.headers(),
      signal: AbortSignal.timeout(8000)
    });
    const rows = prof.ok ? ((await prof.json()) as Array<{ pseudo: string; avatar: string | null }>) : [];
    return { userId: user.id, pseudo: rows[0]?.pseudo ?? null, avatar: rows[0]?.avatar ?? null };
  }

  async friendIds(userId: string): Promise<string[]> {
    if (!this.enabled) return [];
    const id = encodeURIComponent(userId);
    const res = await fetch(
      `${this.base}/rest/v1/friendships?status=eq.accepted&or=(requester.eq.${id},addressee.eq.${id})&select=requester,addressee`,
      { headers: this.headers(), signal: AbortSignal.timeout(8000) }
    );
    if (!res.ok) return [];
    const rows = (await res.json()) as Array<{ requester: string; addressee: string }>;
    return rows.map(r => (r.requester === userId ? r.addressee : r.requester));
  }

  async saveGame(record: GameRecord): Promise<void> {
    if (!this.enabled) return;
    const created = await fetch(`${this.base}/rest/v1/game_history`, {
      method: 'POST',
      headers: this.headers(undefined, { Prefer: 'return=representation' }),
      body: JSON.stringify({
        room_code: record.roomCode,
        round_number: record.roundNumber,
        winner_name: record.winnerName,
        vs_computer: record.vsComputer
      }),
      signal: AbortSignal.timeout(8000)
    });
    if (!created.ok) throw new Error(`history: HTTP ${created.status}`);
    const [row] = (await created.json()) as Array<{ id: string }>;
    const players = await fetch(`${this.base}/rest/v1/game_players`, {
      method: 'POST',
      headers: this.headers(undefined, { Prefer: 'return=minimal' }),
      body: JSON.stringify(record.players.map(p => ({
        history_id: row.id,
        pseudo: p.pseudo,
        user_id: p.userId,
        is_bot: p.isBot,
        is_winner: p.isWinner,
        remaining_tiles: p.remainingTiles,
        round_delta: p.roundDelta,
        total_score: p.totalScore
      }))),
      signal: AbortSignal.timeout(8000)
    });
    if (!players.ok) throw new Error(`history players: HTTP ${players.status}`);
  }
}

/** Who is logged in on which socket, and who is online (for friends presence and invitations). */
export class AccountSessions {
  private bySocket = new Map<WebSocket, Account>();
  private byUser = new Map<string, Set<WebSocket>>();

  bind(ws: WebSocket, account: Account): void {
    this.unbind(ws);
    this.bySocket.set(ws, account);
    if (!this.byUser.has(account.userId)) this.byUser.set(account.userId, new Set());
    this.byUser.get(account.userId)!.add(ws);
  }

  unbind(ws: WebSocket): void {
    const old = this.bySocket.get(ws);
    if (!old) return;
    this.bySocket.delete(ws);
    const set = this.byUser.get(old.userId);
    set?.delete(ws);
    if (set && set.size === 0) this.byUser.delete(old.userId);
  }

  accountOf(ws: WebSocket): Account | undefined {
    return this.bySocket.get(ws);
  }

  isOnline(userId: string): boolean {
    return (this.byUser.get(userId)?.size ?? 0) > 0;
  }

  socketsOf(userId: string): WebSocket[] {
    return Array.from(this.byUser.get(userId) ?? []);
  }

  get onlineCount(): number {
    return this.byUser.size;
  }
}

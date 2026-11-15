/**
 * Persistence of in-progress tables, so that a server restart or redeploy does not end the games.
 *
 * The store talks to Supabase (PostgREST) with the SERVICE ROLE key, which only lives in the
 * server environment (SUPABASE_URL, SUPABASE_SERVICE_KEY). Without them persistence is simply off.
 * The key is never logged.
 */

export interface StoredRoom {
  room_code: string;
  state: any;
  updated_at?: string;
}

export interface RoomPersistence {
  readonly enabled: boolean;
  save(roomCode: string, state: unknown): Promise<void>;
  remove(roomCode: string): Promise<void>;
  loadAll(maxAgeHours?: number): Promise<StoredRoom[]>;
}

export class RoomStore implements RoomPersistence {
  private readonly baseUrl: string | null;
  private readonly key: string | null;

  constructor(url: string | undefined = process.env.SUPABASE_URL, key: string | undefined = process.env.SUPABASE_SERVICE_KEY) {
    this.baseUrl = url ? url.replace(/\/+$/, '') + '/rest/v1/rooms' : null;
    this.key = key ?? null;
  }

  get enabled(): boolean {
    return !!this.baseUrl && !!this.key;
  }

  private headers(extra: Record<string, string> = {}): Record<string, string> {
    return {
      apikey: this.key!,
      Authorization: `Bearer ${this.key}`,
      'Content-Type': 'application/json',
      ...extra
    };
  }

  async save(roomCode: string, state: unknown): Promise<void> {
    if (!this.enabled) return;
    const res = await fetch(`${this.baseUrl}?on_conflict=room_code`, {
      method: 'POST',
      headers: this.headers({ Prefer: 'resolution=merge-duplicates,return=minimal' }),
      body: JSON.stringify([{ room_code: roomCode, state, updated_at: new Date().toISOString() }]),
      signal: AbortSignal.timeout(8000)
    });
    if (!res.ok) throw new Error(`save ${roomCode}: HTTP ${res.status}`);
  }

  async remove(roomCode: string): Promise<void> {
    if (!this.enabled) return;
    const res = await fetch(`${this.baseUrl}?room_code=eq.${encodeURIComponent(roomCode)}`, {
      method: 'DELETE',
      headers: this.headers({ Prefer: 'return=minimal' }),
      signal: AbortSignal.timeout(8000)
    });
    if (!res.ok) throw new Error(`remove ${roomCode}: HTTP ${res.status}`);
  }

  /** Loads the tables saved in the last [maxAgeHours] hours and purges older rows. */
  async loadAll(maxAgeHours: number = 24): Promise<StoredRoom[]> {
    if (!this.enabled) return [];
    const since = new Date(Date.now() - maxAgeHours * 3600_000).toISOString();

    // best-effort purge of stale rows
    try {
      await fetch(`${this.baseUrl}?updated_at=lt.${encodeURIComponent(since)}`, {
        method: 'DELETE',
        headers: this.headers({ Prefer: 'return=minimal' }),
        signal: AbortSignal.timeout(8000)
      });
    } catch { /* ignore */ }

    const res = await fetch(`${this.baseUrl}?select=room_code,state,updated_at&updated_at=gte.${encodeURIComponent(since)}`, {
      headers: this.headers(),
      signal: AbortSignal.timeout(10000)
    });
    if (!res.ok) throw new Error(`load: HTTP ${res.status}`);
    return (await res.json()) as StoredRoom[];
  }
}

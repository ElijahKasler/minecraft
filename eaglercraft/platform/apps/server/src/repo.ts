/** All SQL access for the platform. SQL lives here, not in HTTP handlers. */

import type { Db, Row } from "./db.js";
import { query, queryOne } from "./db.js";
import { sha256Hex } from "./crypto.js";

export interface UserRow {
  id: string;
  email: string;
  username: string;
  role: "user" | "admin";
  password_hash: string;
  email_verified_at: string | null;
  created_at: string;
}

export interface SessionRow {
  token_hash: string;
  user_id: string;
  created_at: string;
  expires_at: string;
}

export interface ServerRow {
  id: string;
  name: string;
  description: string;
  address: string;
  port: number;
  online_mode: boolean;
  icon_url: string | null;
  max_players: number | null;
  sort_order: number;
  enabled: boolean;
  maintenance: boolean;
  created_at: string;
}

export interface PlayTicketRow {
  token_hash: string;
  user_id: string;
  server_id: string;
  used_at: string | null;
  expires_at: string;
}

export interface MsOAuthRow {
  user_id: string;
  ms_subject: string;
  ms_email: string | null;
  ms_name: string | null;
  ms_refresh_enc: Buffer | null;
  mc_uuid: string | null;
  mc_username: string | null;
  linked_at: string;
}

export interface AuditRow {
  id: string;
  actor_id: string | null;
  action: string;
  detail: Record<string, unknown>;
  created_at: string;
}

const HOUR_IN_MS = 3600_000;

// ---------------------------------------------------------------------------
// users
// ---------------------------------------------------------------------------

export function createUser(
  db: Db,
  p: { email: string; username: string; passwordHash: string; role?: string },
): Promise<UserRow | undefined> {
  return queryOne<UserRow>(
    db,
    `insert into users (email, username, password_hash, role)
     values ($1, $2, $3, coalesce($4, 'user'))
     returning id, email, username, role, password_hash, email_verified_at, created_at`,
    [p.email, p.username, p.passwordHash, p.role],
  );
}

export function getUserByEmail(db: Db, email: string): Promise<UserRow | undefined> {
  return queryOne<UserRow>(db, `select * from users where email = $1`, [email]);
}

export function getUserById(db: Db, id: string): Promise<UserRow | undefined> {
  return queryOne<UserRow>(db, `select * from users where id = $1`, [id]);
}

export function getUserByUsername(db: Db, username: string): Promise<UserRow | undefined> {
  return queryOne<UserRow>(db, `select * from users where username = $1`, [username]);
}

export function listUsers(db: Db, opts: { limit: number; offset: number }): Promise<UserRow[]> {
  return query<UserRow>(db, `select * from users order by created_at desc limit $1 offset $2`, [opts.limit, opts.offset]);
}

export function countUsers(db: Db): Promise<number> {
  return queryOne<{ count: string }>(db, `select count(*)::text as count from users`).then((r) => Number(r?.count ?? 0));
}

export function setUserRole(db: Db, id: string, role: string): Promise<UserRow | undefined> {
  return queryOne<UserRow>(db, `update users set role = $2, updated_at = now() where id = $1 returning *`, [id, role]);
}

// ---------------------------------------------------------------------------
// sessions
// ---------------------------------------------------------------------------

export function createSession(db: Db, token: string, userId: string, ttlSeconds: number): Promise<void> {
  const tokenHash = sha256Hex(token);
  return query(
    db,
    `insert into sessions (token_hash, user_id, expires_at) values ($1, $2, now() + make_interval(secs => $3::int))`,
    [tokenHash, userId, ttlSeconds],
  ).then(() => undefined);
}

export async function getUserBySessionToken(db: Db, token: string): Promise<UserRow | undefined> {
  const tokenHash = sha256Hex(token);
  const row = await queryOne<SessionRow & JsUser>(
    db,
    `select s.*, u.id, u.email, u.username, u.role, u.password_hash, u.email_verified_at, u.created_at
     from sessions s join users u on u.id = s.user_id
     where s.token_hash = $1 and s.expires_at > now()`,
    [tokenHash],
  );
  return row ? (row as unknown as UserRow) : undefined;
}

interface JsUser extends Row {}

export function deleteSession(db: Db, token: string): Promise<void> {
  return query(db, `delete from sessions where token_hash = $1`, [sha256Hex(token)]).then(() => undefined);
}

export function deleteExpiredSessions(db: Db): Promise<void> {
  return query(db, `delete from sessions where expires_at < now()`).then(() => undefined);
}

// ---------------------------------------------------------------------------
// servers
// ---------------------------------------------------------------------------

export function listServers(db: Db, onlyEnabled = true): Promise<ServerRow[]> {
  return query<ServerRow>(
    db,
    `select * from servers where ($1::bool or enabled) order by sort_order asc, name asc`,
    [onlyEnabled],
  );
}

export function getServerById(db: Db, id: string): Promise<ServerRow | undefined> {
  return queryOne<ServerRow>(db, `select * from servers where id = $1`, [id]);
}

export function createServer(
  db: Db,
  p: { name: string; description?: string; address: string; port: number; onlineMode: boolean; iconUrl?: string | null; maxPlayers?: number | null },
): Promise<ServerRow | undefined> {
  return queryOne<ServerRow>(
    db,
    `insert into servers (name, description, address, port, online_mode, icon_url, max_players)
     values ($1, $2, $3, $4, $5, $6, $7) returning *`,
    [p.name, p.description ?? "", p.address, p.port, p.onlineMode, p.iconUrl ?? null, p.maxPlayers ?? null],
  );
}

export function updateServer(db: Db, id: string, fields: Partial<ServerRow>): Promise<ServerRow | undefined> {
  const allowed = ["name", "description", "address", "port", "online_mode", "icon_url", "max_players", "sort_order", "enabled", "maintenance"];
  const set: string[] = [];
  const params: unknown[] = [];
  for (const [k, v] of Object.entries(fields)) {
    if (!allowed.includes(k)) continue;
    params.push(v);
    set.push(`${k} = $${params.length}`);
  }
  if (set.length === 0) return getServerById(db, id);
  params.push(id);
  set.push("updated_at = now()");
  return queryOne<ServerRow>(db, `update servers set ${set.join(", ")} where id = $${params.length} returning *`, params);
}

export function deleteServer(db: Db, id: string): Promise<void> {
  return query(db, `delete from servers where id = $1`, [id]).then(() => undefined);
}

// ---------------------------------------------------------------------------
// play tickets
// ---------------------------------------------------------------------------

export function createPlayTicket(db: Db, token: string, userId: string, serverId: string, ttlSeconds: number): Promise<void> {
  const tokenHash = sha256Hex(token);
  return query(
    db,
    `insert into play_tickets (token_hash, user_id, server_id, expires_at)
     values ($1, $2, $3, now() + make_interval(secs => $4::int)) on conflict (token_hash) do nothing`,
    [tokenHash, userId, serverId, ttlSeconds],
  ).then(() => undefined);
}

export async function redeemPlayTicket(db: Db, token: string): Promise<{ userId: string; serverId: string } | null> {
  const tokenHash = sha256Hex(token);
  const row = await queryOne<PlayTicketRow>(
    db,
    `update play_tickets set used_at = now()
     where token_hash = $1 and used_at is null and expires_at > now()
     returning user_id, server_id`,
    [tokenHash],
  );
  if (!row) return null;
  return { userId: row.user_id, serverId: row.server_id };
}

// ---------------------------------------------------------------------------
// Microsoft OAuth (Phase 8)
// ---------------------------------------------------------------------------

export function getMsOAuthByUser(db: Db, userId: string): Promise<MsOAuthRow | undefined> {
  return queryOne<MsOAuthRow>(db, `select * from ms_oauth where user_id = $1`, [userId]);
}

export function getMsOAuthBySubject(db: Db, msSubject: string): Promise<MsOAuthRow | undefined> {
  return queryOne<MsOAuthRow>(db, `select * from ms_oauth where ms_subject = $1`, [msSubject]);
}

export function upsertMsOAuth(
  db: Db,
  p: {
    userId: string;
    msSubject: string;
    msEmail?: string | null;
    msName?: string | null;
    msRefreshEnc?: Buffer | null;
    mcUuid?: string | null;
    mcUsername?: string | null;
  },
): Promise<void> {
  return query(
    db,
    `insert into ms_oauth (user_id, ms_subject, ms_email, ms_name, ms_refresh_enc, mc_uuid, mc_username, updated_at)
     values ($1, $2, $3, $4, $5, $6, $7, now())
     on conflict (user_id) do update set
       ms_subject = excluded.ms_subject,
       ms_email = coalesce(excluded.ms_email, ms_oauth.ms_email),
       ms_name = coalesce(excluded.ms_name, ms_oauth.ms_name),
       ms_refresh_enc = coalesce(excluded.ms_refresh_enc, ms_oauth.ms_refresh_enc),
       mc_uuid = coalesce(excluded.mc_uuid, ms_oauth.mc_uuid),
       mc_username = coalesce(excluded.mc_username, ms_oauth.mc_username),
       updated_at = now()`,
    [p.userId, p.msSubject, p.msEmail ?? null, p.msName ?? null, p.msRefreshEnc ?? null, p.mcUuid ?? null, p.mcUsername ?? null],
  ).then(() => undefined);
}

export function deleteMsOAuth(db: Db, userId: string): Promise<void> {
  return query(db, `delete from ms_oauth where user_id = $1`, [userId]).then(() => undefined);
}

// ---------------------------------------------------------------------------
// audit
// ---------------------------------------------------------------------------

export function audit(db: Db, actorId: string | null, action: string, detail: Record<string, unknown> = {}): Promise<void> {
  return query(db, `insert into audit_log (actor_id, action, detail) values ($1, $2, $3::jsonb)`, [
    actorId,
    action,
    JSON.stringify(detail),
  ]).then(() => undefined);
}

export function listAudit(db: Db, opts: { limit: number; offset: number }): Promise<AuditRow[]> {
  return query<AuditRow>(db, `select * from audit_log order by created_at desc limit $1 offset $2`, [opts.limit, opts.offset]);
}

export { HOUR_IN_MS };
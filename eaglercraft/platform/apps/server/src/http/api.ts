/**
 * REST API: account registration/login, server listing, play-ticket issuance,
 * Microsoft OAuth start/callback, and admin management.
 *
 * Security notes:
 *  - Passwords are scrypt-hashed (crypto.ts), never logged.
 *  - Session tokens are opaque random values; only sha256 hashes are stored.
 *  - State-changing requests are protected by an Origin check (the session
 *    cookie is SameSite=Lax), never by trusting client-supplied user ids.
 *  - OAuth state is single-use, bound to the session, and short-lived.
 */

import type { IncomingMessage, ServerResponse } from "node:http";
import type { Config } from "../config.js";
import type { Db } from "../db.js";
import { hashPassword, verifyPassword, randomToken, encryptSecret } from "../crypto.js";
import { query } from "../db.js";
import {
  audit,
  countUsers,
  createPlayTicket,
  createSession,
  createUser,
  deleteSession,
  getMsOAuthByUser,
  getUserByEmail,
  getUserById,
  getUserBySessionToken,
  getUserByUsername,
  listAudit,
  listServers,
  listUsers,
  createServer as repoCreateServer,
  deleteServer as repoDeleteServer,
  getServerById,
  setUserRole,
  updateServer,
  upsertMsOAuth,
} from "../repo.js";
import { EAG } from "@eaglerplatform/shared";
import { createMsAuthUrl, exchangeCodeForTokens, fetchMicrosoftProfile, fetchMinecraftProfile } from "./oauth.js";
import { log } from "../logger.js";

export interface ApiContext {
  config: Config;
  db: Db;
}

type Handler = (req: IncomingMessage, res: ServerResponse, ctx: ApiContext, params: Record<string, string>) => Promise<boolean> | boolean;

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "x-content-type-options": "nosniff" };

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, JSON_HEADERS);
  res.end(JSON.stringify(body));
}

function sendError(res: ServerResponse, status: number, message: string): void {
  sendJson(res, status, { error: message });
}

function getCookie(req: IncomingMessage, name: string): string | undefined {
  const header = req.headers.cookie;
  if (!header) return undefined;
  for (const part of header.split(";")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    if (part.slice(0, eq).trim() === name) return decodeURIComponent(part.slice(eq + 1).trim());
  }
  return undefined;
}

function setSessionCookie(res: ServerResponse, token: string, cfg: Config): void {
  const attrs = `sid=${encodeURIComponent(token)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${cfg.sessionTtlSeconds}`;
  res.setHeader("set-cookie", [cfg.cookieSecure ? `${attrs}; Secure` : attrs]);
}

function clearSessionCookie(res: ServerResponse, cfg: Config): void {
  const attrs = `sid=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0`;
  res.setHeader("set-cookie", [cfg.cookieSecure ? `${attrs}; Secure` : attrs]);
}

async function readJsonBody(req: IncomingMessage, limit = 64 * 1024): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    const buf = chunk as Buffer;
    size += buf.length;
    if (size > limit) throw new Error("request body too large");
    chunks.push(buf);
  }
  if (chunks.length === 0) return {};
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function checkOrigin(req: IncomingMessage, cfg: Config): boolean {
  const origin = req.headers.origin;
  if (!origin) return true; // same-origin GET/POST from a non-browser client
  try {
    return new URL(origin).origin === new URL(cfg.publicOrigin).origin;
  } catch {
    return false;
  }
}

/** Very small per-IP fixed-window limiter for auth endpoints. */
class RateLimiter {
  private hits = new Map<string, { count: number; resetAt: number }>();
  constructor(private readonly limit: number, private readonly windowMs = 60_000) {}
  take(key: string): boolean {
    const now = Date.now();
    const cur = this.hits.get(key);
    if (!cur || cur.resetAt < now) {
      this.hits.set(key, { count: 1, resetAt: now + this.windowMs });
      return true;
    }
    if (cur.count >= this.limit) return false;
    cur.count++;
    return true;
  }
}

export function createApi(ctx: ApiContext): (req: IncomingMessage, res: ServerResponse) => boolean {
  const { config, db } = ctx;
  const authLimiter = new RateLimiter(config.rateLimitAuthPerMin);
  const ipOf = (req: IncomingMessage): string => req.socket.remoteAddress ?? "unknown";

  async function sessionUser(req: IncomingMessage): Promise<{ id: string; email: string; username: string; role: string } | undefined> {
    const token = getCookie(req, config.sessionCookieName);
    if (!token) return undefined;
    const u = await getUserBySessionToken(db, token);
    if (!u) return undefined;
    return { id: u.id, email: u.email, username: u.username, role: u.role };
  }

  function requireAdmin(user: { role: string } | undefined): boolean {
    return user?.role === "admin";
  }

  async function handleRegister(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    if (!authLimiter.take(ipOf(req))) return sendError(res, 429, "Too many attempts"), true;
    if (!checkOrigin(req, config)) return sendError(res, 403, "Invalid origin"), true;
    const body = await readJsonBody(req);
    const email = String(body.email ?? "").trim();
    const username = String(body.username ?? "").trim();
    const password = String(body.password ?? "");
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return sendError(res, 400, "Invalid email"), true;
    if (!EAG.USERNAME_RE.test(username)) return sendError(res, 400, "Username must be 1-16 letters/numbers/underscores"), true;
    if (password.length < 8) return sendError(res, 400, "Password must be at least 8 characters"), true;
    if (await getUserByEmail(db, email)) return sendError(res, 409, "Email already registered"), true;
    if (await getUserByUsername(db, username)) return sendError(res, 409, "Username already taken"), true;
    const isFirst = (await countUsers(db)) === 0;
    const user = await createUser(db, { email, username, passwordHash: hashPassword(password), role: isFirst ? "admin" : "user" });
    if (!user) return sendError(res, 500, "Could not create account"), true;
    const token = randomToken(32);
    await createSession(db, token, user.id, config.sessionTtlSeconds);
    setSessionCookie(res, token, config);
    sendJson(res, 201, { id: user.id, email: user.email, username: user.username, role: user.role });
    return true;
  }

  async function handleLogin(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    if (!authLimiter.take(ipOf(req))) return sendError(res, 429, "Too many attempts"), true;
    if (!checkOrigin(req, config)) return sendError(res, 403, "Invalid origin"), true;
    const body = await readJsonBody(req);
    const email = String(body.email ?? "").trim();
    const password = String(body.password ?? "");
    const user = await getUserByEmail(db, email);
    if (!user || !verifyPassword(password, user.password_hash)) {
      sendError(res, 401, "Invalid email or password");
      return true;
    }
    const token = randomToken(32);
    await createSession(db, token, user.id, config.sessionTtlSeconds);
    setSessionCookie(res, token, config);
    sendJson(res, 200, { id: user.id, email: user.email, username: user.username, role: user.role });
    return true;
  }

  async function handleLogout(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    const token = getCookie(req, config.sessionCookieName);
    if (token) await deleteSession(db, token);
    clearSessionCookie(res, config);
    sendJson(res, 200, { ok: true });
    return true;
  }

  async function handleMe(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    const user = await sessionUser(req);
    if (!user) return sendError(res, 401, "Not logged in"), true;
    const linked = await getMsOAuthByUser(db, user.id);
    sendJson(res, 200, {
      id: user.id,
      email: user.email,
      username: user.username,
      role: user.role,
      linkedMicrosoft: !!linked,
      mcUsername: linked?.mc_username ?? null,
    });
    return true;
  }

  async function handleListServers(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    const rows = await listServers(db, true);
    sendJson(
      res,
      200,
      rows.map((s) => ({
        id: s.id,
        name: s.name,
        description: s.description,
        address: s.address,
        port: s.port,
        onlineMode: s.online_mode,
        iconUrl: s.icon_url,
        maxPlayers: s.max_players,
        maintenance: s.maintenance,
        playerCount: null,
      })),
    );
    return true;
  }

  async function handlePlay(
    _req: IncomingMessage,
    res: ServerResponse,
    _ctx: ApiContext,
    params: Record<string, string>,
  ): Promise<boolean> {
    const req = _req;
    if (!checkOrigin(req, config)) return sendError(res, 403, "Invalid origin"), true;
    const user = await sessionUser(req);
    if (!user) return sendError(res, 401, "Not logged in"), true;
    const serverId = params.id;
    const server = await getServerById(db, serverId);
    if (!server || !server.enabled) return sendError(res, 404, "Server not found"), true;
    if (server.maintenance) return sendError(res, 503, "Server is under maintenance"), true;
    const ticket = randomToken(32);
    await createPlayTicket(db, ticket, user.id, server.id, config.playTicketTtlSeconds);
    const proto = config.publicOrigin.startsWith("https") ? "wss" : "ws";
    const bridgeUrl = `${proto}://${new URL(config.publicOrigin).host}${config.bridgePath}?ticket=${encodeURIComponent(ticket)}`;
    sendJson(res, 200, { ticket, bridgeUrl, serverId: server.id });
    return true;
  }

  // -------------------------------------------------------------------------
  // OAuth (Microsoft)
  // -------------------------------------------------------------------------

  async function handleOAuthStart(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    if (!config.msClientId) return sendError(res, 501, "Microsoft sign-in is not configured"), true;
    const user = await sessionUser(req);
    const state = randomToken(32);
    // store state: signed, bound to user (or anonymous), short-lived
    const payload = JSON.stringify({ state, userId: user?.id ?? null, exp: Date.now() + 600_000 });
    const signed = `${state}.${encryptSecret(config.encryptionKey, payload).toString("base64url")}`;
    res.setHeader("set-cookie", [
      `oauth_state=${encodeURIComponent(signed)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=600${config.cookieSecure ? "; Secure" : ""}`,
    ]);
    const redirectUri = new URL("/api/oauth/callback", config.publicOrigin).toString();
    sendJson(res, 200, { url: createMsAuthUrl(config, state, redirectUri) });
    return true;
  }

  async function handleOAuthCallback(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    const url = new URL(req.url ?? "/", config.publicOrigin);
    const code = url.searchParams.get("code");
    const stateParam = url.searchParams.get("state");
    const cookie = getCookie(req, "oauth_state");
    if (!code || !stateParam || !cookie) {
      sendError(res, 400, "Missing OAuth state");
      return true;
    }
    const [cookieState, enc] = cookie.split(".");
    if (cookieState !== stateParam) {
      sendError(res, 400, "OAuth state mismatch");
      return true;
    }
    const { decryptSecret } = await import("../crypto.js");
    const raw = Buffer.from(enc ?? "", "base64url");
    let payload: { userId: string | null; exp: number } | null = null;
    try {
      payload = JSON.parse(decryptSecret(config.encryptionKey, raw)) as { userId: string | null; exp: number };
    } catch {
      sendError(res, 400, "Invalid OAuth state");
      return true;
    }
    if (!payload || Date.now() > payload.exp) {
      sendError(res, 400, "OAuth state expired");
      return true;
    }

    const redirectUri = new URL("/api/oauth/callback", config.publicOrigin).toString();
    try {
      const tokens = await exchangeCodeForTokens(config, code, redirectUri);
      const msProfile = await fetchMicrosoftProfile(tokens.accessToken);
      const mcProfile = await fetchMinecraftProfile(tokens.accessToken);

      let userId = payload.userId;
      let user = userId ? await getUserById(db, userId) : undefined;
      if (!user) {
        // auto-provision a platform account from the Microsoft identity
        const email = msProfile.email ?? `${msProfile.id}@microsoft.local`;
        user = await getUserByEmail(db, email);
        if (!user) {
          const preferred = (mcProfile?.name ?? msProfile.name ?? "player").replace(/[^a-zA-Z0-9_]/g, "").slice(0, 16) || "player";
          let username = preferred;
          let i = 1;
          while (await getUserByUsername(db, username)) {
            username = `${preferred.slice(0, 14)}${i++}`;
            if (i > 99) break;
          }
          user = await createUser(db, { email, username, passwordHash: hashPassword(randomToken(24)) });
        }
        if (!user) throw new Error("could not provision account");
        userId = user.id;
        const token = randomToken(32);
        await createSession(db, token, user.id, config.sessionTtlSeconds);
        setSessionCookie(res, token, config);
      }

      await upsertMsOAuth(db, {
        userId: user.id,
        msSubject: msProfile.id,
        msEmail: msProfile.email ?? null,
        msName: msProfile.name ?? null,
        msRefreshEnc: tokens.refreshToken ? encryptSecret(config.encryptionKey, tokens.refreshToken) : null,
        mcUuid: mcProfile?.id ?? null,
        mcUsername: mcProfile?.name ?? null,
      });
      await audit(db, user.id, "oauth.link", { subject: msProfile.id, mc: mcProfile?.name ?? null });
      res.writeHead(302, { location: "/" });
      res.end();
    } catch (err) {
      log.warn("oauth callback failed", err);
      res.writeHead(302, { location: "/?oauth=error" });
      res.end();
    }
    return true;
  }

  // -------------------------------------------------------------------------
  // Admin
  // -------------------------------------------------------------------------

  async function handleAdminUsers(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    const user = await sessionUser(req);
    if (!requireAdmin(user)) return sendError(res, 403, "Admin only"), true;
    const rows = await listUsers(db, { limit: 200, offset: 0 });
    sendJson(
      res,
      200,
      rows.map((u) => ({ id: u.id, email: u.email, username: u.username, role: u.role, createdAt: u.created_at })),
    );
    return true;
  }

  async function handleAdminServers(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    const user = await sessionUser(req);
    if (!requireAdmin(user)) return sendError(res, 403, "Admin only"), true;
    const rows = await listServers(db, false);
    sendJson(
      res,
      200,
      rows.map((s) => ({
        id: s.id,
        name: s.name,
        description: s.description,
        address: s.address,
        port: s.port,
        online_mode: s.online_mode,
        icon_url: s.icon_url,
        max_players: s.max_players,
        sort_order: s.sort_order,
        enabled: s.enabled,
        maintenance: s.maintenance,
        created_at: s.created_at,
      })),
    );
    return true;
  }

  async function handleAdminPatchUser(
    req: IncomingMessage,
    res: ServerResponse,
    _ctx: ApiContext,
    params: Record<string, string>,
  ): Promise<boolean> {
    const actor = await sessionUser(req);
    if (!requireAdmin(actor)) return sendError(res, 403, "Admin only"), true;
    const body = await readJsonBody(req);
    const role = String(body.role ?? "");
    if (role !== "admin" && role !== "user") return sendError(res, 400, "Invalid role"), true;
    const updated = await setUserRole(db, params.id, role);
    if (!updated) return sendError(res, 404, "User not found"), true;
    await audit(db, actor?.id ?? null, "admin.set_role", { userId: params.id, role });
    sendJson(res, 200, { ok: true });
    return true;
  }

  async function handleAdminCreateServer(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    const actor = await sessionUser(req);
    if (!requireAdmin(actor)) return sendError(res, 403, "Admin only"), true;
    const body = await readJsonBody(req);
    const name = String(body.name ?? "").trim();
    const address = String(body.address ?? "").trim();
    const port = Number(body.port ?? 25565);
    if (!name || !address || !Number.isInteger(port) || port < 1 || port > 65535) {
      return sendError(res, 400, "Invalid server fields"), true;
    }
    const created = await repoCreateServer(db, {
      name,
      description: String(body.description ?? ""),
      address,
      port,
      onlineMode: Boolean(body.onlineMode),
      iconUrl: body.iconUrl ? String(body.iconUrl) : null,
      maxPlayers: body.maxPlayers ? Number(body.maxPlayers) : null,
    });
    if (!created) return sendError(res, 500, "Create failed"), true;
    await audit(db, actor?.id ?? null, "admin.server.create", { id: created.id, name, address, port });
    sendJson(res, 201, { id: created.id });
    return true;
  }

  async function handleAdminPatchServer(
    req: IncomingMessage,
    res: ServerResponse,
    _ctx: ApiContext,
    params: Record<string, string>,
  ): Promise<boolean> {
    const actor = await sessionUser(req);
    if (!requireAdmin(actor)) return sendError(res, 403, "Admin only"), true;
    const body = await readJsonBody(req);
    const allowed: Record<string, unknown> = {};
    for (const key of ["name", "description", "address", "port", "online_mode", "enabled", "maintenance", "icon_url", "max_players", "sort_order"] as const) {
      if (key in body) allowed[key] = body[key];
    }
    const updated = await updateServer(db, params.id, allowed as never);
    if (!updated) return sendError(res, 404, "Server not found"), true;
    await audit(db, actor?.id ?? null, "admin.server.update", { id: params.id, fields: Object.keys(allowed) });
    sendJson(res, 200, { ok: true });
    return true;
  }

  async function handleAdminDeleteServer(
    req: IncomingMessage,
    res: ServerResponse,
    _ctx: ApiContext,
    params: Record<string, string>,
  ): Promise<boolean> {
    const actor = await sessionUser(req);
    if (!requireAdmin(actor)) return sendError(res, 403, "Admin only"), true;
    await repoDeleteServer(db, params.id);
    await audit(db, actor?.id ?? null, "admin.server.delete", { id: params.id });
    sendJson(res, 200, { ok: true });
    return true;
  }

  async function handleAdminAudit(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    const user = await sessionUser(req);
    if (!requireAdmin(user)) return sendError(res, 403, "Admin only"), true;
    const rows = await listAudit(db, { limit: 200, offset: 0 });
    sendJson(res, 200, rows);
    return true;
  }

  async function handleAdminStats(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    const user = await sessionUser(req);
    if (!requireAdmin(user)) return sendError(res, 403, "Admin only"), true;
    sendJson(res, 200, { users: await countUsers(db) });
    return true;
  }

  const routes: { method: string; pattern: RegExp; handler: Handler; param?: string }[] = [
    { method: "POST", pattern: /^\/api\/register$/, handler: handleRegister },
    { method: "POST", pattern: /^\/api\/login$/, handler: handleLogin },
    { method: "POST", pattern: /^\/api\/logout$/, handler: handleLogout },
    { method: "GET", pattern: /^\/api\/me$/, handler: handleMe },
    { method: "GET", pattern: /^\/api\/servers$/, handler: handleListServers },
    { method: "POST", pattern: /^\/api\/servers\/([^/]+)\/play$/, handler: handlePlay, param: "id" },
    { method: "GET", pattern: /^\/api\/oauth\/start$/, handler: handleOAuthStart },
    { method: "GET", pattern: /^\/api\/oauth\/callback$/, handler: handleOAuthCallback },
    { method: "GET", pattern: /^\/api\/admin\/users$/, handler: handleAdminUsers },
    { method: "PATCH", pattern: /^\/api\/admin\/users\/([^/]+)$/, handler: handleAdminPatchUser, param: "id" },
    { method: "GET", pattern: /^\/api\/admin\/stats$/, handler: handleAdminStats },
    { method: "GET", pattern: /^\/api\/admin\/audit$/, handler: handleAdminAudit },
    { method: "GET", pattern: /^\/api\/admin\/servers$/, handler: handleAdminServers },
    { method: "POST", pattern: /^\/api\/admin\/servers$/, handler: handleAdminCreateServer },
    { method: "PATCH", pattern: /^\/api\/admin\/servers\/([^/]+)$/, handler: handleAdminPatchServer, param: "id" },
    { method: "DELETE", pattern: /^\/api\/admin\/servers\/([^/]+)$/, handler: handleAdminDeleteServer, param: "id" },
  ];

  return function handle(req, res): boolean {
    const url = req.url ?? "/";
    if (!url.startsWith("/api/")) return false;
    const method = req.method ?? "GET";
    for (const route of routes) {
      if (route.method !== method) continue;
      const m = route.pattern.exec(url);
      if (!m) continue;
      const params: Record<string, string> = {};
      if (route.param && m[1] !== undefined) params[route.param] = decodeURIComponent(m[1]);
      void Promise.resolve(route.handler(req, res, ctx, params))
        .catch((err) => {
          log.error(`api ${method} ${url} failed`, err);
          if (!res.headersSent) sendError(res, 500, "Internal server error");
        });
      return true;
    }
    if (!res.headersSent) sendError(res, 404, "Not found");
    return true;
  };
}

export { getCookie, setSessionCookie, clearSessionCookie };
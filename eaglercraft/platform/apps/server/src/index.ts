/**
 * Eagler Platform server entrypoint.
 *
 * Boundaries:
 *  - HTTP API under /api (accounts, sessions, servers, play tickets, MS OAuth)
 *  - static client under /client, web SPA under /
 *  - Eagler WebSocket bridge under /eagler (the only place websockets live)
 */

import { createServer } from "node:http";
import { loadEnvIfPresent } from "./loadEnv.js";
import { createDb } from "./db.js";
import { loadConfig } from "./config.js";
import { log } from "./logger.js";
import { runMigrations } from "./migrate.js";
import { createApi } from "./http/api.js";
import { createStaticHandler } from "./http/static.js";
import { createBridge } from "./bridge/eaglerServer.js";
import { createServer as repoCreateServer } from "./repo.js";

loadEnvIfPresent();
const config = loadConfig();
const db = createDb(config.databaseUrl);

async function main(): Promise<void> {
  if (process.env.RUN_MIGRATIONS !== "false") {
    const ran = await runMigrations(config.databaseUrl);
    log.info(`schema migrations applied: ${ran}`);
  }

  await seedServers();

  const server = createServer((req, res) => {
    const handled =
      createApi({ config, db })(req, res) || createStaticHandler(config)(req, res);
    if (!handled) {
      res.writeHead(404, { "content-type": "text/plain" });
      res.end("Not found");
    }
  });

  const bridge = createBridge(server, {
    path: config.bridgePath,
    db,
    onTicketInvalid: (ticket, ip) => log.warn(`invalid play ticket from ${ip} (${ticket.slice(0, 8)}…)`),
  });

  server.listen(config.listenPort, config.listenHost, () => {
    log.info(`listening on http://${config.listenHost}:${config.listenPort}`);
    log.info(`bridge path: ${config.bridgePath}`);
  });

  const shutdown = (signal: string) => {
    log.info(`received ${signal}, shutting down`);
    server.close(() => process.exit(0));
    void bridge;
    setTimeout(() => process.exit(1), 5000).unref();
  };
  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));
}

async function seedServers(): Promise<void> {
  const raw = process.env.SEED_SERVERS_JSON;
  if (!raw) return;
  let list: { name: string; address: string; port?: number; onlineMode?: boolean }[];
  try {
    list = JSON.parse(raw);
  } catch {
    log.warn("SEED_SERVERS_JSON is not valid JSON; ignoring");
    return;
  }
  for (const entry of list) {
    if (!entry.name || !entry.address) continue;
    const existing = await repoCreateServer(db, {
      name: entry.name,
      address: entry.address,
      port: entry.port ?? 25565,
      onlineMode: entry.onlineMode ?? false,
    });
    if (existing) log.info(`seeded server '${entry.name}' -> ${entry.address}:${entry.port ?? 25565}`);
  }
}

void main().catch((err) => {
  log.error("fatal startup error", err);
  process.exit(1);
});
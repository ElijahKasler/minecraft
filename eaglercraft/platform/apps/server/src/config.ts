import { dirname, isAbsolute, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export interface Config {
  nodeEnv: string;
  publicOrigin: string;
  listenHost: string;
  listenPort: number;
  databaseUrl: string;
  sessionCookieName: string;
  sessionTtlSeconds: number;
  cookieSecure: boolean;
  encryptionKey: Buffer;
  msClientId: string | undefined;
  msClientSecret: string | undefined;
  msTenant: string;
  clientStaticDir: string;
  webStaticDir: string;
  bridgePath: string;
  playTicketTtlSeconds: number;
  rateLimitAuthPerMin: number;
  bootstrapAdminEmail: string;
}

function fail(name: string, hint: string): never {
  throw new Error(`Missing/invalid env var ${name}. ${hint}`);
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const encryptionKeyHex = env.ENCRYPTION_KEY;
  if (!encryptionKeyHex || encryptionKeyHex.length < 32) {
    fail(
      "ENCRYPTION_KEY",
      "Set a random hex string >= 32 chars (used to encrypt OAuth tokens at rest and play-ticket signing).",
    );
  }
  const encryptionKey = Buffer.from(encryptionKeyHex, "hex");
  if (encryptionKey.length < 16) {
    fail("ENCRYPTION_KEY", "must decode to at least 16 bytes of hex.");
  }

  const listenPort = Number(env.LISTEN_PORT ?? "3007");
  const sessionTtlSeconds = Number(env.SESSION_TTL_SECONDS ?? "604800");
  const playTicketTtlSeconds = Number(env.PLAY_TICKET_TTL_SECONDS ?? "120");
  const rateLimitAuthPerMin = Number(env.RATE_LIMIT_AUTH_PER_MIN ?? "20");

  const hereDir = dirname(fileURLToPath(import.meta.url)); // .../apps/server/src or .../apps/server/dist
  const serverRoot = resolve(hereDir, ".."); // .../apps/server
  const platformRoot = resolve(serverRoot, "..", ".."); // .../platform
  const resolveDir = (v: string | undefined, fallback: string): string => {
    if (!v) return fallback;
    return isAbsolute(v) ? v : resolve(platformRoot, v);
  };

  return {
    nodeEnv: env.NODE_ENV ?? "development",
    publicOrigin: env.PUBLIC_ORIGIN ?? "http://localhost:3007",
    listenHost: env.LISTEN_HOST ?? "0.0.0.0",
    listenPort,
    databaseUrl: env.DATABASE_URL ?? "postgres://eagler:eagler@localhost:5432/eagler",
    sessionCookieName: env.SESSION_COOKIE_NAME ?? "sid",
    sessionTtlSeconds,
    cookieSecure: (env.COOKIE_SECURE ?? "false") === "true",
    encryptionKey,
    msClientId: env.MS_CLIENT_ID || undefined,
    msClientSecret: env.MS_CLIENT_SECRET || undefined,
    msTenant: env.MS_TENANT ?? "consumers",
    clientStaticDir: resolveDir(env.CLIENT_STATIC_DIR, resolve(platformRoot, "..", "target_teavm_javascript", "javascript")),
    webStaticDir: resolveDir(env.WEB_STATIC_DIR, resolve(platformRoot, "apps/web/dist")),
    bridgePath: env.BRIDGE_PATH ?? "/eagler",
    playTicketTtlSeconds,
    rateLimitAuthPerMin,
    bootstrapAdminEmail: env.BOOTSTRAP_ADMIN_EMAIL ?? "admin@example.com",
  };
}

export type AppConfig = Config;
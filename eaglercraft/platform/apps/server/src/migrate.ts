import { readdir, readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { createDb, query } from "./db.js";
import { log } from "./logger.js";
import { loadConfig } from "./config.js";
import { loadEnvIfPresent } from "./loadEnv.js";

loadEnvIfPresent();

const MIGRATIONS_DIR = fileURLToPath(
  new URL("../../../database/migrations/", import.meta.url),
);

export async function runMigrations(databaseUrl: string): Promise<number> {
  const db = createDb(databaseUrl);
  try {
    await query(
      db,
      `create table if not exists schema_migrations (
         version text primary key,
         applied_at timestamptz not null default now()
       )`,
    );

    const files = (await readdir(MIGRATIONS_DIR))
      .filter((f) => /\.sql$/.test(f))
      .sort();
    const appliedRows = await query<{ version: string }>(
      db,
      `select version from schema_migrations`,
    );
    const applied = new Set(appliedRows.map((r) => r.version));

    let ran = 0;
    for (const file of files) {
      if (applied.has(file)) continue;
      const sql = await readFile(`${MIGRATIONS_DIR}/${file}`, "utf8");
      const client = await db.pool.connect();
      try {
        await client.query("BEGIN");
        await client.query(sql);
        await client.query("INSERT INTO schema_migrations(version) VALUES ($1)", [file]);
        await client.query("COMMIT");
        log.info(`migrated ${file}`);
        ran++;
      } catch (err) {
        await client.query("ROLLBACK");
        throw err;
      } finally {
        client.release();
      }
    }
    log.info(`migrations complete (${ran} new)`);
    return ran;
  } finally {
    await db.pool.end();
  }
}

// tsx src/migrate.ts -> runs migrations and exits.
await runMigrations(loadConfig().databaseUrl);
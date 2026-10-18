import pg from "pg";
import { log } from "./logger.js";

const { Pool } = pg;

export interface Db {
  pool: pg.Pool;
}

export function createDb(connectionString: string): Db {
  const pool = new Pool({ connectionString, max: 10 });
  pool.on("error", (err) => log.error("pg pool error", err));
  return { pool };
}

export type Row = Record<string, unknown>;
export type Rows = Row[];

export async function query<T extends object = Row>(
  db: Db,
  text: string,
  params: unknown[] = [],
): Promise<T[]> {
  const res = await db.pool.query<T>(text, params);
  return res.rows;
}

export async function queryOne<T extends object = Row>(
  db: Db,
  text: string,
  params: unknown[] = [],
): Promise<T | undefined> {
  const rows = await query<T>(db, text, params);
  return rows[0];
}

export async function withTx<T>(
  db: Db,
  fn: (db: Db) => Promise<T>,
): Promise<T> {
  const client = await db.pool.connect();
  try {
    await client.query("BEGIN");
    const tx: Db = {
      pool: {
        query: <R extends object = Row>(text: string, params: unknown[] = []) =>
          client.query<R>(text, params as never[]),
      } as unknown as pg.Pool,
    };
    const out = await fn(tx);
    await client.query("COMMIT");
    return out;
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
}
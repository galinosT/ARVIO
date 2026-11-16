"use strict";
/**
 * Applies netlify-auth-site/netlify/database/migrations/<id>/migration.sql in order, once each,
 * recorded in gmc_schema_migrations. The two sample "planets" migrations from Netlify's template
 * are skipped. A new upstream migration is applied on the next start.
 */
const fs = require("node:fs");
const path = require("node:path");

const SITE = path.resolve(process.env.GMC_SITE_DIR || path.join(__dirname, "..", "netlify-auth-site"));
const DIR = path.join(SITE, "netlify", "database", "migrations");
const SKIP = /_(create|seed)_planets$/;

async function migrate() {
  const { Pool } = require(require.resolve("pg", { paths: [SITE] }));
  const pool = new Pool({ connectionString: process.env.DATABASE_URL || process.env.NETLIFY_DB_URL, max: 1 });
  const client = await pool.connect();
  try {
    await client.query("SELECT pg_advisory_lock(73517)");
    await client.query("CREATE TABLE IF NOT EXISTS gmc_schema_migrations (id text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
    const done = new Set((await client.query("SELECT id FROM gmc_schema_migrations")).rows.map((r) => r.id));
    const ids = fs.readdirSync(DIR).filter((d) => fs.existsSync(path.join(DIR, d, "migration.sql"))).sort();
    for (const id of ids) {
      if (done.has(id) || SKIP.test(id)) continue;
      const sql = fs.readFileSync(path.join(DIR, id, "migration.sql"), "utf8");
      await client.query("BEGIN");
      try {
        await client.query(sql);
        await client.query("INSERT INTO gmc_schema_migrations (id) VALUES ($1)", [id]);
        await client.query("COMMIT");
        console.log(`[migrate] applied ${id}`);
      } catch (e) {
        await client.query("ROLLBACK");
        throw new Error(`Migration ${id} failed: ${e.message}`);
      }
    }
  } finally {
    await client.query("SELECT pg_advisory_unlock(73517)").catch(() => {});
    client.release();
    await pool.end();
  }
}

module.exports = { migrate };
if (require.main === module) migrate().then(() => console.log("[migrate] up to date")).catch((e) => { console.error(e.message); process.exit(1); });

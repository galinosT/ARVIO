"use strict";
/**
 * GMC Cloud account admin. Run inside the container:
 *   docker compose exec gmc-cloud node gmc-cloud/admin.cjs list
 *   docker compose exec gmc-cloud node gmc-cloud/admin.cjs create <email>     (asks for the password)
 *   docker compose exec gmc-cloud node gmc-cloud/admin.cjs passwd <email>     (asks for the new password)
 *
 * Public signup is closed on GMC Cloud; this is the only way an account is created.
 * Passwords are read from the terminal (or GMC_ADMIN_PASSWORD) and never printed.
 */
const path = require("node:path");
const crypto = require("node:crypto");
const readline = require("node:readline");
const { startBlobs, secretFile, SITE } = require("./server.cjs");

async function askPassword(label) {
  if (process.env.GMC_ADMIN_PASSWORD) return process.env.GMC_ADMIN_PASSWORD;
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  rl._writeToOutput = (s) => { if (!rl.muted) rl.output.write(s); };
  const ask = (q) => new Promise((resolve) => { rl.muted = false; rl.output.write(q); rl.muted = true; rl.question("", (a) => { rl.output.write("\n"); resolve(a); }); });
  const first = await ask(`${label}: `);
  const second = await ask("Again: ");
  rl.close();
  if (first !== second) throw new Error("The two passwords differ");
  if (first.length < 6) throw new Error("At least 6 characters");
  return first;
}

function fakeEvent(body) {
  return {
    httpMethod: "POST",
    headers: { "content-type": "application/json", apikey: process.env.APP_ANON_KEY, "x-nf-site-id": "gmc-cloud", "x-nf-deploy-id": "gmc-cloud", "x-nf-client-connection-ip": "127.0.0.1" },
    body: JSON.stringify(body),
    queryStringParameters: {},
    blobs: Buffer.from(JSON.stringify({ url: "", token: "" })).toString("base64")
  };
}

async function main() {
  const [cmd, emailArg] = process.argv.slice(2);
  if (!process.env.ARVIO_AUTH_SECRET) process.env.ARVIO_AUTH_SECRET = secretFile(".auth-secret");
  const blobs = await startBlobs();
  const backend = require(path.join(SITE, "netlify", "functions", "_backend.js"));
  const { getStore } = require(require.resolve("@netlify/blobs", { paths: [SITE] }));
  const store = getStore("arvio-auth");
  const email = emailArg ? backend.normalizeEmail(emailArg) : "";
  const key = email ? `accounts/email/${crypto.createHash("sha256").update(email).digest("hex")}.json` : "";

  try {
    if (cmd === "list") {
      const keys = await backend.listBlobKeys(store, "accounts/email/");
      for (const k of keys) {
        const a = await store.get(k, { type: "json" });
        if (a) console.log(`${a.email}\tcreated ${a.createdAt || "?"}\tid ${a.accountId}`);
      }
      if (!keys.length) console.log("(no accounts)");
    } else if (cmd === "create" && email) {
      const password = await askPassword(`Password for ${email}`);
      const { handleCloudAuthEmail } = backend;
      const res = await handleCloudAuthEmail(fakeEvent({ email, password }));
      if (res.statusCode !== 200) throw new Error(JSON.parse(res.body).error || `HTTP ${res.statusCode}`);
      console.log(`Created ${email}`);
    } else if (cmd === "passwd" && email) {
      const account = await store.get(key, { type: "json" });
      if (!account) throw new Error(`No account ${email}`);
      const password = await askPassword(`New password for ${email}`);
      const salt = crypto.randomBytes(16).toString("base64url");
      const hash = crypto.scryptSync(password, salt, 64, { N: 16384, r: 8, p: 1 }).toString("base64url");
      await store.setJSON(key, { ...account, passwordHash: `scrypt:16384:8:1:${salt}:${hash}`, updatedAt: new Date().toISOString() });
      console.log(`Password changed for ${email}`);
    } else {
      console.log("Usage: admin.cjs list | create <email> | passwd <email>");
      process.exitCode = 2;
    }
  } finally {
    await blobs.stop?.();
    await backend.getPool?.()?.end?.().catch?.(() => {});
  }
}

main().then(() => process.exit(process.exitCode || 0)).catch((e) => { console.error(e.message); process.exit(1); });

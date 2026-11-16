"use strict";
/**
 * GMC Cloud account admin. Run inside the container:
 *   docker compose exec gmc-cloud node gmc-cloud/admin.cjs list
 *   docker compose exec gmc-cloud node gmc-cloud/admin.cjs create <email>     (asks for the password)
 *   docker compose exec gmc-cloud node gmc-cloud/admin.cjs passwd <email>     (asks for the new password)
 *   docker compose exec -e ARVIO_PASSWORD=... gmc-cloud node gmc-cloud/admin.cjs import-arvio <email> [arvio-email]
 *     copies the ARVIO cloud snapshot of that ARVIO account (default: the same email) into the GMC
 *     Cloud account, replacing what is there. Field times are set to now so the devices take it.
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
  const [cmd, emailArg, arvioEmailArg] = process.argv.slice(2);
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
    } else if (cmd === "import-arvio" && email) {
      const account = await store.get(key, { type: "json" });
      if (!account) throw new Error(`No GMC Cloud account ${email}: create it first`);
      const arvioEmail = backend.normalizeEmail(arvioEmailArg || email);
      const arvioPassword = process.env.ARVIO_PASSWORD || "";
      if (!arvioPassword) throw new Error("Pass the ARVIO cloud password as ARVIO_PASSWORD");
      const base = (process.env.ARVIO_SOURCE_URL || "https://auth.arvio.tv/.netlify/functions").replace(/\/+$/, "");
      const key0 = process.env.APP_ANON_KEY;
      const call = async (name, body, bearer) => {
        const res = await fetch(`${base}/${name}`, {
          method: "POST",
          headers: { "content-type": "application/json", apikey: key0, authorization: `Bearer ${bearer || key0}` },
          body: JSON.stringify(body || {}), signal: AbortSignal.timeout(30000)
        });
        const json = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(`ARVIO ${name}: ${json.error || json.message || res.status}`);
        return json;
      };
      const login = await call("auth-login", { email: arvioEmail, password: arvioPassword });
      const arvioToken = login.access_token || login.accessToken;
      if (!arvioToken) throw new Error("ARVIO sign-in returned no token");
      const pulled = await call("account-sync-pull", {}, arvioToken);
      let payload = pulled.payload;
      if (!payload) throw new Error(`The ARVIO cloud has no saved settings for ${arvioEmail}`);
      if (typeof payload === "string") payload = JSON.parse(payload);
      // Newer than anything the devices hold, so they take the imported values.
      const now = Date.now();
      if (payload.fieldUpdatedAt && typeof payload.fieldUpdatedAt === "object") {
        for (const k of Object.keys(payload.fieldUpdatedAt)) payload.fieldUpdatedAt[k] = now;
      }
      if ("updatedAt" in payload) payload.updatedAt = now;
      const event = fakeEvent({});
      event.headers.authorization = `Bearer ${(backend.signArvioAccessToken || backend._test.signArvioAccessToken)(account)}`;
      const identity = await backend.resolveIdentity(event);
      const metrics = backend.payloadMetrics(payload);
      for (let attempt = 0; attempt < 5; attempt += 1) {
        const { etag } = await backend.loadSnapshotForUpdate(event, identity);
        const saved = await backend.saveSnapshotToBlobs(event, identity, {
          payload: metrics.payload, payloadVersion: metrics.payloadVersion, restoreRank: metrics.restoreRank,
          profileCount: metrics.profileCount, scopedCoverage: metrics.scopedCoverage,
          payloadUpdatedAt: metrics.payloadUpdatedAt, source: "netlify"
        }, { etag });
        if (!saved) continue;
        await backend.appendSnapshotEvent(event, identity, saved);
        const names = (payload.profiles || []).map((p) => p && p.name).filter(Boolean);
        console.log(`Imported the ARVIO cloud settings of ${arvioEmail} into ${email}: ${metrics.profileCount ?? names.length} profile(s)${names.length ? ` (${names.join(", ")})` : ""}, ${Math.round(JSON.stringify(payload).length / 1024)} KB`);
        return;
      }
      throw new Error("Could not write the snapshot (conflict); try again");
    } else {
      console.log("Usage: admin.cjs list | create <email> | passwd <email> | import-arvio <email> [arvio-email]");
      process.exitCode = 2;
    }
  } finally {
    await blobs.stop?.();
    await backend.getPool?.()?.end?.().catch?.(() => {});
  }
}

main().then(() => process.exit(process.exitCode || 0)).catch((e) => { console.error(e.message); process.exit(1); });

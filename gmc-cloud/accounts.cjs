"use strict";
/**
 * GMC Cloud account operations, shared by the terminal script (admin.cjs) and the admin page
 * (server.cjs, /admin). Needs the Blobs context set (startBlobs) before the first call.
 * Every function returns a short English message for the operator, or throws with one.
 */
const path = require("node:path");
const crypto = require("node:crypto");
const { SITE } = require("./paths.cjs");

let cached = null;
function deps() {
  if (cached) return cached;
  const backend = require(path.join(SITE, "netlify", "functions", "_backend.js"));
  const { getStore } = require(require.resolve("@netlify/blobs", { paths: [SITE] }));
  cached = { backend, store: getStore("arvio-auth") };
  return cached;
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

function normalize(email) {
  return deps().backend.normalizeEmail(email || "");
}

function accountKey(email) {
  return `accounts/email/${crypto.createHash("sha256").update(normalize(email)).digest("hex")}.json`;
}

async function loadAccount(email) {
  const account = await deps().store.get(accountKey(email), { type: "json" });
  if (!account) throw new Error(`No account ${normalize(email)}`);
  return account;
}

function checkPassword(password) {
  if (typeof password !== "string" || password.length < 6) throw new Error("The password needs at least 6 characters");
}

async function list() {
  const { backend, store } = deps();
  const keys = await backend.listBlobKeys(store, "accounts/email/");
  const out = [];
  for (const k of keys) {
    const a = await store.get(k, { type: "json" });
    if (a) out.push({ email: a.email, createdAt: a.createdAt || null, accountId: a.accountId });
  }
  return out.sort((a, b) => String(a.email).localeCompare(String(b.email)));
}

async function create(email, password) {
  checkPassword(password);
  const res = await deps().backend.handleCloudAuthEmail(fakeEvent({ email: normalize(email), password }));
  if (res.statusCode !== 200) throw new Error(JSON.parse(res.body).error || `HTTP ${res.statusCode}`);
  return `Created ${normalize(email)}`;
}

async function passwd(email, password) {
  checkPassword(password);
  const account = await loadAccount(email);
  const salt = crypto.randomBytes(16).toString("base64url");
  const hash = crypto.scryptSync(password, salt, 64, { N: 16384, r: 8, p: 1 }).toString("base64url");
  await deps().store.setJSON(accountKey(email), { ...account, passwordHash: `scrypt:16384:8:1:${salt}:${hash}`, updatedAt: new Date().toISOString() });
  return `Password changed for ${normalize(email)}`;
}

/** Writes [payload] as the account's snapshot (replacing it). */
async function writeSnapshot(account, mutate) {
  const { backend } = deps();
  const event = fakeEvent({});
  event.headers.authorization = `Bearer ${(backend.signArvioAccessToken || backend._test.signArvioAccessToken)(account)}`;
  const identity = await backend.resolveIdentity(event);
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const { snapshot, etag } = await backend.loadSnapshotForUpdate(event, identity);
    let current = snapshot?.payload;
    if (typeof current === "string") current = JSON.parse(current);
    const { payload, result } = mutate(current);
    const metrics = backend.payloadMetrics(payload);
    const saved = await backend.saveSnapshotToBlobs(event, identity, {
      payload: metrics.payload, payloadVersion: metrics.payloadVersion, restoreRank: metrics.restoreRank,
      profileCount: metrics.profileCount, scopedCoverage: metrics.scopedCoverage,
      payloadUpdatedAt: metrics.payloadUpdatedAt, source: "netlify"
    }, { etag });
    if (!saved) continue;
    await backend.appendSnapshotEvent(event, identity, saved);
    return { metrics, result };
  }
  throw new Error("Could not write the settings (conflict); try again");
}

async function importArvio(email, arvioEmail, arvioPassword) {
  const account = await loadAccount(email);
  const source = normalize(arvioEmail || email);
  if (!arvioPassword) throw new Error("The ARVIO cloud password is required");
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
  const login = await call("auth-login", { email: source, password: arvioPassword });
  const token = login.access_token || login.accessToken;
  if (!token) throw new Error("ARVIO sign-in returned no token");
  const pulled = await call("account-sync-pull", {}, token);
  let payload = pulled.payload;
  if (!payload) throw new Error(`The ARVIO cloud has no saved settings for ${source}`);
  if (typeof payload === "string") payload = JSON.parse(payload);
  // Newer than anything the devices hold, so they take the imported values. The official
  // app's Trakt sign-in stays behind: GMC keeps Trakt on each box.
  stampNow(payload);
  delete payload.traktTokens;
  payload.traktLinked = false;
  const { metrics } = await writeSnapshot(account, () => ({ payload }));
  const names = (payload.profiles || []).map((p) => p && p.name).filter(Boolean);
  return `Imported the ARVIO cloud settings of ${source} into ${normalize(email)}: ${metrics.profileCount ?? names.length} profile(s)${names.length ? ` (${names.join(", ")})` : ""}`;
}

/**
 * Marks every value of a snapshot as changed now, so the boxes take the cloud's values over their
 * own: the per-field times, the add-on list time and each add-on change, and the subtitle time.
 * A box set up after the settings were written otherwise keeps its fresh defaults.
 */
function stampNow(payload, now = Date.now()) {
  if (payload.fieldUpdatedAt && typeof payload.fieldUpdatedAt === "object") {
    for (const k of Object.keys(payload.fieldUpdatedAt)) payload.fieldUpdatedAt[k] = now;
  }
  for (const k of ["updatedAt", "addonsUpdatedAt", "subtitleSettingsUpdatedAt"]) if (k in payload) payload[k] = now;
  if (payload.addonChanges && typeof payload.addonChanges === "object") {
    for (const c of Object.values(payload.addonChanges)) if (c && typeof c === "object" && "updatedAt" in c) c.updatedAt = now;
  }
  return payload;
}

/** The account's saved settings (payload object), or null when it has none. */
async function readSnapshot(account) {
  const { backend } = deps();
  const event = fakeEvent({});
  event.headers.authorization = `Bearer ${(backend.signArvioAccessToken || backend._test.signArvioAccessToken)(account)}`;
  const identity = await backend.resolveIdentity(event);
  const { snapshot } = await backend.loadSnapshotForUpdate(event, identity);
  let payload = snapshot?.payload;
  if (typeof payload === "string") payload = JSON.parse(payload);
  return payload || null;
}

/**
 * Copies every setting of one GMC Cloud account into another, replacing what that one holds:
 * profiles, add-ons, catalogs, subtitle/AI settings (keys included) and the GMC settings.
 * Sign-ins stay behind: Trakt/Simkl/WeTrakr live on each box, and any Trakt tokens in the
 * snapshot are dropped. Field times are set to now so the devices take the copied values.
 */
async function copyAccount(fromEmail, toEmail) {
  if (normalize(fromEmail) === normalize(toEmail)) throw new Error("Pick two different accounts");
  const source = await loadAccount(fromEmail);
  const target = await loadAccount(toEmail);
  const payload = await readSnapshot(source);
  if (!payload) throw new Error(`No saved settings for ${normalize(fromEmail)}`);
  const copy = stampNow(JSON.parse(JSON.stringify(payload)));
  delete copy.traktTokens;
  copy.traktLinked = false;
  const { metrics } = await writeSnapshot(target, () => ({ payload: copy }));
  const names = (copy.profiles || []).map((p) => p && p.name).filter(Boolean);
  return `Copied the settings of ${normalize(fromEmail)} into ${normalize(toEmail)}: ${metrics.profileCount ?? names.length} profile(s)${names.length ? ` (${names.join(", ")})` : ""}. Sign in to WeTrakr/Simkl/Trakt on the boxes.`;
}

/**
 * A summary of an account's saved settings for troubleshooting sync, without secrets: profiles,
 * the active profile, add-ons and catalogs per profile (names and counts only) and the times
 * that decide which side wins a merge.
 */
async function inspect(email) {
  const account = await loadAccount(email);
  const p = await readSnapshot(account);
  if (!p) return `No saved settings for ${normalize(email)}`;
  const iso = (t) => (typeof t === "number" && t > 0 ? new Date(t).toISOString() : String(t ?? "-"));
  const names = (list) => (Array.isArray(list) ? list.map((a) => a?.name || a?.manifest?.name || a?.id || "?") : []);
  const out = [];
  out.push(`account ${normalize(email)} · version ${p.version ?? "-"} · updatedAt ${iso(p.updatedAt)}`);
  out.push(`activeProfileId ${p.activeProfileId ?? "-"}`);
  for (const pr of p.profiles || []) out.push(`profile ${pr?.id} "${pr?.name ?? ""}"`);
  out.push(`addonsUpdatedAt ${iso(p.addonsUpdatedAt)} · addonChanges ${Object.keys(p.addonChanges || {}).length}`);
  out.push(`addons (root) ${names(p.addons).length}: ${names(p.addons).join(", ")}`);
  for (const [id, list] of Object.entries(p.addonsByProfile || {})) out.push(`addonsByProfile[${id}] ${names(list).length}: ${names(list).join(", ")}`);
  for (const [id, list] of Object.entries(p.catalogsByProfile || {})) out.push(`catalogsByProfile[${id}] ${Array.isArray(list) ? list.length : 0}`);
  const f = p.fieldUpdatedAt || {};
  const keys = Object.keys(f).sort();
  out.push(`fieldUpdatedAt ${keys.length} keys`);
  for (const k of keys) if (/addon|catalog|profile/i.test(k) || /^p:/.test(k) || /^c:/.test(k)) out.push(`  ${k} ${iso(f[k])}`);
  return out.join("\n");
}

/**
 * Makes the account's saved settings win on its boxes at their next sync, without changing them:
 * for a box that was set up after the settings were written and kept its own defaults.
 */
async function touch(email) {
  const account = await loadAccount(email);
  await writeSnapshot(account, (current) => {
    if (!current) throw new Error(`No saved settings for ${normalize(email)}`);
    return { payload: stampNow(current) };
  });
  return `The saved settings of ${normalize(email)} now win on its boxes: run Sync now on each one.`;
}

async function clearTrakt(email) {
  const account = await loadAccount(email);
  const { result } = await writeSnapshot(account, (current) => {
    if (!current) throw new Error(`No saved settings for ${normalize(email)}`);
    const had = Boolean(current.traktTokens && Object.keys(current.traktTokens).length);
    delete current.traktTokens;
    current.traktLinked = false;
    return { payload: current, result: had };
  });
  return result ? `Removed the Trakt sign-in stored for ${normalize(email)}` : `No Trakt sign-in was stored for ${normalize(email)}`;
}

/** The signed-in user of a GMC access token, when it is an administrator (GMC_ADMIN_EMAILS). */
async function adminFromToken(token) {
  const admins = (process.env.GMC_ADMIN_EMAILS || "").split(",").map((s) => normalize(s.trim())).filter(Boolean);
  if (!admins.length || !token) return null;
  const { backend } = deps();
  const event = fakeEvent({});
  event.headers.authorization = `Bearer ${token}`;
  try {
    const identity = await backend.resolveIdentity(event);
    const email = normalize(identity.email);
    return admins.includes(email) ? email : null;
  } catch {
    return null;
  }
}

module.exports = { list, create, passwd, importArvio, copyAccount, inspect, touch, clearTrakt, adminFromToken, normalize };

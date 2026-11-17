"use strict";
/**
 * GMC Cloud: ARVIO's Netlify backend (netlify-auth-site) run as one Node process on a VPS.
 *
 * - /.netlify/functions/<name> runs the unchanged ARVIO function (v1 `exports.handler`
 *   or v2 `export default (Request) => Response`).
 * - Netlify Blobs are served by @netlify/blobs' own local server, files under DATA_DIR/blobs.
 * - PostgreSQL comes from DATABASE_URL; migrations run at start (see migrate.cjs).
 * - Closed environment: only the functions the app and the dashboard need are public.
 *   Accounts are created with admin.cjs, never through the public signup.
 * - The static auth pages (TV approval at /?code=, account deletion) are served as on Netlify.
 */
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { pathToFileURL } = require("node:url");

const SITE = path.resolve(process.env.GMC_SITE_DIR || path.join(__dirname, "..", "netlify-auth-site"));
const FUNCTIONS = path.join(SITE, "netlify", "functions");
const DATA = path.resolve(process.env.DATA_DIR || path.join(__dirname, "data"));
const PORT = Number(process.env.PORT || 8790);
const HOST = process.env.HOST || "0.0.0.0";

/** Public functions. Anything else answers 404, so a new upstream function stays closed until listed. */
const DEFAULT_PUBLIC = [
  "config", "auth-login", "auth-refresh",
  "account-sync-push", "account-sync-pull", "account-sync-delta", "account-sync-cursor", "account-sync-progress",
  "account-delete-start", "account-delete-status",
  "tv-auth-start", "tv-auth-poll", "tv-auth-status", "tv-auth-approve", "tv-auth-complete", "tv-auth-web",
  "entitlement-status", "stream-ticket", "stream-ticket-key",
  "tmdb-proxy", "trakt-proxy", "simkl-proxy", "sports-metadata",
  "catalog-packs-list", "app-usage-event"
];
const PUBLIC = new Set((process.env.GMC_PUBLIC_FUNCTIONS || DEFAULT_PUBLIC.join(",")).split(",").map((s) => s.trim()).filter(Boolean));

function secretFile(name) {
  const file = path.join(DATA, name);
  try { return fs.readFileSync(file, "utf8").trim(); } catch {}
  const value = crypto.randomBytes(32).toString("base64url");
  fs.mkdirSync(DATA, { recursive: true });
  fs.writeFileSync(file, value, { mode: 0o600 });
  return value;
}

let blobsContext = null;

async function startBlobs() {
  const { BlobsServer } = require(require.resolve("@netlify/blobs/server", { paths: [SITE] }));
  const token = secretFile(".blobs-token");
  const server = new BlobsServer({ directory: path.join(DATA, "blobs"), token, port: Number(process.env.BLOBS_PORT || 0) });
  const { port } = await server.start();
  const url = `http://127.0.0.1:${port}`;
  blobsContext = { url, token, siteID: "gmc-cloud", deployID: "gmc-cloud" };
  // getStore() reads globalThis.netlifyBlobsContext first. Keeping the full context there means
  // connectLambda() (which only rewrites the env variable) cannot drop uncachedEdgeURL, so
  // strong-consistency reads keep working.
  globalThis.netlifyBlobsContext = Buffer.from(JSON.stringify({
    edgeURL: url, uncachedEdgeURL: url, siteID: "gmc-cloud", deployID: "gmc-cloud", token, primaryRegion: "local"
  })).toString("base64");
  process.env.NETLIFY_BLOBS_CONTEXT = globalThis.netlifyBlobsContext;
  return server;
}

const loaded = new Map();

async function loadFunction(name) {
  if (loaded.has(name)) return loaded.get(name);
  let fn = null;
  const js = path.join(FUNCTIONS, `${name}.js`);
  const mjs = path.join(FUNCTIONS, `${name}.mjs`);
  if (fs.existsSync(js)) {
    const mod = require(js);
    if (typeof mod.handler === "function") fn = { kind: "v1", handler: mod.handler };
    else if (typeof mod.default === "function") fn = { kind: "v2", handler: mod.default };
  } else if (fs.existsSync(mjs)) {
    const mod = await import(pathToFileURL(mjs).href);
    if (typeof mod.default === "function") fn = { kind: "v2", handler: mod.default };
    else if (typeof mod.handler === "function") fn = { kind: "v1", handler: mod.handler };
  }
  loaded.set(name, fn);
  return fn;
}

function lowerHeaders(raw) {
  const out = {};
  for (const [k, v] of Object.entries(raw)) out[k.toLowerCase()] = Array.isArray(v) ? v.join(", ") : String(v);
  return out;
}

function clientIp(req, headers) {
  // Caddy sets X-Forwarded-For; trust only its first hop when the request came through loopback/docker.
  const fwd = (headers["x-forwarded-for"] || "").split(",")[0].trim();
  return fwd || req.socket.remoteAddress || "unknown";
}

/** Builds the Lambda-style event Netlify gives a v1 function. */
function lambdaEvent(req, url, body, publicUrl) {
  const headers = lowerHeaders(req.headers);
  headers["x-nf-client-connection-ip"] = clientIp(req, headers);
  headers["x-nf-site-id"] = "gmc-cloud";
  headers["x-nf-deploy-id"] = "gmc-cloud";
  const query = {};
  const multiQuery = {};
  for (const [k, v] of url.searchParams) {
    query[k] = v;
    (multiQuery[k] ||= []).push(v);
  }
  const text = isTextBody(headers["content-type"]);
  return {
    path: url.pathname,
    rawUrl: publicUrl,
    rawQuery: url.search.replace(/^\?/, ""),
    httpMethod: req.method,
    headers,
    multiValueHeaders: Object.fromEntries(Object.entries(headers).map(([k, v]) => [k, [v]])),
    queryStringParameters: query,
    multiValueQueryStringParameters: multiQuery,
    body: body.length ? (text ? body.toString("utf8") : body.toString("base64")) : null,
    isBase64Encoded: body.length > 0 && !text,
    blobs: Buffer.from(JSON.stringify({ url: blobsContext.url, token: blobsContext.token })).toString("base64")
  };
}

function isTextBody(type) {
  const t = String(type || "").toLowerCase();
  return !t || t.startsWith("text/") || t.includes("json") || t.includes("x-www-form-urlencoded") || t.includes("xml") || t.includes("javascript");
}

function publicUrlOf(req, url) {
  const proto = String(req.headers["x-forwarded-proto"] || "http").split(",")[0].trim();
  const host = req.headers["x-forwarded-host"] || req.headers.host || `localhost:${PORT}`;
  return `${proto}://${host}${url.pathname}${url.search}`;
}

function send(res, status, headers, body) {
  res.writeHead(status, headers);
  res.end(body);
}

function sendJson(res, status, value) {
  send(res, status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }, JSON.stringify(value));
}

async function runFunction(name, req, res, url, body) {
  const fn = await loadFunction(name);
  if (!fn) return sendJson(res, 404, { error: "Not found" });
  const publicUrl = publicUrlOf(req, url);

  if (fn.kind === "v2") {
    const request = new Request(publicUrl, {
      method: req.method,
      headers: Object.entries(lowerHeaders(req.headers)),
      body: ["GET", "HEAD"].includes(req.method) || !body.length ? undefined : body
    });
    const response = await fn.handler(request, { site: { id: "gmc-cloud" }, deploy: { id: "gmc-cloud" }, ip: clientIp(req, lowerHeaders(req.headers)) });
    const out = Buffer.from(await response.arrayBuffer());
    const headers = {};
    response.headers.forEach((v, k) => { headers[k] = v; });
    return send(res, response.status, headers, out);
  }

  const event = lambdaEvent(req, url, body, publicUrl);
  if (name.endsWith("-background")) {
    Promise.resolve(fn.handler(event, {})).catch((e) => console.error(`[${name}]`, e));
    return send(res, 202, {}, "");
  }
  const result = (await fn.handler(event, { clientContext: {} })) || { statusCode: 204 };
  const headers = { ...(result.headers || {}) };
  for (const [k, values] of Object.entries(result.multiValueHeaders || {})) headers[k] = values;
  const payload = result.body == null ? "" : result.isBase64Encoded ? Buffer.from(result.body, "base64") : String(result.body);
  return send(res, result.statusCode || 200, headers, payload);
}

// ---- static pages -------------------------------------------------------------------------

const STATIC_ROOTS = ["index.html", "delete-account.html", "assets", "discord", "docs"];
const REWRITES = [
  [/^\/(delete|account-deletion|delete-account)(\/.*)?$/, "/delete-account.html"],
  // The TV approval page. It lives under /tv so "/" stays free for the dashboard
  // (TV_AUTH_VERIFY_BASE_URL=https://<host>/tv makes the QR point here).
  [/^\/tv\/?$/, "/index.html"]
];
const TYPES = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
  ".json": "application/json", ".png": "image/png", ".svg": "image/svg+xml", ".ico": "image/x-icon",
  ".webp": "image/webp", ".jpg": "image/jpeg", ".woff2": "font/woff2", ".txt": "text/plain; charset=utf-8"
};

/**
 * The account page sends the web app back only to arvio.tv hosts after sign-in. Add this server's
 * own host(s) (SITE_URL, GMC_REDIRECT_HOSTS) so the GMC dashboard's "Connect to Cloud" returns home.
 */
let patchedIndex = null;
function allowOwnRedirects(data) {
  if (patchedIndex) return patchedIndex;
  const hosts = new Set((process.env.GMC_REDIRECT_HOSTS || "").split(",").map((s) => s.trim()).filter(Boolean));
  try { if (process.env.SITE_URL) hosts.add(new URL(process.env.SITE_URL).hostname); } catch {}
  let html = data.toString("utf8");
  const anchor = "const isArvioDomain =";
  const check = "(isLocal || isArvioDomain";
  if (!hosts.size || !html.includes(anchor) || !html.includes(check)) {
    if (hosts.size) console.warn("[static] index.html redirect check changed upstream; GMC hosts not added");
    patchedIndex = data;
    return data;
  }
  html = html.replace(anchor, `const isGmcHost = url.protocol === "https:" && ${JSON.stringify([...hosts])}.includes(url.hostname);\n        ${anchor}`)
    .replace(check, "(isLocal || isGmcHost || isArvioDomain");
  patchedIndex = Buffer.from(html);
  return patchedIndex;
}

// ---- GMC branding of the account pages ----------------------------------------------------

const BRANDING = path.join(__dirname, "branding");
const ASSET_OVERRIDES = {
  "assets/arvio-icon-512.png": "gmc-icon-512.png",
  "assets/arvio-mark.png": "gmc-icon-512.png",
  "assets/arvio-wordmark.png": "gmc-lockup.png"
};
// Same rule as personal/debrand.mjs: the brand word only, never identifiers or domains.
const BRAND_WORD = /(?<![A-Za-z0-9_./\-$])(ARVIO|Arvio)(?![A-Za-z0-9_])/g;
// Nothing here applies to GMC Cloud: Premium, Discord badges, ARVIO's policy pages, sign-up and
// password e-mails (accounts and passwords are managed by the administrator).
const HIDDEN = [
  ".premium-banner", "#discordEntry", "#discordJoin", "#discordClaim", "#signup", "#signupNotice", "#forgot",
  ".linkrow", 'a[href^="https://arvio.tv"]', 'a[href*="discord.gg"]'
];

const brandedPages = new Map();
function brandPage(file, data) {
  if (brandedPages.has(file)) return brandedPages.get(file);
  let html = data.toString("utf8");
  html = html
    .replace(/(["'`])https:\/\/auth\.arvio\.tv\/?\1/g, '$1/$1')
    .replace(/(["'`])https:\/\/arvio\.tv\/?\1/g, '$1/$1')
    .replace(BRAND_WORD, "GMC")
    // Relative asset paths break under /tv/; the GMC orange for the main button.
    .replace(/(src|href)="assets\//g, '$1="/assets/')
    .replace("</head>", `<style>${HIDDEN.join(",")}{display:none!important}.btn.primary{background:#FF6A3D!important;color:#0E0F12!important;box-shadow:none!important}</style>\n</head>`);
  const out = Buffer.from(html);
  brandedPages.set(file, out);
  return out;
}

function serveStatic(req, res, url) {
  let pathname = decodeURIComponent(url.pathname);
  for (const [re, to] of REWRITES) if (re.test(pathname)) pathname = to;
  if (pathname === "/") pathname = "/index.html";
  const rel = path.normalize(pathname).replace(/^([/\\])+/, "");
  const top = rel.split(/[/\\]/)[0];
  if (!STATIC_ROOTS.includes(top)) return sendJson(res, 404, { error: "Not found" });
  let file = path.join(SITE, rel);
  if (!file.startsWith(SITE + path.sep)) return sendJson(res, 404, { error: "Not found" });
  try {
    if (fs.statSync(file).isDirectory()) file = path.join(file, "index.html");
    const override = ASSET_OVERRIDES[rel.split(path.sep).join("/")];
    if (override) file = path.join(BRANDING, override);
    let data = fs.readFileSync(file);
    if (file === path.join(SITE, "index.html")) data = brandPage(file, allowOwnRedirects(data));
    else if (file === path.join(SITE, "delete-account.html")) data = brandPage(file, data);
    return send(res, 200, {
      "content-type": TYPES[path.extname(file)] || "application/octet-stream",
      "cache-control": file.endsWith(".html") ? "no-store" : "public, max-age=3600",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer"
    }, req.method === "HEAD" ? "" : data);
  } catch {
    return sendJson(res, 404, { error: "Not found" });
  }
}

// ---- scheduled functions ------------------------------------------------------------------

function schedule() {
  const daily = 24 * 60 * 60 * 1000;
  const runScheduled = async (name) => {
    try {
      const fn = await loadFunction(name);
      if (!fn) return;
      const fake = { method: "POST", headers: {}, socket: {} };
      const url = new URL(`http://localhost/.netlify/functions/${name}`);
      if (fn.kind === "v1") await fn.handler({ ...lambdaEvent(fake, url, Buffer.alloc(0), url.href), body: JSON.stringify({ next_run: null }) }, {});
      else await fn.handler(new Request(url, { method: "POST", body: "{}" }), {});
      console.log(`[schedule] ${name} done`);
    } catch (e) {
      console.error(`[schedule] ${name} failed:`, e?.message || e);
    }
  };
  // Account-deletion and retention clean-up, as on Netlify (@daily). The paid-plan workers
  // (trial e-mails, Discord) are not run: GMC Cloud has no billing.
  setTimeout(() => runScheduled("retention-cleanup"), 60 * 1000).unref();
  setInterval(() => runScheduled("retention-cleanup"), daily).unref();
}

// ---- server -------------------------------------------------------------------------------

const MAX_BODY = Number(process.env.MAX_BODY_BYTES || 25 * 1024 * 1024);

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(Object.assign(new Error("Payload too large"), { statusCode: 413 })); req.destroy(); return; }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

/** TV pairing can also create an account (intent "signup"); on GMC Cloud only admin.cjs does. */
const SIGNUP_CAPABLE = new Set(["tv-auth-web", "tv-auth-complete"]);

function wantsSignup(body) {
  try {
    const parsed = JSON.parse(body.toString("utf8") || "{}");
    const intent = String(parsed.intent || parsed.action || "signin").trim().toLowerCase();
    return intent === "signup";
  } catch {
    return true;
  }
}

// ---- admin page (/admin) ------------------------------------------------------------------
// Two locks: Caddy's basic_auth in front of /admin*, and a GMC sign-in by an account listed in
// GMC_ADMIN_EMAILS. Repeated bad tokens from one address are refused for a while.

const ADMIN_OPS = {
  list: async (_body, admin) => ({ accounts: await accountsApi().list(), admin }),
  create: async (b) => ({ message: await accountsApi().create(b.email, b.password) }),
  passwd: async (b) => ({ message: await accountsApi().passwd(b.email, b.password) }),
  "import-arvio": async (b) => ({ message: await accountsApi().importArvio(b.email, b.arvioEmail, b.arvioPassword) }),
  "clear-trakt": async (b) => ({ message: await accountsApi().clearTrakt(b.email) })
};
let accountsModule = null;
function accountsApi() {
  return (accountsModule ||= require("./accounts.cjs"));
}

const adminFailures = new Map();
function adminBlocked(ip) {
  const entry = adminFailures.get(ip);
  return Boolean(entry && entry.count >= 10 && Date.now() - entry.since < 15 * 60 * 1000);
}
function adminFailed(ip) {
  const now = Date.now();
  const entry = adminFailures.get(ip);
  if (!entry || now - entry.since > 15 * 60 * 1000) adminFailures.set(ip, { count: 1, since: now });
  else entry.count++;
}

let adminPage = null;
function serveAdminPage(res) {
  if (!adminPage) {
    adminPage = fs.readFileSync(path.join(__dirname, "admin.html"), "utf8")
      .replace("__APP_ANON_KEY__", String(process.env.APP_ANON_KEY || "").replace(/[^A-Za-z0-9._-]/g, ""));
  }
  return send(res, 200, {
    "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer", "x-frame-options": "DENY", "x-robots-tag": "noindex, nofollow"
  }, adminPage);
}

async function handleAdmin(req, res, url) {
  if (url.pathname === "/admin" || url.pathname === "/admin/") {
    if (req.method !== "GET" && req.method !== "HEAD") return sendJson(res, 405, { error: "Method not allowed" });
    return serveAdminPage(res);
  }
  const op = url.pathname.match(/^\/admin\/api\/([a-z-]+)$/)?.[1];
  if (!op || !ADMIN_OPS[op]) return sendJson(res, 404, { error: "Not found" });
  if (req.method !== "POST") return sendJson(res, 405, { error: "Method not allowed" });
  const ip = clientIp(req, lowerHeaders(req.headers));
  if (adminBlocked(ip)) return sendJson(res, 429, { error: "Too many attempts; try again later" });
  // The GMC token rides its own header: Authorization carries Caddy's basic_auth on /admin.
  const token = String(req.headers["x-gmc-token"] || "").trim();
  const admin = await accountsApi().adminFromToken(token);
  if (!admin) {
    adminFailed(ip);
    return sendJson(res, 401, { error: "Not signed in as an administrator" });
  }
  let body = {};
  try { body = JSON.parse((await readBody(req)).toString("utf8") || "{}"); } catch { return sendJson(res, 400, { error: "Bad request" }); }
  try {
    const result = await ADMIN_OPS[op](body, admin);
    console.log(`[admin] ${admin} ${op}${body.email ? ` ${accountsApi().normalize(body.email)}` : ""}`);
    return sendJson(res, 200, result);
  } catch (error) {
    return sendJson(res, 400, { error: error?.message || "Failed" });
  }
}

async function handle(req, res) {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname === "/healthz") return sendJson(res, 200, { ok: true });
  if (url.pathname === "/admin" || url.pathname.startsWith("/admin/")) return handleAdmin(req, res, url);
  const match = url.pathname.match(/^\/(?:\.netlify\/functions|api)\/([a-z0-9-]+)\/?$/);
  if (match) {
    const name = match[1];
    if (!PUBLIC.has(name)) return sendJson(res, 404, { error: "Not available on GMC Cloud" });
    const body = await readBody(req);
    if (SIGNUP_CAPABLE.has(name) && wantsSignup(body)) return sendJson(res, 403, { error: "Sign-up is closed. Ask the administrator for an account." });
    return runFunction(name, req, res, url, body);
  }
  if (req.method !== "GET" && req.method !== "HEAD") return sendJson(res, 405, { error: "Method not allowed" });
  return serveStatic(req, res, url);
}

async function main() {
  if (!process.env.ARVIO_AUTH_SECRET) process.env.ARVIO_AUTH_SECRET = secretFile(".auth-secret");
  if (!process.env.APP_ANON_KEY) throw new Error("APP_ANON_KEY is required (the key built into GMC)");
  if (!process.env.DATABASE_URL && !process.env.NETLIFY_DB_URL) throw new Error("DATABASE_URL is required");
  await require("./migrate.cjs").migrate();
  await startBlobs();
  schedule();
  http.createServer((req, res) => {
    handle(req, res).catch((error) => {
      console.error("[request]", req.method, req.url, error?.stack || error);
      if (!res.headersSent) sendJson(res, error?.statusCode || 500, { error: error?.statusCode ? error.message : "Server error" });
      else res.end();
    });
  }).listen(PORT, HOST, () => console.log(`GMC Cloud on ${HOST}:${PORT}, data in ${DATA}`));
}

if (require.main === module) {
  main().catch((e) => { console.error(e); process.exit(1); });
}

module.exports = { startBlobs, secretFile, DATA, SITE };

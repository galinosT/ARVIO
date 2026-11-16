#!/usr/bin/env node
// GMC: removes the ARVIO name and logos from a fresh checkout, right before a build.
// Upstream files stay untouched in Git (so syncing with ARVIO keeps merging cleanly);
// the personal build (personalize.py) and the GMC dashboard image (gmc-cloud/dashboard.Dockerfile)
// run this on their own copy.
//
//   node personal/debrand.mjs --res app/src/main/res      Android strings (all languages)
//   node personal/debrand.mjs --web web                   web app text, translations, logos, icons
//   node personal/debrand.mjs --kotlin app/src/main/kotlin text inside Kotlin string literals
//
// Only the brand word is replaced, as a whole word: "ARVIO" / "Arvio" -> "GMC". Identifiers
// (ARVIO_*, NEXT_PUBLIC_ARVIO_*, avatarIdToArvio), lower-case ids, domains and file names are left
// alone, so nothing that code or the servers match on changes.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const BRAND = /(?<![A-Za-z0-9_./\-$])(ARVIO|Arvio)(?![A-Za-z0-9_])/g;
const NAME = "GMC";

export function debrandText(text) {
  return text.replace(BRAND, NAME);
}

function walk(dir, exts, skip, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (skip.some((s) => full.includes(s))) continue;
    if (entry.isDirectory()) walk(full, exts, skip, out);
    else if (exts.some((e) => entry.name.endsWith(e))) out.push(full);
  }
  return out;
}

function rewrite(files) {
  let changed = 0;
  for (const file of files) {
    const before = fs.readFileSync(file, "utf8");
    const after = debrandText(before);
    if (after !== before) {
      fs.writeFileSync(file, after);
      changed++;
    }
  }
  return changed;
}

function debrandRes(res) {
  const files = fs.readdirSync(res, { withFileTypes: true })
    .filter((d) => d.isDirectory() && d.name.startsWith("values"))
    .flatMap((d) => walk(path.join(res, d.name), [".xml"], []));
  return rewrite(files);
}

// Kotlin: only inside string literals, so enum constants and identifiers keep their names.
// GMC's own files talk about the original ARVIO on purpose; the User-Agent stays as servers know it.
const KOTLIN_SKIP = [/[\\/]Gmc[^\\/]*\.kt$/, /[\\/]OkHttpProvider\.kt$/];
const PROTOCOL_LINE = /User-Agent|userAgent|X-Plex|MediaBrowser|Product/i;
const KOTLIN_STRING = /"""[\s\S]*?"""|"(?:[^"\\\n]|\\.)*"/g;

function debrandKotlin(dir) {
  const files = walk(dir, [".kt"], []).filter((f) => !KOTLIN_SKIP.some((re) => re.test(f)));
  let changed = 0;
  for (const file of files) {
    const before = fs.readFileSync(file, "utf8");
    const after = before.replace(KOTLIN_STRING, (literal, offset) => {
      // Protocol names (User-Agent, Plex/Jellyfin client ids) stay what the servers expect.
      const lineStart = before.lastIndexOf("\n", offset) + 1;
      const lineEnd = before.indexOf("\n", offset + literal.length);
      const line = before.slice(lineStart, lineEnd < 0 ? undefined : lineEnd);
      return PROTOCOL_LINE.test(line) ? literal : debrandText(literal);
    });
    if (after !== before) {
      fs.writeFileSync(file, after);
      changed++;
    }
  }
  return changed;
}

function debrandWeb(web) {
  // Server routes compare literal values with what other services send; leave them alone.
  const skip = [`${path.sep}api${path.sep}`, `${path.sep}server${path.sep}`, `${path.sep}node_modules${path.sep}`];
  const files = [
    ...walk(path.join(web, "app"), [".ts", ".tsx"], skip),
    ...walk(path.join(web, "components"), [".ts", ".tsx"], skip),
    ...walk(path.join(web, "lib"), [".ts", ".tsx", ".json"], skip),
    ...walk(path.join(web, "public", "i18n"), [".json"], skip),
    path.join(web, "public", "manifest.webmanifest")
  ].filter((f) => fs.existsSync(f));
  const changed = rewrite(files);

  // Logos and icons: the GMC files under the names the app already uses.
  const brand = path.resolve(here, "..", "gmc-cloud", "branding");
  const pub = path.join(web, "public");
  const copies = {
    "arvio-logo.svg": "gmc-logo.svg",
    "arvio-wordmark.svg": "gmc-wordmark.svg",
    "arvio-icon-192.png": "gmc-icon-192.png",
    "arvio-icon-512.png": "gmc-icon-512.png",
    "arvio-icon-maskable-192.png": "gmc-icon-maskable-192.png",
    "arvio-icon-maskable-512.png": "gmc-icon-maskable-512.png",
    "apple-touch-icon.png": "apple-touch-icon.png",
    "apple-touch-icon-precomposed.png": "apple-touch-icon.png",
    "apple-touch-icon-v3.png": "apple-touch-icon.png",
    "favicon-32.png": "favicon-32.png",
    "favicon.ico": "favicon.ico"
  };
  for (const [target, source] of Object.entries(copies)) {
    fs.copyFileSync(path.join(brand, source), path.join(pub, target));
  }
  // The manifest's theme follows the GMC colours.
  const manifest = path.join(pub, "manifest.webmanifest");
  if (fs.existsSync(manifest)) {
    const m = JSON.parse(fs.readFileSync(manifest, "utf8"));
    m.name = NAME;
    m.short_name = NAME;
    m.description = "GMC media center";
    m.background_color = "#0E0F12";
    m.theme_color = "#0E0F12";
    fs.writeFileSync(manifest, JSON.stringify(m, null, 2) + "\n");
  }
  return { changed, logos: Object.keys(copies).length };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const res = args.indexOf("--res");
  const web = args.indexOf("--web");
  const kotlin = args.indexOf("--kotlin");
  if (res < 0 && web < 0 && kotlin < 0) {
    console.error("usage: debrand.mjs --res <android res dir> | --web <web dir> | --kotlin <kotlin source dir>");
    process.exit(2);
  }
  if (kotlin >= 0) console.log(`debrand: ${debrandKotlin(path.resolve(args[kotlin + 1]))} Kotlin file(s)`);
  if (res >= 0) console.log(`debrand: ${debrandRes(path.resolve(args[res + 1]))} Android string file(s)`);
  if (web >= 0) {
    const r = debrandWeb(path.resolve(args[web + 1]));
    console.log(`debrand: ${r.changed} web file(s), ${r.logos} logo/icon file(s)`);
  }
}

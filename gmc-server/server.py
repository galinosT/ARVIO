"""
GMC subtitle memory: one place where every GMC box keeps the subtitle lines it has translated, so
any box can reuse them (a rewatch, the other Shield, resuming after a restart) without paying for
the translation again. It also keeps each series' glossary (names, forms of address).

Standard library only. Storage: one SQLite file under /data.
Auth: every /v1 request must carry "Authorization: Bearer <GMC_TOKEN>".

  POST /v1/translations/lookup  {"lang": "Greek", "texts": ["..."]}
                                -> {"found": {"<text>": "<translation>"}}
  POST /v1/translations/store   {"lang": "Greek", "model": "claude-haiku-5-5",
                                 "items": [["<text>", "<translation>"], ...]}
                                -> {"stored": n}
  GET  /v1/glossary?key=<series>&lang=Greek  -> {"glossary": "..."}  (404 when none)
  PUT  /v1/glossary             {"key": "...", "lang": "Greek", "glossary": "..."}
  POST /v1/plays                {"device": "...", "title": "...", "imdb": "tt..", "season": 2, "episode": 20,
                                 "subtitle": "ai|addon|embedded|none", "detail": "...",
                                 "memory_pct": 40, "model": "..."}   one line per playback (GMC)
  GET  /v1/plays?limit=200[&format=text]  the most recent playbacks, newest first
  POST /v1/debug                {"device", "title", "imdb", "season", "episode", "event", "detail", "trace"}
                                 sync debug (a box with "Debug συγχρονισμού" on): a title's whole scan
                                 ("play") or a manual fix ("user_ai", "user_pick", "user_delay")
  GET  /v1/debug?limit=20[&format=text]   the most recent debug events, newest first
  GET  /health                  -> {"ok": true, "lines": n}
"""

import hashlib
import hmac
import json
import os
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

TOKEN = os.environ.get("GMC_TOKEN", "")
DB_PATH = os.environ.get("GMC_DB", "/data/gmc.sqlite3")
PORT = int(os.environ.get("GMC_PORT", "8787"))
MAX_BODY = 2 * 1024 * 1024
MAX_TEXTS = 500
MAX_TEXT_LEN = 2000
MAX_PLAYS = 20000  # older playback lines are dropped beyond this
PLAY_KINDS = ("ai", "addon", "embedded", "none")
MAX_DEBUG = 3000  # debug events kept
MAX_TRACE_LEN = 20000

_lock = threading.Lock()
_db = None


def db():
    global _db
    if _db is None:
        os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
        _db = sqlite3.connect(DB_PATH, check_same_thread=False)
        _db.execute("PRAGMA journal_mode=WAL")
        _db.execute(
            "CREATE TABLE IF NOT EXISTS lines ("
            " key TEXT PRIMARY KEY, lang TEXT NOT NULL, source TEXT NOT NULL,"
            " translation TEXT NOT NULL, model TEXT NOT NULL, created INTEGER NOT NULL)"
        )
        _db.execute(
            "CREATE TABLE IF NOT EXISTS glossaries ("
            " key TEXT NOT NULL, lang TEXT NOT NULL, glossary TEXT NOT NULL,"
            " updated INTEGER NOT NULL, PRIMARY KEY (key, lang))"
        )
        _db.execute(
            "CREATE TABLE IF NOT EXISTS plays ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, device TEXT NOT NULL,"
            " title TEXT NOT NULL, imdb TEXT, season INTEGER, episode INTEGER, subtitle TEXT NOT NULL,"
            " detail TEXT, memory_pct INTEGER, model TEXT)"
        )
        _db.execute(
            "CREATE TABLE IF NOT EXISTS debug ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, device TEXT NOT NULL,"
            " title TEXT NOT NULL, imdb TEXT, season INTEGER, episode INTEGER, event TEXT NOT NULL,"
            " detail TEXT, trace TEXT)"
        )
        _db.commit()
    return _db


def line_key(lang, text):
    return hashlib.sha256((lang.strip().lower() + "\n" + text).encode("utf-8")).hexdigest()


def is_claude(model):
    return model.lower().startswith("claude")


def lookup(lang, texts):
    found = {}
    keys = {line_key(lang, t): t for t in texts}
    if not keys:
        return found
    with _lock:
        cur = db().cursor()
        items = list(keys.items())
        for start in range(0, len(items), 400):
            chunk = items[start:start + 400]
            marks = ",".join("?" * len(chunk))
            for key, translation in cur.execute(
                f"SELECT key, translation FROM lines WHERE key IN ({marks})", [k for k, _ in chunk]
            ):
                found[keys[key]] = translation
    return found


def store(lang, model, items):
    """A Claude translation replaces another model's; otherwise the first one stays."""
    now = int(time.time())
    stored = 0
    with _lock:
        conn = db()
        for source, translation in items:
            key = line_key(lang, source)
            row = conn.execute("SELECT model FROM lines WHERE key = ?", (key,)).fetchone()
            if row is None:
                conn.execute(
                    "INSERT INTO lines VALUES (?, ?, ?, ?, ?, ?)",
                    (key, lang, source, translation, model, now),
                )
                stored += 1
            elif is_claude(model) and not is_claude(row[0]):
                conn.execute(
                    "UPDATE lines SET translation = ?, model = ?, created = ? WHERE key = ?",
                    (translation, model, now, key),
                )
                stored += 1
        conn.commit()
    return stored


def get_glossary(key, lang):
    with _lock:
        row = db().execute(
            "SELECT glossary FROM glossaries WHERE key = ? AND lang = ?", (key, lang.strip().lower())
        ).fetchone()
    return row[0] if row else None


def put_glossary(key, lang, glossary):
    with _lock:
        conn = db()
        conn.execute(
            "INSERT INTO glossaries VALUES (?, ?, ?, ?) ON CONFLICT(key, lang) DO UPDATE SET"
            " glossary = excluded.glossary, updated = excluded.updated",
            (key, lang.strip().lower(), glossary, int(time.time())),
        )
        conn.commit()


def _short(value, limit):
    return value.strip()[:limit] if isinstance(value, str) and value.strip() else None


def _small_int(value, low, high):
    return value if isinstance(value, int) and not isinstance(value, bool) and low <= value <= high else None


def add_play(data):
    """One playback, as a GMC box reported it. None when the report is unusable."""
    device, title = _short(data.get("device"), 80), _short(data.get("title"), 200)
    kind = data.get("subtitle")
    if not device or not title or kind not in PLAY_KINDS:
        return None
    row = (
        int(time.time()), device, title, _short(data.get("imdb"), 20),
        _small_int(data.get("season"), 0, 999), _small_int(data.get("episode"), 0, 9999), kind,
        _short(data.get("detail"), 400), _small_int(data.get("memory_pct"), 0, 100), _short(data.get("model"), 80),
    )
    with _lock:
        conn = db()
        conn.execute(
            "INSERT INTO plays (created, device, title, imdb, season, episode, subtitle, detail, memory_pct, model)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", row,
        )
        conn.execute("DELETE FROM plays WHERE id <= (SELECT MAX(id) FROM plays) - ?", (MAX_PLAYS,))
        conn.commit()
    return True


def add_debug(data):
    """One sync-debug event from a box. None when unusable."""
    device, title, event = _short(data.get("device"), 80), _short(data.get("title"), 200), _short(data.get("event"), 40)
    if not device or not title or not event:
        return None
    trace = data.get("trace") if isinstance(data.get("trace"), str) else ""
    row = (
        int(time.time()), device, title, _short(data.get("imdb"), 20),
        _small_int(data.get("season"), 0, 999), _small_int(data.get("episode"), 0, 9999), event,
        _short(data.get("detail"), 2000), trace[-MAX_TRACE_LEN:],
    )
    with _lock:
        conn = db()
        conn.execute(
            "INSERT INTO debug (created, device, title, imdb, season, episode, event, detail, trace)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", row,
        )
        conn.execute("DELETE FROM debug WHERE id <= (SELECT MAX(id) FROM debug) - ?", (MAX_DEBUG,))
        conn.commit()
    return True


def recent_debug(limit):
    with _lock:
        rows = db().execute(
            "SELECT created, device, title, imdb, season, episode, event, detail, trace"
            " FROM debug ORDER BY id DESC LIMIT ?", (limit,),
        ).fetchall()
    keys = ("created", "device", "title", "imdb", "season", "episode", "event", "detail", "trace")
    return [dict(zip(keys, r)) for r in rows]


def debug_text(events):
    out = []
    for e in events:
        episode = " S%02dE%02d" % (e["season"], e["episode"]) if e["season"] is not None and e["episode"] is not None else ""
        out.append("=== %s  %s  %s%s  [%s]" % (_local_time(e["created"]), e["device"], e["title"], episode, e["event"]))
        if e["detail"]:
            out.append(e["detail"])
        if e["trace"]:
            out.append(e["trace"])
        out.append("")
    return "\n".join(out) + "\n"


def recent_plays(limit):
    with _lock:
        rows = db().execute(
            "SELECT created, device, title, imdb, season, episode, subtitle, detail, memory_pct, model"
            " FROM plays ORDER BY id DESC LIMIT ?", (limit,),
        ).fetchall()
    keys = ("created", "device", "title", "imdb", "season", "episode", "subtitle", "detail", "memory_pct", "model")
    return [dict(zip(keys, r)) for r in rows]


def _local_time(ts):
    """Athens time when the image has time zone data, else UTC (marked)."""
    try:
        from datetime import datetime
        from zoneinfo import ZoneInfo
        return datetime.fromtimestamp(ts, ZoneInfo(os.environ.get("GMC_TZ", "Europe/Athens"))).strftime("%Y-%m-%d %H:%M")
    except Exception:
        return time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(ts))


def plays_text(plays):
    out = []
    for p in plays:
        when = _local_time(p["created"])
        episode = " S%02dE%02d" % (p["season"], p["episode"]) if p["season"] is not None and p["episode"] is not None else ""
        extra = []
        if p["model"]:
            extra.append(p["model"])
        if p["memory_pct"] is not None:
            extra.append("memory %d%%" % p["memory_pct"])
        kind = p["subtitle"] + (" (" + ", ".join(extra) + ")" if extra else "")
        out.append("  ".join(x for x in (when, p["device"], p["title"] + episode, kind, p["detail"] or "") if x))
    return "\n".join(out) + "\n"


def count_lines():
    with _lock:
        return db().execute("SELECT COUNT(*) FROM lines").fetchone()[0]


def valid_texts(value):
    return (
        isinstance(value, list)
        and len(value) <= MAX_TEXTS
        and all(isinstance(t, str) and 0 < len(t) <= MAX_TEXT_LEN for t in value)
    )


class Handler(BaseHTTPRequestHandler):
    server_version = "gmc-memory/1"

    def log_message(self, fmt, *args):  # one short line, never the request body
        sys.stderr.write("%s %s\n" % (self.command, self.path.split("?")[0]))

    def reply(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        supplied = self.headers.get("Authorization", "")
        expected = "Bearer " + TOKEN
        return bool(TOKEN) and hmac.compare_digest(supplied.encode(), expected.encode())

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return None
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            return self.reply(200, {"ok": True, "lines": count_lines()})
        if not self.authorized():
            return self.reply(401, {"error": "unauthorized"})
        if url.path == "/v1/glossary":
            q = parse_qs(url.query)
            key, lang = q.get("key", [""])[0], q.get("lang", [""])[0]
            if not key or not lang:
                return self.reply(400, {"error": "key and lang required"})
            glossary = get_glossary(key, lang)
            if glossary is None:
                return self.reply(404, {"error": "none"})
            return self.reply(200, {"glossary": glossary})
        if url.path == "/v1/plays":
            q = parse_qs(url.query)
            try:
                limit = max(1, min(int(q.get("limit", ["200"])[0]), 5000))
            except ValueError:
                return self.reply(400, {"error": "bad limit"})
            plays = recent_plays(limit)
            if q.get("format", [""])[0] == "text":
                body = plays_text(plays).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return None
            return self.reply(200, {"plays": plays})
        if url.path == "/v1/debug":
            q = parse_qs(url.query)
            try:
                limit = max(1, min(int(q.get("limit", ["20"])[0]), 500))
            except ValueError:
                return self.reply(400, {"error": "bad limit"})
            events = recent_debug(limit)
            if q.get("format", [""])[0] == "text":
                body = debug_text(events).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return None
            return self.reply(200, {"events": events})
        return self.reply(404, {"error": "not found"})

    def do_POST(self):
        if not self.authorized():
            return self.reply(401, {"error": "unauthorized"})
        data = self.body()
        if self.path == "/v1/debug":
            if not isinstance(data, dict) or add_debug(data) is None:
                return self.reply(400, {"error": "bad debug event"})
            return self.reply(200, {"ok": True})
        if self.path == "/v1/plays":
            if not isinstance(data, dict) or add_play(data) is None:
                return self.reply(400, {"error": "bad play"})
            return self.reply(200, {"ok": True})
        if not isinstance(data, dict) or not isinstance(data.get("lang"), str) or not data["lang"].strip():
            return self.reply(400, {"error": "bad request"})
        lang = data["lang"]
        if self.path == "/v1/translations/lookup":
            if not valid_texts(data.get("texts")):
                return self.reply(400, {"error": "bad texts"})
            return self.reply(200, {"found": lookup(lang, data["texts"])})
        if self.path == "/v1/translations/store":
            items = data.get("items")
            model = data.get("model") if isinstance(data.get("model"), str) else "unknown"
            if not (isinstance(items, list) and len(items) <= MAX_TEXTS and all(
                isinstance(i, list) and len(i) == 2 and all(isinstance(x, str) and 0 < len(x) <= MAX_TEXT_LEN for x in i)
                for i in items
            )):
                return self.reply(400, {"error": "bad items"})
            return self.reply(200, {"stored": store(lang, model[:80], items)})
        return self.reply(404, {"error": "not found"})

    def do_PUT(self):
        if not self.authorized():
            return self.reply(401, {"error": "unauthorized"})
        data = self.body()
        if self.path != "/v1/glossary" or not isinstance(data, dict):
            return self.reply(404, {"error": "not found"})
        key, lang, glossary = data.get("key"), data.get("lang"), data.get("glossary")
        if not all(isinstance(v, str) and v.strip() for v in (key, lang, glossary)) or len(glossary) > 20000:
            return self.reply(400, {"error": "bad glossary"})
        put_glossary(key[:200], lang, glossary)
        return self.reply(200, {"ok": True})


def main():
    if not TOKEN or len(TOKEN) < 24:
        sys.exit("GMC_TOKEN must be set (at least 24 characters)")
    db()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    sys.stderr.write("gmc-memory listening on %d\n" % PORT)
    server.serve_forever()


if __name__ == "__main__":
    main()

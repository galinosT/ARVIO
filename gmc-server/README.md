# gmc-server — subtitle memory for GMC

A small service for the GMC boxes:
- **Translation memory:** subtitle lines already translated on any box are reused by every other box, at no cost.
- **Glossaries:** each series' glossary (names, forms of address) used in Claude translation.
- **Playback log:** every box reports one line per title (five minutes in): which subtitle ended
  on screen — AI translation (with model and share from memory), an addon subtitle, a built-in
  track or none — and the last step of the subtitle scan.

Python standard library and one SQLite file. Nothing else.

## Run

```sh
cd gmc-server
echo "GMC_TOKEN=$(openssl rand -hex 24)" > .env     # the key the GMC boxes need
chmod 600 .env
docker compose up -d --build
curl -s http://127.0.0.1:8787/health              # {"ok": true, "lines": 0}
```

The container listens only on `127.0.0.1:8787`. To reach it from outside, put it behind the reverse proxy, e.g. in a Caddyfile:

```
subs.example.gr {
    reverse_proxy 127.0.0.1:8787
}
```

If Caddy itself runs in Docker, attach it to the same network as `gmc-memory` and proxy to `gmc-memory:8787` instead.

## In GMC

Settings → AI subtitles → **Subtitle memory on server (GMC)**: enter the address (`subs.example.gr`) and the `GMC_TOKEN`. You can send them from your phone using the QR code. **Check** verifies the connection.

## Playback log

```sh
TOKEN=$(grep GMC_TOKEN .env | cut -d= -f2)
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8787/v1/plays?format=text&limit=50"
```

One line per playback, newest first: time (Athens), box (its Android "Device name" and a short id),
title, `ai` / `addon` / `embedded` / `none`, and details. Without `format=text` it is JSON.
Only the latest 20,000 lines are kept.

## Ratings (home screen)

Rotten Tomatoes and Metacritic next to IMDb on the GMC home screen come from here: the server asks
MDBList once per title and keeps the answer (7 days; 1 day for titles released in the last 60 days),
so the boxes never spend MDBList's daily allowance. Needs in `.env`:

```
GMC_MDBLIST_KEY=<your MDBList API key>   # mdblist.com → Preferences → API key
GMC_MDBLIST_DAILY=800                     # optional; free tier allows 1000 requests a day
```

Without the key the endpoint answers with no ratings and the boxes show only IMDb.

## Sync debug

A box with **Settings → AI subtitles → Debug συγχρονισμού** on also sends, per title, the whole
subtitle scan (the player's own log lines), and every manual fix — switching to AI, picking another
subtitle, changing the delay — with what was on screen and the scan that put it there:

```sh
curl -s -H "Authorization: Bearer $(grep '^GMC_TOKEN=' .env | cut -d= -f2-)" "http://127.0.0.1:8787/v1/debug?format=text&limit=5"
```

Only the latest 3,000 events are kept.

## Update

Put the new `server.py` and `Dockerfile` in place (the data folder and `.env` stay), then:

```sh
docker compose up -d --build
```

New tables are created on start; existing data is untouched.

## Backup

Everything is in `data/gmc.sqlite3`.

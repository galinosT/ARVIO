# gmc-server — subtitle memory for GMC

A small service for the GMC boxes:
- **Translation memory:** subtitle lines already translated on any box are reused by every other box, at no cost.
- **Glossaries:** each series' glossary (names, forms of address) used in Claude translation.

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

## Backup

Everything is in `data/gmc.sqlite3`.

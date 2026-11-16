# GMC Cloud

ARVIO's cloud backend (`netlify-auth-site/`) run on a VPS instead of Netlify, for a closed group
of GMC accounts. The ARVIO functions run unchanged; `server.cjs` only supplies what Netlify did:

| Netlify | GMC Cloud |
| --- | --- |
| Functions at `/.netlify/functions/<name>` | Same paths, loaded from `netlify-auth-site/netlify/functions` |
| Netlify Blobs | `@netlify/blobs` local server, files in `data/blobs` |
| Netlify Database | PostgreSQL container; `migrate.cjs` applies `netlify/database/migrations` at start |
| Scheduled functions | `retention-cleanup` daily (billing workers are not run) |
| Static auth site | `/tv` (TV QR approval) and `/delete-account` |

Closed environment: only the functions in `DEFAULT_PUBLIC` (server.cjs) answer; sign-up through
`cloud-auth-email` or TV pairing is refused. Accounts are managed with `admin.cjs`:

```
docker compose exec gmc-cloud node gmc-cloud/admin.cjs list
read -rsp "Password: " P; echo; docker compose exec -e GMC_ADMIN_PASSWORD="$P" gmc-cloud node gmc-cloud/admin.cjs create someone@example.com; unset P
read -rsp "Password: " P; echo; docker compose exec -e GMC_ADMIN_PASSWORD="$P" gmc-cloud node gmc-cloud/admin.cjs passwd someone@example.com; unset P
```

(The interactive password prompt of admin.cjs did not show through `docker compose exec` on the VPS,
so the password is read by the shell and passed in.)

Following upstream: after merging ARVIO into `galinos`, `docker compose build && docker compose up -d`.
New migrations apply on start. A new upstream function stays closed (404) until added to
`DEFAULT_PUBLIC`; check `docker compose logs` for 404s after an update.

Back up `data/` (Blobs, `.auth-secret`, `.blobs-token`) and a `pg_dump` of the database.
Losing `.auth-secret` signs every device out.

## Dashboard and Caddy

`dashboard` is ARVIO's web app (`web/`) built in hosted mode against GMC Cloud
(`dashboard.Dockerfile`). One domain serves both:

```
gmc.projectneverland.gr {
	encode gzip
	@backend path /.netlify/functions/* /tv /tv/* /assets/* /delete* /account-deletion* /healthz
	handle @backend {
		reverse_proxy gmc-cloud:8790
	}
	handle {
		reverse_proxy dashboard:3000
	}
}
```

Before the dashboard is up, `reverse_proxy gmc-cloud:8790` for the whole site is enough.

# GMC (Galinos Media Center): personal build of ARVIO

This branch (`galinos`) is ARVIO plus personal changes, built as a separate app
(`com.galinos.gmc`, launcher name "GMC") that installs next to the official one.

- `main` mirrors ProdigyV21/ARVIO. Use "Sync fork" on GitHub to update it.
- `galinos` = `main` + personal changes. After syncing `main`, merge `main` into `galinos`.
- `.github/workflows/personal-build.yml` builds a signed APK on every push to `galinos`
  (or by hand from the Actions tab) and publishes it as a GitHub release.
- `personal/personalize.py` adjusts the checkout at build time only (package name, app name,
  version, update source, Firebase off), so upstream files are never edited in Git.

## One-time setup

1. The first run of "Personal build" creates the signing key and uploads it as the artifact
   `personal-keystore`. Download it, add the two secrets it describes, keep `personal.jks` and the
   password as a backup, delete the artifact.
2. Settings → Secrets and variables → Actions: add `TMDB_API_KEY`, `TRAKT_CLIENT_ID`,
   `TRAKT_CLIENT_SECRET`.
3. Settings → General → Default branch: `galinos`, so "Run workflow" appears in the Actions tab.
   Then Actions → "Personal build" → Run workflow.
4. On each Shield: install Obtainium, add `https://github.com/galinosT/ARVIO`.

## Personal changes on this branch

- AI subtitles: re-request lines the model dropped or garbled with another script
  (also proposed upstream as ProdigyV21/ARVIO#897).

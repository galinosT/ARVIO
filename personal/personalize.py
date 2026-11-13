#!/usr/bin/env python3
"""
Turns an ARVIO checkout into a personal build, at build time only.

Nothing here is committed into the upstream source files: the workflow runs this script on a fresh
checkout right before Gradle, so syncing the fork with ProdigyV21/ARVIO never conflicts with it.
Every edit asserts that the text it changes is still there, so if upstream reshapes these lines the
build stops with a clear message instead of silently producing a wrong APK.

What it changes:
  - package name, so the app installs next to the official ARVIO instead of clashing with it
  - app name shown on the launcher
  - version, so each build of this fork is newer than the last (for Obtainium / the in-app updater)
  - in-app updater points at this fork's GitHub releases instead of the official ones
  - Firebase/Crashlytics off: the bundled Firebase config belongs to the official app
  - skips the release check that demands the official Telegram/cloud credentials

Environment:
  PERSONAL_PACKAGE   package name             (default com.galinos.arvio)
  PERSONAL_APP_NAME  launcher name            (default ARVIO G)
  PERSONAL_BUILD     build number, required   (the workflow passes github.run_number)
  GITHUB_REPOSITORY  owner/repo for updates   (set by GitHub Actions)
"""
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle.kts"


def fail(msg):
    sys.exit(f"personalize: {msg}\nUpstream probably changed this part of the build; the script needs updating.")


def replace_once(text, old, new, what):
    n = text.count(old)
    if n != 1:
        fail(f"expected exactly one '{what}' ({old!r}), found {n}")
    return text.replace(old, new)


def main():
    package = os.environ.get("PERSONAL_PACKAGE", "com.galinos.arvio")
    app_name = os.environ.get("PERSONAL_APP_NAME", "ARVIO G")
    build = os.environ.get("PERSONAL_BUILD", "")
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not build.isdigit() or not 0 < int(build) < 1000:
        fail(f"PERSONAL_BUILD must be a number from 1 to 999, got {build!r}")
    if repo.count("/") != 1:
        fail(f"GITHUB_REPOSITORY must be owner/repo, got {repo!r}")
    owner, name = repo.split("/")
    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", package):
        fail(f"invalid package name {package!r}")

    g = GRADLE.read_text(encoding="utf-8")

    g = replace_once(g, 'applicationId = "com.arvio.tv"', f'applicationId = "{package}"', "applicationId")

    m = re.search(r"\n(\s*)versionCode = (\d+)\n\s*versionName = \"([^\"]+)\"\n", g)
    if not m:
        fail("versionCode/versionName block not found")
    up_code, up_name = int(m.group(2)), m.group(3)
    code = up_code * 1000 + int(build)          # stays above every earlier build, also after upstream bumps
    version = f"{up_name}.{build}"
    g = g.replace(m.group(0), f"\n{m.group(1)}versionCode = {code}\n{m.group(1)}versionName = \"{version}\"\n", 1)

    g = replace_once(g, 'buildConfigField("String", "GITHUB_OWNER", "\\"ProdigyV21\\"")',
                     f'buildConfigField("String", "GITHUB_OWNER", "\\"{owner}\\"")', "GITHUB_OWNER")
    g = replace_once(g, 'buildConfigField("String", "GITHUB_REPO", "\\"ARVIO\\"")',
                     f'buildConfigField("String", "GITHUB_REPO", "\\"{name}\\"")', "GITHUB_REPO")

    # Firebase: the bundled google-services.json only lists the official package names.
    g = replace_once(g, '    id("com.google.gms.google-services")\n', "", "google-services plugin")
    g = replace_once(g, '    id("com.google.firebase.crashlytics")\n', "", "crashlytics plugin")
    block = re.search(r"\n\s*configure<CrashlyticsExtension> \{\s*nativeSymbolUploadEnabled = true\s*\}", g)
    if not block:
        fail("CrashlyticsExtension block not found")
    g = g.replace(block.group(0), "", 1)
    n = g.count('buildConfigField("Boolean", "ENABLE_CRASH_REPORTING", "true")')
    if n == 0:
        fail("ENABLE_CRASH_REPORTING not found")
    g = g.replace('buildConfigField("Boolean", "ENABLE_CRASH_REPORTING", "true")',
                  'buildConfigField("Boolean", "ENABLE_CRASH_REPORTING", "false")')

    # The release check demands the official Telegram and cloud credentials; a personal build has none.
    g = replace_once(g, "        dependsOn(validateReleaseCloudSecrets)\n", "", "release credential check")

    GRADLE.write_text(g, encoding="utf-8")

    renamed = 0
    for strings in sorted((ROOT / "app" / "src" / "main" / "res").glob("values*/strings.xml")):
        s = strings.read_text(encoding="utf-8")
        new = re.sub(r'(<string name="app_name"[^>]*>)[^<]*(</string>)', rf"\g<1>{app_name}\g<2>", s)
        if new != s:
            strings.write_text(new, encoding="utf-8")
            renamed += 1
    if renamed == 0:
        fail("no app_name string found")

    print(f"personalize: package {package}, name '{app_name}', version {version} ({code}), "
          f"updates from {owner}/{name}, Firebase off, app_name set in {renamed} file(s)")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"version={version}\nversion_code={code}\n")


if __name__ == "__main__":
    main()

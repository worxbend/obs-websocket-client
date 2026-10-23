# Releases

MIT licensed. Bundled third-party specs keep own attribution and licences.

## Coordinates

Group `com.worxbend` (Scala package `com.worxbend.obs.websocket.client`), Scala 3 (`_3`), early-semver. Seven artifacts:
`obs-websocket-client-protocol`, `-core`, and backend adapters `-sttp`, `-okhttp`, `-zio`,
`-fs2`, `-pekko`. App declares exactly one adapter; `sttp` is default.

Released from `v0.3.0` on. A version exists only after its release run is green, not when its tag
is pushed. Earlier tags (`v0.1.0`, `v0.2.0`) are source tags only, never published.

```text
mvn"com.worxbend::obs-websocket-client-sttp:0.3.0"
```

## Publishing workflows

Pushing tag `vX.Y.Z` starts two independent workflows (same shape as glyphora and scalacv):

| Workflow | Destination | Steps after full Validate on tag commit |
|---|---|---|
| `release.yml` | Maven Central (`central.sonatype.com`) | credential + licence-review check → `validate_release.py` → signed publish, auto-release → hash-check 28 public files → attest jars → isolated consumer from Central → GitHub Release |
| `publish-packages.yml` | `https://maven.pkg.github.com/worxbend/obs-websocket-client` | `validate_release.py` → publish with `GITHUB_TOKEN` → hash-check 28 files → isolated consumer |

Both call `validate.yml` through `workflow_call`, so every gate (exact coverage, formatting,
regeneration, dependency baseline, local consumer, site) runs on the tag's exact commit before
publish job gets credentials. The publish job checks out only that validated SHA.

`tools/validate_release.py` fails unless:

- ref is a tag `vX.Y.Z` (stable only) and checkout is exactly that tag commit, tree clean;
- publishable modules are exactly the seven libraries;
- every `publishVersion` equals `X.Y.Z` (`OBS_RELEASE_VERSION`, set from tag);
- `CHANGELOG.md` has a `## X.Y.Z` heading.

`tools/verify_published.py REPO` downloads POM, jar, sources and javadoc for all seven and compares
SHA-256 with the built files; Central lag retries until one 30-minute deadline.
`tools/consumer_smoke.sh VERSION REPO` builds a separate consumer against REPO with a fresh Coursier
cache — no local repository fallback. Without arguments it publishes to a temp local repo instead
(what Validate runs).

GitHub Release is created only after Central upload, remote verification and consumer pass.
Packages run is independent; check it before claiming availability there.

## One-time setup

**Repository secrets** (Settings → Secrets and variables → Actions), named as Mill reads them:

| Secret | Purpose |
|---|---|
| `MILL_SONATYPE_USERNAME` | Central Portal user-token username |
| `MILL_SONATYPE_PASSWORD` | Central Portal user-token password |
| `MILL_PGP_SECRET_BASE64` | base64 of ASCII-armored signing secret key |
| `MILL_PGP_PASSPHRASE` | key passphrase (empty for unprotected key) |

Token: [Central Portal → Generate User Token](https://central.sonatype.org/publish/generate-portal-token/),
with publish rights on verified namespace `com.worxbend`. Public key must be
on a [Sonatype-supported keyserver](https://central.sonatype.org/publish/requirements/gpg/).

```sh
gh secret set MILL_SONATYPE_USERNAME --repo worxbend/obs-websocket-client
gh secret set MILL_SONATYPE_PASSWORD --repo worxbend/obs-websocket-client
gpg --armor --export-secret-keys FINGERPRINT | base64 -w0 | \
  gh secret set MILL_PGP_SECRET_BASE64 --repo worxbend/obs-websocket-client
gh secret set MILL_PGP_PASSPHRASE --repo worxbend/obs-websocket-client
```

**Repository variable** `SCHEMA_LICENSE_REVIEWED`: generated bindings derive from upstream
obs-websocket `protocol.json` (GPL-2.0, see `protocol-spec/`). Central publish refuses to run until
this variable equals the `revision` in `protocol-spec/provenance.json`. Set it only after licence
review of schema-derived output; re-review when the pinned schema changes.

```sh
gh variable set SCHEMA_LICENSE_REVIEWED --repo worxbend/obs-websocket-client \
  --body "$(python3 -c 'import json; print(json.load(open("protocol-spec/provenance.json"))["revision"])')"
```

GitHub Packages needs nothing extra: job `GITHUB_TOKEN` has `packages: write`. Reading packages
needs auth even when public: classic PAT with `read:packages`, then
`COURSIER_CREDENTIALS="maven.pkg.github.com USER:TOKEN"`.

## Cutting a release

1. `main` green; run local gates if unsure (`tools/coverage.sh`, `tools/check_generation.py`,
   `tools/consumer_smoke.sh`, `./mill --no-server site.build`).
2. Real OBS check on release commit when transport or session code changed
   (`real-obs.yml` / `tools/real_obs_smoke.sh`); record in [compatibility](compatibility.md).
3. Move `## Unreleased` entries under `## X.Y.Z — YYYY-MM-DD`; bump install snippets
   (README, [backends](guides/backends.md), this page). Commit, push, wait for green.
4. `git tag -a vX.Y.Z -m "…" && git push origin vX.Y.Z`.
5. Watch both runs. Version is out only when `release.yml` is green.
6. Bump default snapshot in `build.mill` (`Versions.release`) to next `-SNAPSHOT`.

Local packaging check without upload: `OBS_RELEASE_VERSION=X.Y.Z ./mill --no-server release.packageReport`
writes jar hashes under `out/release/`; `release.dependencyReport` inventories runtime deps and
cached POM licences (not a licence audit). Weekly `vulnerability-scan.yml` scans codegen Python
locks only — JVM advisories need manual review before release.

## Reruns and recovery

Both workflows accept `workflow_dispatch` with a **tag** selected; branches fail first job. Runs
for one tag and destination serialize, never cancel an upload mid-way. Missing secret or licence
variable fails before any upload: set it, then rerun failed jobs.

Maven coordinates are immutable. After partial upload, inspect the Central Portal deployment
before rerunning. If upload succeeded but verification or consumer step failed, rerun only that
check; never re-upload or move a published tag. Fix forward with a new patch version.

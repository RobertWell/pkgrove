# Releasing PkgroveKit

PkgroveKit publishes to three targets, all from the immutable-release version
(never `-SNAPSHOT` — see `CHANGELOG.md` / the publish guard):

| Target | Reach | Auth to consume | Status |
|---|---|---|---|
| **LAN GitLab** Maven registry (`root/pkgrovekit`) | LAN only | none (anonymous) | **live** (`.gitlab-ci.yml`) |
| **GitHub Packages** (`maven.pkg.github.com/RobertWell/pkgrove`) | public repo | token (`read:packages`) even for public | **live** (`publish.yml`) |
| **Maven Central** | fully public | **none** | **LIVE — 0.3.0 published 2026-08-02** |

## Maven Central — the public, tokenless goal

Consumers resolve with a plain `mavenCentral()` and no credentials. Getting
there needs the following. The build is already wired (gated on env, so nothing
below breaks the existing targets); what remains is owner input + secrets.

### Prerequisite 1 — Namespace — DECIDED: **`com.pkgrove`** ✅ (owner-verified 2026-08-02)
The namespace `com.pkgrove` is verified on central.sonatype.com. The build's
`group` is now `com.pkgrove`; **0.3.0 is the first release under the new
coordinates** (`com.pkgrove:pkgrovekit-*`). The already-published
`com.pkgrove.pkgrovekit:*:0.2.0` artifacts remain immutable in the GitLab/GitHub
registries — existing consumers (AuditPatchX) keep building and switch
`groupId` on their next upgrade. Java package names stay `com.pkgrove.pkgrovekit`
(groupId and code packages are independent; a source-wide rename would churn
every consumer for zero functional gain).

### Prerequisite 2 — License — DECIDED: **MIT** ✅
Owner chose **MIT** (2026-08). `LICENSE` now holds the full MIT text
(© 2026 RobertWell), and the POM emits the MIT `<licenses>` block **by default**
on every published artifact (overridable via `-Ppkgrovekit.license.name/.url`).
No further license input needed.

### Prerequisite 3 — Signing key — **IN HAND** ✅ (2026-08-02)
ed25519 key `DAE1EF2D001D6B5FABFF3F1C0E8253CF6B4EBD3B`, uid
`RobertWell <bioresearch567@gmail.com>`; public half on keyserver.ubuntu.com,
private half + passphrase on the LAN box at `~/.config/pkgrovekit/signing.env`
(chmod 600; sets `SIGNING_KEY`/`SIGNING_PASSWORD`; consume by sourcing). The
public key is also archived at `~/.config/pkgrovekit/pkgrovekit-signing-public.asc`.
The Gradle-core `signing` plugin signs when `SIGNING_KEY` is present, inert
otherwise.

### Prerequisite 4 — Central Portal token — **IN HAND** ✅ (2026-08-02)
The Central Portal user token is stored on the LAN box at
`~/.config/pkgrovekit/central.env` (chmod 600; sets `MAVEN_CENTRAL_URL`,
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`). Consume by **sourcing the
file** — never inline the values. Validity + namespace authorization proven:
the Portal `published` API answered `200` with the token and `401` without.
The prod-side drop file it arrived in has been deleted. For CI publishing,
mirror these three values as masked CI variables when the release is cut.

### Release flow (the PROVEN 0.3.0 path — Portal bundle upload)
The Central **Portal** is not a Maven PUT endpoint; a release is a signed bundle
zip POSTed to the Publisher API:
1. Bump to the next immutable version (build.gradle.kts + CHANGELOG); no `-SNAPSHOT`.
2. `source ~/.config/pkgrovekit/central.env ~/.config/pkgrovekit/signing.env` and set
   `CENTRAL_BUNDLE_DIR` to a scratch dir, then
   `./gradlew publishMavenPublicationToCentralBundleRepository` — publishes every
   module (jar + sources + javadoc + pom + module metadata) with `.asc`
   signatures and checksums into a maven-layout tree.
3. Strip Portal-forbidden files (`maven-metadata.xml*`, `*.asc.md5/sha*`), zip
   the `com/` tree, and upload:
   `curl -H "Authorization: Bearer $(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 -w0)" -F bundle=@bundle.zip "https://central.sonatype.com/api/v1/publisher/upload?name=pkgrovekit-X.Y.Z&publishingType=AUTOMATIC"`
   → returns a deploymentId.
4. Poll `POST /api/v1/publisher/status?id=<deploymentId>` until
   `PUBLISHED` (AUTOMATIC publishes on successful validation) or `FAILED`
   (errors list names the exact file/problem).
5. Verify a clean `mavenCentral()` consumer resolves it with no token
   (repo1.maven.org propagation lands within minutes of PUBLISHED).
6. Tag `vX.Y.Z` and push; GitLab/GitHub Packages publishes ride their existing CI.

### Already wired (no owner input needed)
- POM completeness: `name`, `description`, `url`, `developers`, `scm`,
  `issueManagement` (Central-required fields) — verified in the published POM.
- Gated `signing` (core plugin) + gated `MavenCentral` publishing repo.
- Sources jar + Dokka-javadoc jar produced for every module.
- Immutable-version policy + publish guard already enforced.

## Note: the GitHub-Packages read PAT
Separately, a `read:packages` PAT lets an external CI resolve PkgroveKit from
**GitHub Packages** (public repo, but token-gated reads) — this satisfies the
literal cross-repo-registry-read proof and is independent of Maven Central.
Wire it as a consumer-CI secret; the LAN GitLab registry already provides the
tokenless LAN proof.

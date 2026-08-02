# Releasing RowRelay

RowRelay publishes to three targets, all from the immutable-release version
(never `-SNAPSHOT` — see `CHANGELOG.md` / the publish guard):

| Target | Reach | Auth to consume | Status |
|---|---|---|---|
| **LAN GitLab** Maven registry (`root/rowrelay`) | LAN only | none (anonymous) | **live** (`.gitlab-ci.yml`) |
| **GitHub Packages** (`maven.pkg.github.com/RobertWell/rowrelay`) | public repo | token (`read:packages`) even for public | **live** (`publish.yml`) |
| **Maven Central** | fully public | **none** (the goal) | **namespace + token READY; pending PGP signing key only** |

## Maven Central — the public, tokenless goal

Consumers resolve with a plain `mavenCentral()` and no credentials. Getting
there needs the following. The build is already wired (gated on env, so nothing
below breaks the existing targets); what remains is owner input + secrets.

### Prerequisite 1 — Namespace — DECIDED: **`com.pkgrove`** ✅ (owner-verified 2026-08-02)
The namespace `com.pkgrove` is verified on central.sonatype.com. The build's
`group` is now `com.pkgrove`; **0.3.0 is the first release under the new
coordinates** (`com.pkgrove:rowrelay-*`). The already-published
`io.maxxga.rowrelay:*:0.2.0` artifacts remain immutable in the GitLab/GitHub
registries — existing consumers (AuditPatchX) keep building and switch
`groupId` on their next upgrade. Java package names stay `io.maxxga.rowrelay`
(groupId and code packages are independent; a source-wide rename would churn
every consumer for zero functional gain).

### Prerequisite 2 — License — DECIDED: **MIT** ✅
Owner chose **MIT** (2026-08). `LICENSE` now holds the full MIT text
(© 2026 RobertWell), and the POM emits the MIT `<licenses>` block **by default**
on every published artifact (overridable via `-Prowrelay.license.name/.url`).
No further license input needed.

### Prerequisite 3 — Signing key (secret)
Central requires PGP-signed artifacts. Generate a key, publish the public half
to a keyserver, and provide the private half to CI:
- `SIGNING_KEY` — the ASCII-armored private key (`gpg --armor --export-secret-keys`).
- `SIGNING_PASSWORD` — its passphrase.
The Gradle-core `signing` plugin (no new dependency → passes the supply-chain
gate) signs `publishToMavenCentral` when `SIGNING_KEY` is present, and is inert
otherwise.

### Prerequisite 4 — Central Portal token — **IN HAND** ✅ (2026-08-02)
The Central Portal user token is stored on the LAN box at
`~/.config/rowrelay/central.env` (chmod 600; sets `MAVEN_CENTRAL_URL`,
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`). Consume by **sourcing the
file** — never inline the values. Validity + namespace authorization proven:
the Portal `published` API answered `200` with the token and `401` without.
The prod-side drop file it arrived in has been deleted. For CI publishing,
mirror these three values as masked CI variables when the release is cut.

### Release flow (once the above are in place)
1. Bump to the next immutable version (build.gradle.kts + CHANGELOG); no `-SNAPSHOT`.
2. Refresh dependency-verification metadata via the GitLab job (see `docs/SECURITY.md`).
3. Tag `vX.Y.Z`. CI runs the gate (`check` + CVE/SBOM + verification), then:
   `./gradlew publishMavenPublicationToMavenCentralRepository -Prowrelay.license.name=… -Prowrelay.license.url=…`
   with the signing + Central secrets in the env.
4. Validate the staged bundle on the Portal (POM completeness, signatures,
   sources+javadoc jars — all already produced), then **promote/release**.
5. Verify a clean `mavenCentral()` consumer resolves it with no token.

### Already wired (no owner input needed)
- POM completeness: `name`, `description`, `url`, `developers`, `scm`,
  `issueManagement` (Central-required fields) — verified in the published POM.
- Gated `signing` (core plugin) + gated `MavenCentral` publishing repo.
- Sources jar + Dokka-javadoc jar produced for every module.
- Immutable-version policy + publish guard already enforced.

## Note: the GitHub-Packages read PAT
Separately, a `read:packages` PAT lets an external CI resolve RowRelay from
**GitHub Packages** (public repo, but token-gated reads) — this satisfies the
literal cross-repo-registry-read proof and is independent of Maven Central.
Wire it as a consumer-CI secret; the LAN GitLab registry already provides the
tokenless LAN proof.

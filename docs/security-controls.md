# PkgroveKit security controls (HEL-124)

> **Reporting a vulnerability?** This file documents the project's *internal*
> automated controls and gates. To report a vulnerability privately, see the
> researcher-facing [Security Policy](../SECURITY.md) at the repository root.

## Publication gate — what blocks a publish

The GitHub Packages `publish` job depends on **all** of: the full test check,
the CVE scan, and SBOM generation. `workflow_dispatch` (manual publish) runs
the same workflow and therefore the same gates — there is no bypass path.
`./gradlew publishToMavenLocal` stays available for development and
remediation testing; only shared-registry publication is gated.

A publish is impossible when any of these holds:

- an unsuppressed **HIGH or CRITICAL** vulnerability exists in the resolved
  dependency graph (Trivy blocking scan, CVSS ≥ 7 class);
- the scanner fails to execute or update its advisory database (a scan error
  is a failed job, never a silent pass);
- a security exception has **expired** (`expired_at` in `.trivyignore.yaml`
  makes Trivy re-raise the finding);
- required reports were not produced (report upload runs `if: always()`, so
  reports survive failed scans as CI artifacts);
- the full test check fails.

MEDIUM findings are reported and require review; LOW/UNKNOWN are reported.
None of them auto-block, and none of them auto-disappear — the full-severity
JSON/table reports are retained on every run.

## Scanner choice

**Trivy** over the committed Gradle lockfiles (`*/gradle.lockfile`), selected
per the issue's "or an equivalent maintained tool" clause: maintained by Aqua,
needs no advisory-DB API key (OWASP Dependency-Check's NVD feed now requires
one), produces SARIF + JSON + table output, and supports **expiring**
exception entries — which is what makes the exception policy mechanically
enforceable. SARIF is also uploaded to the repository Security tab.

Build *plugins* are not part of the shipped surface and are reported
separately by nature of the lockfile scan (plugin classpaths are locked in
`settings` scope, not in module runtime lockfiles).

## Exception (suppression) policy

Register: `.trivyignore.yaml`. Every entry must carry the advisory id, the
affected artifact + dependency path, the non-exploitability/remediation
rationale, a compensating control, an owner, an approval reference (Linear
issue), the creation date, the awaited fixed version/condition, and a
mandatory `expired_at` review date. **Expired entries fail the gate by
construction** (Trivy resumes raising them). Wildcard suppressions are
prohibited.

## Integrity and reproducibility (distinct from CVE scanning)

Integrity proves the fetched artifact is the expected one — not that it is
vulnerability-free.

- **Dependency locking** is enabled for all configurations in every module;
  lockfiles are committed. Dynamic versions (`1.+`, ranges, changing modules)
  cannot resolve. Refresh deliberately with
  `./gradlew resolveAndLockAll --write-locks` (or per-module `dependencies
  --write-locks`) and review the lockfile diff in the PR.
- Repositories are explicitly `mavenCentral()` only (plus GitHub Packages for
  publishing); no fallback repositories exist in the build.
- **Artifact-integrity verification** is ENABLED: `gradle/verification-metadata.xml`
  pins a SHA-256 for every resolved dependency **and** POM
  (`<verify-metadata>true</verify-metadata>`, checksum-only —
  `verify-signatures=false`, no PGP-keyring dependency). Gradle enforces it on
  **every** build automatically, so a substituted or tampered artifact fails
  the `gate` (publish.yml) and `check` (ci.yml) jobs — integrity ("is this the
  artifact we expect") distinct from the CVE scan ("is it vulnerable").
  - **Generated inside GitLab CI — never locally** (owner directive, HEL-124).
    Workstation generation is prohibited: a local environment resolves a
    different variant set (daemon JVM, plugin markers, dokka variants) and the
    file then rejects the CI build — proven twice. The canonical generator is
    the **`verification-metadata`** job on the LAN GitLab mirror
    (`root/pkgrovekit`, `.gitlab-ci.yml`): it **union-appends** onto the
    committed file (Gradle's write mode adds entries and never removes them,
    so entries resolved by other enforcing environments are preserved), proves
    the enforced build passes on the GitLab runner, and exposes
    `gradle/verification-metadata.xml` as the job artifact.
  - **Refresh flow after a dependency change**: push to the GitLab mirror →
    the job runs → fetch the artifact → commit it **verbatim** to GitHub main
    → GitHub CI enforces it green. Do not regenerate anywhere else; do not
    hand-edit the file.

## SBOM

`./gradlew cyclonedxBom` generates CycloneDX JSON+XML from the **actual
resolved runtime graph** into `build/sbom/`; CI uploads it as an artifact on
every security run and before every publish.

## Source-code analysis

- **CodeQL** (`java-kotlin`) runs on push/PR/weekly; findings land in the
  Security tab. HIGH/CRITICAL source findings block publication via the
  documented exception process (treat like CVE exceptions — register the
  decision in the Linear issue and dismiss/fix in the Security tab).
- Security-focused tests live in the suite already: the no-echo unsafe
  identifier gate, named-parameter non-interpolation, secret-free warnings
  (names only, never values), and redaction behavior — all regression-tested.

## Newly disclosed CVE in a published version

1. The weekly scheduled scan (or Dependabot alert) surfaces it.
2. Open/refresh a remediation issue in Linear; identify affected versions,
   modules, and whether the dependency is consumer-exposed (check the SBOM of
   that release).
3. Publish a **fixed version** — never overwrite an existing release.
4. If no upstream fix exists, document mitigation and (only with approval)
   register a time-boxed exception.

## External verification log (unauthenticated probes — what CAN be proven from outside)

2026-08-22 (HEL-259 review follow-up; anonymous GitHub API against the public repo):

| Control | Probe | Result |
|---|---|---|
| Private vulnerability reporting | `GET /repos/RobertWell/pkgrove/private-vulnerability-reporting` | **`{"enabled": false}` — OFF; owner toggle required** |
| Branch protection on `main` | `GET /repos/RobertWell/pkgrove/branches/main` → `.protected` | **`false` — no required checks; owner toggle required** |
| Repository advisories | `GET /repos/RobertWell/pkgrove/security-advisories` | `[]` (none published; drill covered by `security-response-tabletop.md`) |
| Actions gates at HEAD | `GET /actions/runs` | codeql ✅; **`security` was ❌ (CVE-2025-67030 + 4 Spring CVEs in locked graphs) — fixed in this revision, see below** |

Dependabot alerts / secret scanning / dependency-graph state and alert
recipients are not readable anonymously — those five checkboxes below remain
owner-verified only.

Gate repair shipped with this log: `plexus-utils` forced to 3.6.1 in
`integration-tests-quarkus` (CVE-2025-67030), Spring Boot 3.3.5 → 3.5.14 and
Spring 6.1.14 → 6.2.19 in the catalog (CVE-2025-22235, CVE-2026-40973 — no fix
existed on the 3.3.x line, CVE-2025-41249, CVE-2026-41850), lockfiles +
verification metadata regenerated, blocking scan exit 0 locally.

## Owner checklist — repository settings (cannot be set from the repo)

These are GitHub *settings*, requiring repo admin in the UI/API. They cannot be
committed from the repository; the owner must toggle and verify each one, then
record the evidence (a screenshot or the `gh api` response) against HEL-259.

- [ ] Settings → Code security → **Private vulnerability reporting: Enable**.
      This is the intake route published in [`SECURITY.md`](../SECURITY.md); it
      must be on for the "Report a vulnerability" button to appear on the
      Security tab. Verify with
      `gh api repos/RobertWell/pkgrove --jq '.security_and_analysis'`.
- [ ] Settings → Code security: enable **Dependabot alerts** and
      **Dependabot security updates**.
- [ ] Settings → Code security: enable the **dependency graph** and
      **automatic dependency submission** for Gradle (or accept the dependency
      graph from manifests + the in-repo scanning as coverage).
- [ ] Settings → Code security: verify **secret scanning** and **push
      protection** are active (public repos: on by default — verify, don't
      assume).
- [ ] Settings → Code security → Security alerts: confirm the **notification
      recipients** (repo admins / the maintainer) receive Dependabot and
      advisory alerts.
- [ ] Branch protection on `main`: mark these as **required status checks**
      (job names, `workflow / job`): `ci / check`, `security / cve-scan`,
      `dependency-review / dependency-review`, and `codeql / analyze`.

Any control that cannot be enabled (e.g. an org plan that gates a feature) must
be recorded here with the reason and its compensating control — do not leave a
box silently unchecked.

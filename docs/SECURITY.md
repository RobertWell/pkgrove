# RowRelay security controls (HEL-124)

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
- Full checksum `verification-metadata.xml` is deliberately deferred until
  the dependency set stabilizes post-1.0 (locking already pins exact
  versions; verification metadata adds checksum pinning at meaningful
  maintenance cost while the graph is still moving). Revisit at 1.0.

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

## Owner checklist — repository settings (cannot be set from the repo)

These are GitHub *settings*, requiring repo admin in the UI/API:

- [ ] Settings → Advanced Security: enable **Dependabot alerts** and
      **Dependabot security updates**.
- [ ] Enable **automatic dependency submission** for Gradle (or accept the
      dependency graph from manifests + the in-repo scanning as coverage).
- [ ] Verify **secret scanning + push protection** are active (public repos:
      on by default — verify, don't assume).
- [ ] Branch protection on `main`: mark `dependency-review`, `security /
      cve-scan`, and `ci / check` as **required status checks**.

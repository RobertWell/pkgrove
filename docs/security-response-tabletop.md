# Private-report / maintainer-triage tabletop (HEL-259)

Executed 2026-08-22 as a dry-run of the [SECURITY.md](../SECURITY.md) response
process. **No advisory was created or published** — GitHub shows zero
repository advisories before and after this exercise
(`GET /repos/RobertWell/pkgrove/security-advisories` → `[]`). The goal is to
walk every step a real report would take, name the exact artifacts a real
advisory would name, and record where the process breaks *today*.

## Scenario (fictitious, modeled on a real dependency class)

A researcher privately reports: *"`PgCopyWriter` in `pkgrovekit-postgres`
builds a `COPY ... FROM STDIN` statement from a caller-supplied table name;
a table name containing a quoted identifier escape can smuggle SQL when an
application passes unsanitized user input as the table name."* Claimed
impact: SQL injection from an adopter-controlled string. Reported against
`com.pkgrove:pkgrovekit-postgres:0.6.0`.

## Walkthrough against the published process

| Step (SECURITY.md) | What happened in the drill | Verdict |
|---|---|---|
| 1. Private intake via GitHub PVR | **BROKEN TODAY**: `GET /repos/RobertWell/pkgrove/private-vulnerability-reporting` → `{"enabled": false}` (verified 2026-08-22). The "Report a vulnerability" route SECURITY.md publishes does not exist until the owner enables the toggle. A real researcher would fall back to a public issue — the exact failure the policy exists to prevent. | ❌ owner toggle required |
| 2. Acknowledge within the stated SLA | Maintainer (owner) acknowledges receipt; no fix status promised. Nothing blocks this step once intake works. | ✅ |
| 3. Triage: reproduce + severity | Reproduction harness exists: `pkgrovekit-postgres` ITs run against real Postgres (testcontainers). For the claimed vector, triage checks whether the table name reaches the statement without `quoteIdentifier`-style handling. Severity would be assessed CVSS: injection via adopter-supplied identifier = High (not Critical: requires the adopter to pass untrusted input as a *table name*, an unusual contract). | ✅ |
| 4. Determine affected coordinates + range | An advisory must name Maven coordinates and exact versions, not the product: affected `com.pkgrove:pkgrovekit-postgres` `[0.3.0, 0.6.0]` **and the legacy names** `com.pkgrove:rowrelay-postgres:0.3.0`, `com.pkgrove.pkgrovekit:pkgrovekit-postgres:0.2.0` (unsupported but real users may sit there — the advisory lists them as affected-and-unfixed with the migration path). | ✅ policy covers this |
| 5. Patched-version handling | Releases are immutable, no backports (pre-1.0 policy): fix ships as `0.6.1` for every published module (the release train versions all `pkgrovekit-*` together). The advisory names `0.6.1` as the single patched version; `0.3.x–0.5.x` remain "upgrade to 0.6.1". Release mechanics: version bump → full gate (`ci / check`, `security / cve-scan`, `dependency-review`, `codeql`) → Central publish via the documented manual flow (no CI path to Central exists — HEL-257/258 — so the publish step is the maintainer's laptop with `~/.config/pkgrovekit/` credentials; the tabletop flags this as the slowest step in the timeline). | ✅ with known HEL-257/258 caveat |
| 6. Draft the GHSA privately | Fields rehearsed (not filed): summary, product = Maven `com.pkgrove:pkgrovekit-postgres`, affected `<= 0.6.0`, patched `0.6.1`, severity CVSS vector, credit to reporter, CWE-89. CVE request via GitHub's CNA at publication time. | ✅ (dry-run only) |
| 7. Coordinated disclosure | Publish the GHSA + release simultaneously; SECURITY.md's embargo language covers the interval. | ✅ |
| 8. Post-mortem | Would land in `docs/security-controls.md` evidence log + a Linear issue for the root-cause class. | ✅ |

## What the tabletop surfaced (actions)

1. **PVR is off** — the published intake route is dead. Owner toggle; verified
   externally `enabled:false` on 2026-08-22. Until then the policy's
   "do not open a public issue" instruction has no working alternative.
2. **Branch protection on `main` is off** (`GET /branches/main` →
   `protected:false`, 2026-08-22): a rushed security fix could merge without
   the very gates the advisory process depends on. Owner toggle.
3. **Central publish is manual** (HEL-257/258): the patched-release step is
   the critical-path bottleneck in an embargo; acceptable for now, but the
   advisory timeline must budget for it.
4. The security gate itself was red at HEAD when this drill started
   (CVE-2025-67030 + four Spring findings) — fixed alongside this document;
   a red gate would have blocked the patched release in step 5.

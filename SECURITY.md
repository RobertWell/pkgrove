# Security Policy

This is the researcher-facing security policy for **PkgroveKit**
(`com.pkgrove:pkgrovekit-*`, and the legacy `com.pkgrove:rowrelay-*` coordinates).
It covers how to report a vulnerability privately and what to expect in return.
The project's *internal* automated controls and publication gates are documented
separately in [docs/security-controls.md](docs/security-controls.md).

## Supported versions

PkgroveKit is pre-stable (`0.x`) and every published version is an **immutable
release** — there are no `-SNAPSHOT` builds and a released version is never
overwritten. Fixes ship as a **new** version, not a re-tag.

| Version line | Coordinates | Supported |
|---|---|---|
| `0.6.x` (current) | `com.pkgrove:pkgrovekit-*` | ✅ Security fixes released as a new `0.x` version |
| `0.3.x` – `0.5.x` | `com.pkgrove:pkgrovekit-*` | ⚠️ Please upgrade to the latest `0.6.x`; fixes are not backported |
| `0.2.0` | `com.pkgrove.pkgrovekit:*` (legacy namespace) | ❌ Unsupported — migrate coordinates to `com.pkgrove:*` and upgrade |
| `rowrelay-*` `0.3.0` | `com.pkgrove:rowrelay-*` (former name) | ❌ Unsupported — migrate to `pkgrovekit-*` |

Because the project is pre-`1.0.0`, remediation is normally "upgrade to the
latest release." An advisory always names the **exact affected and patched
versions and Maven coordinates**, not just the product name.

## Reporting a vulnerability

**Do not open a public GitHub issue, pull request, or discussion for an
unpatched vulnerability**, and do not disclose it publicly until a fix is
released and coordinated (see below).

Report privately through **GitHub Private Vulnerability Reporting**:

1. Go to <https://github.com/RobertWell/pkgrove/security/advisories>.
2. Click **Report a vulnerability**.
3. Fill in the private advisory form.

This opens a private channel with the maintainer ([@RobertWell](https://github.com/RobertWell))
where the report can be discussed, triaged, and fixed before any public
disclosure. GitHub notifies the maintainer directly; no public artifact is
created until an advisory is published.

If the "Report a vulnerability" button is not visible, private reporting has not
yet been enabled on the repository — please open a **minimal, non-sensitive**
issue asking the maintainer to enable it (without any vulnerability detail) and
wait for the private channel before sharing specifics.

### What a useful report contains

- affected module(s) and Maven coordinate(s) + version(s) (e.g.
  `com.pkgrove:pkgrovekit-jdbc:0.6.0`);
- the version of PkgroveKit you tested against and the environment (JDK, driver,
  database, framework adapter if any);
- a description of the vulnerability and its impact;
- a minimal reproduction (steps, or a small failing snippet/test);
- any known mitigation or workaround;
- whether the issue is already known publicly or under an embargo elsewhere.

Please **do not** include third-party secrets, customer data, or live
credentials in a report — describe the issue with the smallest reproducer that
demonstrates it.

## What to expect

These are targets, not contractual guarantees; PkgroveKit is maintained by a
single maintainer and timelines depend on severity and complexity.

- **Acknowledgement:** within **3 business days** of the private report.
- **Initial assessment** (validity + preliminary severity, CVSS-class): within
  **7 business days**.
- **Progress updates:** at least every **10 business days** while the report is
  open.
- **Fix / decision:** we aim to release a fix or provide a documented mitigation
  within **90 days** of confirmation. High/critical, actively-exploited issues
  are prioritized ahead of that window.

Severity is assessed with CVSS. If a report is determined not to be a
vulnerability, we will explain why and close the private channel.

## Coordinated disclosure

- We follow **coordinated disclosure**: details stay private until a patched
  version is published and an advisory is ready.
- We will agree a disclosure date with the reporter. The default embargo is
  until a fix is released, up to **90 days**; we will not unreasonably extend it
  and will communicate if more time is genuinely needed.
- Once a fix is published, a **GitHub Security Advisory (GHSA)** is published and
  a **CVE** is requested where appropriate (see the maintainer workflow below).

### Researcher credit

Reporters who follow this policy are credited by name/handle in the published
advisory unless they ask to remain anonymous. GitHub advisories support crediting
reporters directly. We do not run a paid bug-bounty program.

### Emergency mitigation

If a vulnerability is being actively exploited or is severe with no available
fix, the maintainer may publish an interim mitigation (configuration guidance, a
documented workaround, or a yanked-from-recommendation notice) via a GHSA before
the full patched release, and prioritize an out-of-band patch release.

---

## Maintainer workflow — private report to advisory

This is the internal procedure for handling a confirmed private report. It does
**not** duplicate the release mechanics — those live in
[docs/RELEASING.md](docs/RELEASING.md).

1. **Reproduce and assess impact.** Confirm the issue on a supported version;
   assign a CVSS severity; identify whether the affected code is
   consumer-exposed (cross-check the released version's SBOM, see
   [docs/security-controls.md](docs/security-controls.md)).
2. **Map affected Maven artifacts and versions.** Enumerate every affected
   coordinate and version across both the current (`com.pkgrove:pkgrovekit-*`)
   and legacy (`com.pkgrove:rowrelay-*`, `com.pkgrove.pkgrovekit:*`) coordinate
   families. Record exact affected + planned-patched versions.
3. **Prepare the fix privately.** Use a GitHub private temporary fork (GHSA
   private fork) or a local branch — never a public PR that reveals the issue
   pre-disclosure. Add a regression test. `./gradlew publishToMavenLocal` and
   `./gradlew check` remain available for private remediation testing without
   touching any shared registry.
4. **Publish a new immutable patched version.** Bump to the next release version
   (no re-tag of an existing release) and cut it via the standard release flow
   in [docs/RELEASING.md](docs/RELEASING.md) (Maven Central Portal bundle
   upload; GitLab + GitHub Packages ride existing CI). Keep the fix commit
   description free of exploit detail until disclosure.
5. **Publish the GitHub Security Advisory.** Draft it in the repository's
   Security tab during the embargo, listing affected/patched versions per
   coordinate, severity, impact, workaround, and reporter credit; publish it
   when the patched version is live.
6. **Request or attach a CVE when appropriate.** Use GitHub's "Request CVE"
   action on the GHSA (GitHub is a CNA) for issues affecting published
   artifacts. Do not mint a CVE for a non-issue or merely to exercise the
   workflow.
7. **Notify consumers and document mitigations.** Note the fix in
   `CHANGELOG.md`, reference the GHSA/CVE, and inform known downstream consumers
   (e.g. AuditPatchX) of the affected/patched coordinates.

Newly disclosed CVEs in a **dependency** (as opposed to a report against
PkgroveKit's own code) are handled by the automated-detection path documented in
[docs/security-controls.md](docs/security-controls.md) ("Newly disclosed CVE in
a published version").

## Repository security posture

Owner-side GitHub settings that back this policy (private vulnerability
reporting, Dependabot, dependency graph/submission, secret scanning + push
protection, required checks on `main`, and alert notification recipients) are
tracked as a verifiable checklist in
[docs/security-controls.md](docs/security-controls.md#owner-checklist--repository-settings-cannot-be-set-from-the-repo).

# Release evidence & retained CI artifacts (HEL-234)

Owner mandate (2026-08-09): every release-relevant quality signal must be (a)
**enforced** — a threshold that FAILS a pipeline, not a report someone might
read — and (b) **independently verifiable** — retained artifacts tying the
exact commit SHA to the measured numbers.

## The evidence document

`scripts/gen-release-evidence.sh` generates `build/release-evidence.md`: the
SHA, branch, toolchain, summed test counts from every JUnit XML on disk,
repository-wide line/branch coverage from the aggregated JaCoCo XML, PIT
mutation scores (when the scheduled tier ran), the soak trend summary, and the
full table of gates enforced at that SHA. It reads only committed sources +
build outputs, so anyone can reproduce it:

```
git checkout <sha>
./gradlew check jacocoAggregatedReport jacocoAggregatedVerification -x :integration-tests:test
bash scripts/gen-release-evidence.sh build/release-evidence.md
```

Sections whose producer did not run in the current pipeline say so explicitly —
absence of evidence is visible, never silent.

## Where the retained artifacts live

| Evidence | Producer job | Artifact path | Retention |
|---|---|---|---|
| Release evidence (SHA → tests → coverage → gates) | GitHub `check` (`ci.yml`); GitLab `verification-metadata` | `build/release-evidence.md` in `check-reports` / `verification-metadata` | 30 days / 90 days |
| Unit+contract test reports, per-module + aggregated JaCoCo HTML/XML | GitHub `check` | `**/build/reports/tests/**`, `**/build/reports/jacoco/**` in `check-reports` | 30 days |
| Changed-code coverage input (aggregated JaCoCo report) | GitLab `diff-coverage` | `build/reports/jacoco/jacocoAggregatedReport/` | 90 days |
| Live-Postgres integration reports (blocking) | GitHub `integration-postgres` | `integration-tests/build/reports/tests/**` | 7 days |
| Live-Oracle integration reports (REQUIRED gate) | GitLab `integration-oracle` (LAN privileged runner) | `integration-tests/build/{test-results,reports/tests}/test/` + JUnit report | 90 days |
| Mutation reports (PIT XML+HTML, 4 critical modules) | GitLab `mutation` (scheduled / `MUTATION_TIER=1`) | `pkgrovekit-{jdbc,transfer,coordination-api,jta}/build/reports/pitest/` | 365 days |
| Soak trend (`soak-trend.csv`) + stress/soak JUnit results | GitLab `stress-soak` (scheduled / `STRESS_TIER=1`) | `integration-tests/build/soak/soak-trend.csv` + test results | 365 days |
| Dependency verification metadata (sanctioned source) | GitLab `verification-metadata` | `gradle/verification-metadata.xml` | 90 days |
| SBOM (CycloneDX, resolved runtime graph) | part of the enforced build | `build/sbom/` | with producing job |

## Scheduled tiers (owner action required once)

The `mutation` and `stress-soak` jobs run on `$CI_PIPELINE_SOURCE == "schedule"`.
A GitLab **pipeline schedule** (e.g. nightly, on `main`) must exist on the LAN
project `root/pkgrovekit` — creating it needs owner/maintainer access
(CI/CD → Schedules). Until then, both tiers remain manually triggerable via
pipeline variables `MUTATION_TIER=1` / `STRESS_TIER=1`.

## Gate summary at a glance

- **Per-module coverage**: 85/75 (critical: jdbc, transfer, jta,
  coordination-api) and 80/70 (rest) — `jacocoTestCoverageVerification` rides
  every module's `check`.
- **Repo-wide coverage**: 80/70 — `jacocoAggregatedVerification` in both CIs.
- **Changed-code coverage**: ≥ 80% of the coverable lines a change touched —
  `jacocoDiffCoverageCheck` (GitHub `check` step; GitLab `diff-coverage` job),
  merge-blocking. Base ref via `-PdiffCoverageBase`.
- **Mutation score**: jdbc ≥ 60%, transfer ≥ 60%, coordination-api ≥ 70%,
  jta ≥ 70% mutants killed — `./gradlew mutationTest`, threshold FAILS the task
  (ratchet policy: raise with the baseline, never lower without owner approval).
- **Live-Oracle IT**: required per-SHA on the LAN runner (GitLab
  `integration-oracle`); the GitHub hosted-runner job stays informational
  (documented flakiness — the gate must not be flaky, the runner was).
- **Soak**: `TransferSoakIT` hard-fails on connection/session/thread leaks,
  a post-GC heap ceiling, or an upward heap trend; duration bounded via
  `-Ppkgrovekit.soak.minutes` (scheduled tier: 12).

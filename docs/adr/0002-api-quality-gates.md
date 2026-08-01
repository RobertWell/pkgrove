# ADR 0002 — API quality gates

Status: **Accepted** (HEL-125) — explicit evaluation of formatting, static
analysis, explicit-API, and API-compatibility gates, with adopt/defer decisions.

## Context

HEL-125 asks RowRelay to "add or explicitly evaluate formatting, static-analysis,
explicit-API, and API-compatibility gates." RowRelay is **pre-stable
(`0.1.x`)** and its public API is still moving (the workflow algebra, structured
executor, and `Metrics` all changed within this cycle). The gate that's right
for a stabilising 1.0 library is not always right for a 0.1.x one that is still
being shaped — a gate that churns on every intentional API change is negative
value during active design.

Each gate is evaluated below against **value now vs. churn now**, with a
scheduled adoption point.

## Decisions

### Formatting — ktlint · DEFER to 1.0 (config now, enforce later)
ktlint would enforce a consistent style, but adopting it now means a one-shot
reformat of the whole codebase (a large, review-noisy diff) plus a buildscript
plugin + its lockfile. The code already follows a consistent hand-maintained
style (4-space, explicit imports, no wildcards). **Decision:** defer the
*enforcing* gate to the 1.0 hardening pass; until then the style is maintained
by review. Low risk, low current value.

### Static analysis — detekt · DEFER to 1.0
detekt catches complexity/smell issues, but its value is highest once the API
shape is frozen; run mid-design it mostly flags things that are about to change.
The security-relevant checks it would cover (no-echo of secrets, injection-safe
identifiers, redaction) are **already regression-tested** in the suite
(`SqlPolicyParityTest`, dialect quoting tests, redaction tests) — the real risk
is covered without the plugin. **Decision:** adopt at 1.0.

### Explicit API — `explicitApi()` · DEFER to 1.0 (highest eventual value)
`explicitApi = Strict` forces an explicit visibility modifier on every public
declaration — genuinely valuable for making the published surface intentional.
But applying it now is a large mechanical change across a surface that is still
being added to, and it would need re-touching as the API grows. **Decision:**
turn it on during the 1.0 API-freeze, when the surface is settled and the
one-time modifier pass is a stable investment rather than churn.

### API compatibility — binary-compatibility-validator · DEFER to 1.0 (by design)
The validator dumps the public API to `*.api` files and fails `check` when the
surface changes. That is exactly what a **stable** library wants — and exactly
what a **pre-1.0** library does *not*: every intentional API change (this cycle:
`Choice`, `WorkflowOutcome`, `executeStructured`, the `Metrics.maxConcurrentLeases`
field) would fail the gate and force an `apiDump` on every commit. Pre-1.0
semantics explicitly permit breaking changes. **Decision:** adopt at the
`0.x → 1.0` transition — the natural moment to freeze and start guarding the API.
This is the correct home for the "an accidental API break must fail CI" goal.

## Summary

| Gate | Decision | Adopt at |
|---|---|---|
| ktlint (formatting) | defer | 1.0 hardening |
| detekt (static analysis) | defer (security checks already tested) | 1.0 |
| `explicitApi()` | defer (highest eventual value) | 1.0 API-freeze |
| binary-compatibility-validator | defer (pre-1.0 permits breaks) | 0.x → 1.0 |

**What IS enforced now** (the gates that add value during active design, already
in CI): the full `check` (unit/module/dialect + compiled doc examples — examples
can't rot), the HEL-124 CVE/SBOM security gate (blocking, proven), CodeQL,
dependency locking (dynamic versions rejected), and the security-focused tests
(SQL-injection corpus, identifier quoting, secret-free redaction). The four
gates above are scheduled for the 1.0 freeze, tracked here so the decision is a
recorded choice, not an omission.

#!/usr/bin/env bash
# HEL-234 (owner mandate 2026-08-09): independently verifiable release evidence.
# Ties the EXACT commit SHA to the test counts, measured coverage, mutation
# scores, soak trend, and the list of enforced gates — generated from the build
# outputs already on disk, so anyone can reproduce it:
#
#   ./gradlew check jacocoAggregatedReport jacocoAggregatedVerification -x :integration-tests:test
#   bash scripts/gen-release-evidence.sh build/release-evidence.md
#
# CI runs it after the enforced build in BOTH CIs and retains the output as an
# artifact (GitHub `check` job; GitLab `verification-metadata` job). Sections
# whose producer did not run in this pipeline say so explicitly rather than
# silently vanishing — absence of evidence must be visible.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-build/release-evidence.md}"
mkdir -p "$(dirname "$OUT")"

sha="${CI_COMMIT_SHA:-${GITHUB_SHA:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}}"
short_sha="$(echo "$sha" | cut -c1-12)"
branch="${CI_COMMIT_REF_NAME:-${GITHUB_REF_NAME:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}}"
gradle_ver="$(grep -oE 'gradle-[0-9.]+-bin' gradle/wrapper/gradle-wrapper.properties | sed -E 's/gradle-|-bin//g')"
java_ver="$(java -version 2>&1 | head -1)"

# ── test counts, summed from every JUnit XML on disk ─────────────────────────
tests=0; failures=0; errors=0; skipped=0; result_files=0
while IFS= read -r f; do
  result_files=$((result_files + 1))
  # first <testsuite ...> attributes only (JUnit XML has one per file)
  attrs="$(grep -m1 -oE '<testsuite[^>]*>' "$f" || true)"
  for k in tests failures errors skipped; do
    v="$(echo "$attrs" | grep -oE "$k=\"[0-9]+\"" | head -1 | grep -oE '[0-9]+' || echo 0)"
    eval "$k=\$(( $k + ${v:-0} ))"
  done
done < <(find . -path '*/build/test-results/*' -name 'TEST-*.xml' -not -path './.gradle-home/*' 2>/dev/null)
passed=$((tests - failures - errors - skipped))

# ── repository-wide coverage from the aggregated JaCoCo XML ──────────────────
agg_xml="build/reports/jacoco/jacocoAggregatedReport/jacocoAggregatedReport.xml"
line_cov="not generated in this run"; branch_cov="not generated in this run"
if [[ -f "$agg_xml" ]]; then
  # the report-level counters are the LAST LINE/BRANCH counters in the document
  read -r lm lc <<<"$(grep -oE '<counter type="LINE" missed="[0-9]+" covered="[0-9]+"' "$agg_xml" | tail -1 | grep -oE '[0-9]+' | tr '\n' ' ')"
  read -r bm bc <<<"$(grep -oE '<counter type="BRANCH" missed="[0-9]+" covered="[0-9]+"' "$agg_xml" | tail -1 | grep -oE '[0-9]+' | tr '\n' ' ')"
  [[ -n "${lc:-}" ]] && line_cov="$(awk "BEGIN {printf \"%.1f%% (%d/%d lines)\", 100*$lc/($lc+$lm), $lc, $lc+$lm}")"
  [[ -n "${bc:-}" ]] && branch_cov="$(awk "BEGIN {printf \"%.1f%% (%d/%d branches)\", 100*$bc/($bc+$bm), $bc, $bc+$bm}")"
fi

# ── mutation scores from PIT XML (scheduled tier; may be absent here) ────────
mutation_section=""
for m in pkgrovekit-jdbc pkgrovekit-transfer pkgrovekit-coordination-api pkgrovekit-jta; do
  mx="$m/build/reports/pitest/mutations.xml"
  if [[ -f "$mx" ]]; then
    total="$(grep -c '<mutation ' "$mx" || echo 0)"
    killed="$(grep -oE "status='KILLED'|status=\"KILLED\"" "$mx" | wc -l | tr -d ' ')"
    score="$(awk "BEGIN { if ($total>0) printf \"%.1f%%\", 100*$killed/$total; else print \"n/a\" }")"
    mutation_section+="| \`$m\` | $killed / $total killed | $score |"$'\n'
  fi
done
[[ -z "$mutation_section" ]] && mutation_section="_PIT did not run in this pipeline — mutation gates run in the scheduled \`mutation\` job (GitLab); latest scores live in its retained artifacts._"$'\n'

# ── soak trend (scheduled tier; may be absent here) ──────────────────────────
soak="integration-tests/build/soak/soak-trend.csv"
if [[ -f "$soak" ]]; then
  soak_section="$(($(wc -l < "$soak") - 1)) iterations recorded; last sample: \`$(tail -1 "$soak")\` (columns: $(head -1 "$soak"))"
else
  soak_section="_soak did not run in this pipeline — the scheduled \`stress-soak\` job (GitLab) runs TransferSoakIT and retains soak-trend.csv for 365 days._"
fi

cat > "$OUT" <<EOF
# PkgroveKit release evidence — \`$short_sha\`

Generated $(date -u '+%Y-%m-%d %H:%M:%S UTC') by \`scripts/gen-release-evidence.sh\` (HEL-234).
Reproduce: check out \`$sha\`, run the enforced build, then re-run this script — the
numbers below are derived only from committed sources + build outputs.

| | |
|---|---|
| Commit | \`$sha\` |
| Branch/ref | \`$branch\` |
| Gradle | $gradle_ver (wrapper) |
| Launcher JVM | $java_ver |

## Test execution (this pipeline)

| Result files | Tests | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| $result_files | $tests | $passed | $failures | $errors | $skipped |

## Coverage (repository-wide, merged over all production modules)

| Line | Branch |
|---|---|
| $line_cov | $branch_cov |

## Mutation testing (PIT, critical modules)

| Module | Mutants | Score |
|---|---|---|
$mutation_section
## Soak / stress

$soak_section

## Enforced gates at this SHA

| Gate | Threshold | Enforced by |
|---|---|---|
| Per-module coverage (critical: jdbc/transfer/jta/coordination-api) | 85% line / 75% branch | \`jacocoTestCoverageVerification\` on \`check\` |
| Per-module coverage (all other production modules) | 80% line / 70% branch | \`jacocoTestCoverageVerification\` on \`check\` |
| Repository-wide coverage | 80% line / 70% branch | \`jacocoAggregatedVerification\` (both CIs) |
| Changed-code coverage (PR diff) | 80% of changed coverable lines | \`jacocoDiffCoverageCheck\` (GitHub \`check\` on PRs; GitLab \`diff-coverage\`) |
| Mutation score (jdbc 60 / transfer 60 / coordination-api 70 / jta 70) | fails \`pitest\` below threshold | GitLab \`mutation\` (scheduled) |
| Live-Postgres integration suite | required, per-SHA | GitHub \`integration-postgres\` (blocking PR check) |
| Live-Oracle integration suite | required, per-SHA | GitLab \`integration-oracle\` (LAN privileged runner) |
| Soak leak/boundedness (heap ceiling+trend, lease/session/thread leaks) | hard assertions in \`TransferSoakIT\` | GitLab \`stress-soak\` (scheduled) |
| Module hierarchy / coordination isolation | any violation fails | \`assertModuleHierarchy\` + \`assertCoordinationIsolation\` on \`check\` |
| Dependency verification | enforced checksums | \`gradle/verification-metadata.xml\` (all builds) |
EOF

echo "release evidence -> $OUT"

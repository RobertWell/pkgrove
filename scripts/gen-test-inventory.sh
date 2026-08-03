#!/usr/bin/env bash
# HEL-129: generate the machine-readable test inventory from source — the
# "generated" half of the scenario-to-test traceability report. It lists every
# @Test / @ParameterizedTest method with its module, class, Docker requirement,
# and CI tier, and cross-checks that every test named in
# docs/test-traceability.md actually exists (drift guard). Runs in CI (non-Docker).
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-build/test-inventory.csv}"
mkdir -p "$(dirname "$OUT")"
echo "module,class,test_method,requires_docker,ci_tier" > "$OUT"

while IFS= read -r f; do
  module="${f#./}"; module="${module%%/*}"
  class="$(basename "$f" | sed -E 's/\.(kt|java)$//')"
  # Docker requirement: any *IT class annotated @Testcontainers is integration-tier.
  if grep -qE '@Testcontainers' "$f"; then docker="yes"; tier="integration(non-blocking)"; else docker="no"; tier="pr(blocking)"; fi
  # backtick Kotlin names + plain method names carrying @Test/@ParameterizedTest on the preceding line(s)
  awk -v m="$module" -v c="$class" -v d="$docker" -v t="$tier" '
    /@Test|@ParameterizedTest/ { pend=1; next }
    pend==1 {
      name=""
      if (match($0, /`[^`]+`/)) {
        name=substr($0, RSTART+1, RLENGTH-2)               # Kotlin backtick name
      } else if (match($0, /fun[ \t]+[A-Za-z0-9_]+/)) {
        name=substr($0, RSTART, RLENGTH); sub(/fun[ \t]+/, "", name)
      } else if (match($0, /(public|void|[ \t])[A-Za-z0-9_]+\(/)) {
        name=substr($0, RSTART, RLENGTH); gsub(/[^A-Za-z0-9_]/, "", name)  # Java method
      }
      if (name!="") { gsub(/,/, ";", name); print m "," c "," name "," d "," t }
      pend=0
    }
  ' "$f"
done < <(find . -type f \( -name '*Test.kt' -o -name '*IT.kt' -o -name '*Example.java' \) \
              -path '*/src/test/*' -not -path '*/build/*') >> "$OUT"

total=$(($(wc -l < "$OUT") - 1))
echo "generated $OUT: $total test methods"

# Drift guard: every `class Xyz` named in the matrix must appear in the inventory.
MATRIX="docs/test-traceability.md"
if [[ -f "$MATRIX" ]]; then
  missing=0
  # test CLASS tokens referenced in the matrix — only backtick-quoted class refs,
  # so prose words like the @ParameterizedTest/@Test annotations don't count.
  for cls in $(grep -oE '`[A-Z][A-Za-z0-9]*(Test|IT)`' "$MATRIX" | tr -d '`' | sort -u); do
    if ! cut -d, -f2 "$OUT" | grep -qx "$cls"; then
      echo "DRIFT: matrix references $cls but it has no tests in source" >&2
      missing=$((missing+1))
    fi
  done
  [[ $missing -eq 0 ]] && echo "drift guard: OK (every matrix-referenced class exists in source)"
  [[ $missing -gt 0 ]] && { echo "drift guard FAILED: $missing missing" >&2; exit 1; }
fi

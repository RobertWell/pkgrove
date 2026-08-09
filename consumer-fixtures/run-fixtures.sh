#!/usr/bin/env bash
# HEL-235: publish the current PkgroveKit build to mavenLocal, then resolve every
# consumer fixture against it and assert each scenario's module boundary
# (required present / forbidden absent). Fails on the first boundary violation.
#
# Usage: consumer-fixtures/run-fixtures.sh [--offline]
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE_FLAGS="--no-daemon"
if [ "${1:-}" = "--offline" ]; then GRADLE_FLAGS="$GRADLE_FLAGS --offline"; fi

echo "== 1/2 publishing PkgroveKit to mavenLocal =="
./gradlew $GRADLE_FLAGS publishToMavenLocal \
  -x :integration-tests:test -x :integration-tests-quarkus:test

echo "== 2/2 verifying consumer fixtures against mavenLocal =="
./gradlew $GRADLE_FLAGS -p consumer-fixtures verifyAllFixtures

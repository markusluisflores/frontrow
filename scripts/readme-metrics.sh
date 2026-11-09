#!/usr/bin/env bash
# Prints the README's headline metrics (spec §7) from what is on disk, so each number can be re-derived.
# Run `./mvnw verify` first: the test counts and the runtime come from target/surefire-reports, so a stale
# target/ gives stale numbers. Nothing here is estimated.
#
# Layers: a test class is "unit" if its source has no Spring context or Testcontainers marker. A class that has one is
# database-backed, and counts under schema (package schema), REST (package rest), MCP (package mcp) or
# service/integration (every other package: service, persistence, security, evidence).
set -euo pipefail
cd "$(dirname "$0")/.."

reports=target/surefire-reports
if ! ls "$reports"/TEST-*.xml > /dev/null 2>&1; then
  echo "no surefire reports in $reports; run ./mvnw verify first" >&2
  exit 1
fi

root=src/test/java
declare -A tests
declare -A seconds
for layer in unit service schema rest mcp; do tests[$layer]=0; seconds[$layer]=0; done
skipped=0

for xml in "$reports"/TEST-*.xml; do
  header="$(grep -m1 -o '<testsuite [^>]*>' "$xml")"
  name="$(echo "$header" | sed -E 's/.* name="([^"]*)".*/\1/')"
  count="$(echo "$header" | sed -E 's/.* tests="([0-9]+)".*/\1/')"
  skip="$(echo "$header" | sed -E 's/.* skipped="([0-9]+)".*/\1/')"
  time="$(echo "$header" | sed -E 's/.* time="([0-9.]+)".*/\1/')"
  source="$root/$(echo "${name%%\$*}" | tr . /).java"
  package="$(basename "$(dirname "$source")")"
  if grep -qE 'SpringBootTest|Testcontainers|WebMvcTest|JdbcTest|DataJpaTest' "$source"; then
    case "$package" in
      schema | rest | mcp) layer="$package" ;;
      *) layer=service ;;
    esac
  else
    layer=unit
  fi
  tests[$layer]=$((tests[$layer] + count))
  seconds[$layer]="$(awk -v a="${seconds[$layer]}" -v b="$time" 'BEGIN { printf "%.3f", a + b }')"
  skipped=$((skipped + skip))
done

total=$((tests[unit] + tests[service] + tests[schema] + tests[rest] + tests[mcp]))
db_seconds="$(awk -v a="${seconds[service]}" -v b="${seconds[schema]}" -v c="${seconds[rest]}" -v d="${seconds[mcp]}" \
  'BEGIN { printf "%.0f", a + b + c + d }')"

race=src/test/java/io/github/markusluisflores/frontrow/service/HoldConcurrencyTest.java
racers="$(sed -nE 's/.*int RACERS = ([0-9]+);.*/\1/p' "$race")"
repetitions="$(sed -nE 's/.*int REPETITIONS = ([0-9]+);.*/\1/p' "$race")"
schemas="$(ls docs/mcp/tools/*.schema.json | wc -l | tr -d ' ')"

echo "Hold race: $racers racers x $repetitions repetitions (HoldConcurrencyTest constants RACERS, REPETITIONS)"
echo "Tests, unit: ${tests[unit]}"
echo "Tests, service/integration: ${tests[service]}"
echo "Tests, schema: ${tests[schema]}"
echo "Tests, REST: ${tests[rest]}"
echo "Tests, MCP: ${tests[mcp]}"
echo "Tests, total: $total ($skipped skipped)"
echo "Integration suite runtime: ${db_seconds} s (sum of the database-backed classes' surefire times)"
echo "Tools with checked-in schemas: $schemas"

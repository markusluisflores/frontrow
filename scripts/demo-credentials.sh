#!/usr/bin/env bash
# Generates the local demo's credentials (spec §9): bcrypt and SHA-256 hashes go to config/application-demo.yml,
# which is gitignored, and the raw passwords and agent token are printed once. Start the app with the demo profile
# and a Postgres datasource (application.yml sets none; SPRING_DATASOURCE_URL, _USERNAME and _PASSWORD work):
#   ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
set -euo pipefail
cd "$(dirname "$0")/.."

target=config/application-demo.yml
if [ -e "$target" ]; then
  echo "$target already exists; delete it to generate new credentials" >&2
  exit 1
fi

./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=target/demo-classpath.txt -Dmdep.includeScope=runtime
separator=":"
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) separator=";" ;; esac

mkdir -p config
umask 077
# Write to a temporary file and move it into place only on success, so a failed run leaves nothing behind.
partial="$(mktemp config/.application-demo.yml.XXXXXX)"
trap 'rm -f "$partial"' EXIT
java -cp "target/classes${separator}$(cat target/demo-classpath.txt)"   io.github.markusluisflores.frontrow.security.DemoCredentials > "$partial"
mv "$partial" "$target"
trap - EXIT
echo "Wrote $target (hashes only)." >&2

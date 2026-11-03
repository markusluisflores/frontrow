#!/usr/bin/env bash
# Generates the local demo's secrets (spec §9, §12). Each file is written only if it is missing; both are gitignored.
# - config/application-demo.yml: bcrypt and SHA-256 hashes of the demo users' passwords and alice's agent token. The
#   raw values are printed once. Delete the file and re-run for new credentials.
# - .env: docker compose's Postgres password. Postgres keeps the password it first started with in its volume, so a new
#   one also needs `docker compose down -v`, which deletes the demo data.
# Then run the demo, bound to loopback only, with docker compose:
#   docker compose up --build
# or without a container, against your own Postgres (SPRING_DATASOURCE_URL, _USERNAME and _PASSWORD):
#   ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo -Dspring-boot.run.arguments=--server.address=127.0.0.1
set -euo pipefail
cd "$(dirname "$0")/.."

target=config/application-demo.yml
if [ -e "$target" ] && [ -e .env ]; then
  echo "$target and .env already exist; delete $target to generate new credentials" >&2
  exit 1
fi

./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=target/demo-classpath.txt -Dmdep.includeScope=runtime
separator=":"
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) separator=";" ;; esac
classpath="target/classes${separator}$(cat target/demo-classpath.txt)"

# Before the umask, so the container's non-root user can enter the directory on a Linux bind mount.
mkdir -p config
umask 077
# Each file goes to a temporary file first and is moved into place only on success, so a failed run leaves nothing.
if [ ! -e .env ]; then
  partial_env="$(mktemp .env.XXXXXX)"
  trap 'rm -f "$partial_env"' EXIT
  java -cp "$classpath" io.github.markusluisflores.frontrow.security.DemoCredentials env > "$partial_env"
  mv "$partial_env" .env
  trap - EXIT
  echo "Wrote .env (the compose Postgres password)." >&2
fi

if [ ! -e "$target" ]; then
  partial="$(mktemp config/.application-demo.yml.XXXXXX)"
  trap 'rm -f "$partial"' EXIT
  java -cp "$classpath" io.github.markusluisflores.frontrow.security.DemoCredentials > "$partial"
  # Readable by the container's non-root user on a Linux bind mount (plan 4B decision 9). It holds only hashes of
  # 192-bit random values; the raw values exist only in this run's output.
  chmod 644 "$partial"
  mv "$partial" "$target"
  trap - EXIT
  echo "Wrote $target (hashes only)." >&2
fi

#!/usr/bin/env bash
# Runs the scripted MCP agent (src/test/.../evidence/ExampleRun.java) against a running demo app and prints its
# transcript (spec §7). Redirect stdout to save the log.
#   FRONTROW_AGENT_TOKEN=... FRONTROW_BOB_PASSWORD=... scripts/example-run.sh [base-url] [scenario|tour]
# The base URL defaults to http://127.0.0.1:8080. Both secrets come from the environment only: the raw values are the
# ones scripts/demo-credentials.sh printed once (alice's agent token, bob's password). Nothing here prints them.
set -euo pipefail
cd "$(dirname "$0")/.."

base_url="${1:-http://127.0.0.1:8080}"
mode="${2:-scenario}"
: "${FRONTROW_AGENT_TOKEN:?FRONTROW_AGENT_TOKEN is not set}"
: "${FRONTROW_BOB_PASSWORD:?FRONTROW_BOB_PASSWORD is not set}"

./mvnw -q test-compile dependency:build-classpath -Dmdep.outputFile=target/example-run-classpath.txt -Dmdep.includeScope=test >&2
separator=":"
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) separator=";" ;; esac
classpath="target/test-classes${separator}target/classes${separator}$(cat target/example-run-classpath.txt)"

# On Windows a bare "bash" started from Java can be WSL's: hand ExampleRun this shell's own Git Bash.
if command -v cygpath > /dev/null 2>&1; then
  FRONTROW_BASH="$(cygpath -w "$BASH")"
else
  FRONTROW_BASH="$BASH"
fi
export FRONTROW_BASH

exec java -cp "$classpath" io.github.markusluisflores.frontrow.evidence.ExampleRun "$base_url" "$mode"

#!/usr/bin/env bash
# The scripted second buyer of the §7 failure run (plan 5 task 7): takes one seat with a REST hold as bob, a different
# demo user from the agent (alice).
#   FRONTROW_BOB_PASSWORD=... scripts/take-seat.sh <base-url> <event-id> <seat-id>
# The password comes from the environment, never an argument. curl reads its credentials from a config on stdin, so
# they are in no process listing. Prints the HTTP status and the hold group id, nothing else. jq is not assumed: the
# id is read out of the JSON response with sed.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: FRONTROW_BOB_PASSWORD=... $0 <base-url> <event-id> <seat-id>" >&2
  exit 2
fi
base_url="$1"
event_id="$2"
seat_id="$3"
: "${FRONTROW_BOB_PASSWORD:?FRONTROW_BOB_PASSWORD is not set}"
case "$event_id$seat_id" in
  *[!0-9]*) echo "event-id and seat-id must be integers" >&2; exit 2 ;;
esac

# A fresh key per run, so a rerun is a new request and not a replay (spec §5).
key="take-seat-$(date +%s)-$RANDOM$RANDOM"
password="${FRONTROW_BOB_PASSWORD//\\/\\\\}"
password="${password//\"/\\\"}"

# printf is a shell builtin, so the config never appears as a process argument.
response="$(printf 'user = "bob:%s"\n' "$password" | curl -sS --max-time 15 --config - \
  -X POST "$base_url/api/events/$event_id/holds" \
  -H "Idempotency-Key: $key" \
  -H "Content-Type: application/json" \
  -d "{\"event_seat_ids\":[$seat_id]}" \
  -w '\n%{http_code}')"
status="${response##*$'\n'}"
body="${response%$'\n'*}"
group="$(printf '%s' "$body" | sed -n 's/.*"hold_group_id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"

echo "HTTP $status hold_group_id=${group:--}"
case "$status" in
  2??) ;;
  *) exit 1 ;;
esac

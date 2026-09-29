#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
deployment_root=${LORIKEET_DEPLOYMENT_ROOT:-"$repo_root/feedback/feedback_automation/output/deployment"}
jar="$deployment_root/bin/feedback-server.jar"
if [[ ! -f "$jar" ]]; then
  echo "Build the server first: ./feedback/server/build.sh" >&2
  exit 1
fi

smoke_root=$(mktemp -d)
server_pid=
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -r -- "$smoke_root"
}
trap cleanup EXIT
umask 077

mkdir -p "$smoke_root/data/grading_histories_demo" "$smoke_root/deployment/public"
printf '[{"student":"student-0","status":"issues"}]\n' \
  > "$smoke_root/data/grading_results_demo.json"
printf '%s\n' '{"schemaVersion":1,"student":"student-0","files":[{"schemaVersion":1,"file":"src/Sample.scala","limit":10,"truncated":false,"initial":"object Sample { val n = 1 }","steps":[{"rule":"Example","description":"Improve this line","start":24,"end":25,"line":1,"column":25,"before":"1","after":"2","code":"object Sample { val n = 2 }"}],"lints":[]}]}' \
  > "$smoke_root/data/grading_histories_demo/student-0.history.json"

FEEDBACK_PUBLIC_DIR="$smoke_root/deployment/public" \
FEEDBACK_DB="$smoke_root/feedback.sqlite" \
FEEDBACK_HOST=127.0.0.1 FEEDBACK_PORT=0 \
  java -jar "$jar" > "$smoke_root/server.log" 2>&1 &
server_pid=$!
smoke_port=
for attempt in {1..100}; do
  if ! kill -0 "$server_pid" 2>/dev/null; then
    sed -n '1,20p' "$smoke_root/server.log" >&2
    exit 1
  fi
  smoke_port=$(sed -n 's/^Feedback server listening on http:\/\/127\.0\.0\.1:\([0-9][0-9]*\)$/\1/p' "$smoke_root/server.log")
  if [[ -n "$smoke_port" ]]; then break; fi
  sleep 0.1
done
if [[ -z "$smoke_port" ]]; then
  echo 'Server did not start in time' >&2
  exit 1
fi

base_url="http://127.0.0.1:$smoke_port"
[[ $(curl -fsS "$base_url/healthz") == ok ]]
[[ $(curl -sS -o /dev/null -w '%{http_code}' "$base_url/generated/overview.html") == 404 ]]
[[ -z $(find "$smoke_root/deployment/public" -type f -print) ]]
[[ $(sqlite3 "$smoke_root/feedback.sqlite" 'SELECT count(*) FROM events;') == 0 ]]
printf 'Empty local server is ready.\n\nIn a second terminal, from the repository root, run:\n'
printf 'LORIKEET_DEPLOYMENT_ROOT=%q ./feedback/server/publish_lab.sh smoke demo %q\n\n' \
  "$smoke_root/deployment" "$smoke_root/data"

manifest="$smoke_root/deployment/private/links/smoke.csv"
while [[ ! -f "$manifest" ]]; do
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo 'Server stopped before publication' >&2
    exit 1
  fi
  sleep 0.2
done
report_path=$(awk -F, 'NR == 2 {print $2}' "$manifest")
if [[ "$report_path" != /r/smoke/* ]] ||
  [[ $(curl -sS -o /dev/null -w '%{http_code}' "$base_url$report_path") != 200 ]]; then
  echo 'Published report is not available' >&2
  exit 1
fi

printf 'Open %s%s in a browser. Step through the feedback and choose Yes or No.\n' \
  "$base_url" "$report_path"
while true; do
  read -r -p 'Press Enter to inspect saved events (Ctrl-C to stop): ' _
  sqlite3 -header -column "$smoke_root/feedback.sqlite" \
    'SELECT event_type, rule, rating FROM events ORDER BY received_timestamp;'
done

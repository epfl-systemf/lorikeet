#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose_file="$script_dir/compose.yaml"
env_file=${FEEDBACK_ENV_FILE:-$script_dir/.env}

test -f "$env_file"

: "${REPORTS_DIR:?set REPORTS_DIR to the grading reports directory}"
: "${DIFFS_DIR:?set DIFFS_DIR to the grading diffs directory}"
: "${ROSTER_FILE:?set ROSTER_FILE to the grading results CSV}"
: "${COURSE_TITLE:?set COURSE_TITLE}"
: "${ASSIGNMENT_TITLE:?set ASSIGNMENT_TITLE}"
: "${REVIEW_ID:?set REVIEW_ID to a new immutable review identifier}"
: "${FEEDBACK_BASE_URL:?set FEEDBACK_BASE_URL to the student-facing origin}"

reports_dir=$(realpath "$REPORTS_DIR")
diffs_dir=$(realpath "$DIFFS_DIR")
roster_file=$(realpath "$ROSTER_FILE")
telemetry_mode=${TELEMETRY_MODE:-research}

test -d "$reports_dir"
test -d "$diffs_dir"
test -f "$roster_file"

docker compose --env-file "$env_file" -f "$compose_file" build feedback

docker compose --env-file "$env_file" -f "$compose_file" run --rm --no-deps \
  -v "$reports_dir:/imports/reports:ro" \
  -v "$diffs_dir:/imports/diffs:ro" \
  -v "$roster_file:/imports/roster.csv:ro" \
  feedback \
  publish \
  --deployment /data \
  --reports /imports/reports \
  --diffs /imports/diffs \
  --roster /imports/roster.csv \
  --course-title "$COURSE_TITLE" \
  --assignment-title "$ASSIGNMENT_TITLE" \
  --review-id "$REVIEW_ID" \
  --base-url "$FEEDBACK_BASE_URL" \
  --telemetry "$telemetry_mode"

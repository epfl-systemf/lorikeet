#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 LAB RUN [GRADING_OUTPUT]" >&2
  exit 2
fi

repo_root=$(cd "$(dirname "$0")/.." && pwd)
lab=$1
run=$2
data=${3:-"$repo_root/grading/output"}
deployment_root=${LORIKEET_DEPLOYMENT_ROOT:-"$repo_root/grading/output/deployment"}

python3 "$repo_root/feedback_website/generate_feedback.py" \
  --data "$data" \
  --run "$run" \
  --publish-lab "$lab" \
  --deployment-root "$deployment_root" \
  --include-scalafmt

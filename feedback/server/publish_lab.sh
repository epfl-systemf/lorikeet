#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 LAB RUN [AUTOMATION_OUTPUT]" >&2
  exit 2
fi

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
lab=$1
run=$2
data=${3:-"$repo_root/feedback/feedback_automation/output"}
deployment_root=${LORIKEET_DEPLOYMENT_ROOT:-"$repo_root/feedback/feedback_automation/output/deployment"}

scala-cli run "$repo_root/feedback/website/GenerateFeedback.scala" --server=false -- \
  --data "$data" \
  --template "$repo_root/feedback/website/feedback_template.html" \
  --run "$run" \
  --publish-lab "$lab" \
  --deployment-root "$deployment_root"

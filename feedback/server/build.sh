#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
deployment_root=${LORIKEET_DEPLOYMENT_ROOT:-"$repo_root/feedback/feedback_automation/output/deployment"}
mkdir -p "$deployment_root/bin"

scala-cli --power package "$repo_root/feedback/server/FeedbackServer.scala" \
  --assembly --force --server=false --main-class FeedbackServerMain \
  -o "$deployment_root/bin/feedback-server.jar"

echo "Built $deployment_root/bin/feedback-server.jar"

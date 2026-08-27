#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose_file="$script_dir/compose.yaml"
env_file=${FEEDBACK_ENV_FILE:-$script_dir/.env}

test -f "$env_file"

if [ "${ENABLE_TLS:-0}" = "1" ]; then
  docker compose \
    --env-file "$env_file" \
    -f "$compose_file" \
    --profile tls \
    up -d --build --remove-orphans
else
  docker compose \
    --env-file "$env_file" \
    -f "$compose_file" \
    up -d --build --remove-orphans feedback
fi

docker compose \
  --env-file "$env_file" \
  -f "$compose_file" \
  ps

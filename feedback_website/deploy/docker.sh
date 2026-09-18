#!/bin/sh
# Run Compose as the host user so interaction logs remain readable and writable.
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
export LORIKEET_UID="$(id -u)"
export LORIKEET_GID="$(id -g)"
export LORIKEET_LOG_HOST_DIR="${LORIKEET_LOG_HOST_DIR:-$HOME/lorikeet-logs}"
umask 027
mkdir -p "$LORIKEET_LOG_HOST_DIR"
if [ ! -w "$LORIKEET_LOG_HOST_DIR" ] || [ ! -x "$LORIKEET_LOG_HOST_DIR" ]; then
    echo "Log directory is not writable: $LORIKEET_LOG_HOST_DIR" >&2
    exit 1
fi
cd "$repo_dir"
exec docker compose -f compose.feedback.yaml "$@"

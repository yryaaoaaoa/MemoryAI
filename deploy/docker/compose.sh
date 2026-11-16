#!/usr/bin/env bash
set -euo pipefail
directory=$(cd "$(dirname "$0")" && pwd -P)
base=${MEMORYAI_DEPLOY_ROOT:-/opt/memoryai}
exec docker compose --project-directory "$directory" --env-file "$base/.env" \
  --env-file "$directory/.release.env" -f "$directory/compose.yaml" "$@"

#!/usr/bin/env bash
set -euo pipefail
base=${MEMORYAI_DEPLOY_ROOT:-/opt/memoryai}
tag=${1:?Usage: deploy.sh release-tag archive-sha256}
checksum=${2:?Archive SHA256 is required}
[[ "$tag" =~ ^[a-f0-9]{40}-[0-9]+-[0-9]+$ ]] || { echo 'Invalid release tag' >&2; exit 1; }
[[ "$checksum" =~ ^[a-f0-9]{64}$ ]] || { echo 'Invalid archive checksum' >&2; exit 1; }
archive="$base/incoming/release.tar"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check --status
previous=$(readlink -f "$base/current")
test -f "$previous/compose.sh"
test -f "$base/.env"

release="$base/releases/$tag"
mkdir "$release"
tar --no-same-owner -xf "$archive" -C "$release"
printf 'RELEASE_TAG=%s\n' "$tag" > "$release/.release.env"
bash "$release/compose.sh" config --quiet
# Finish building before replacing the running application.
bash "$release/compose.sh" --progress plain build backend web

# Additive schema upgrade runs before replacing the application. The previous
# version ignores this nullable column, so application rollback remains valid.
bash "$release/compose.sh" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
  < "$release/migrations/20261116_learning_note_tree.sql"

if bash "$release/compose.sh" up -d --no-deps --force-recreate --wait --wait-timeout 180 backend web &&
   bash "$release/compose.sh" exec -T --interactive=false web nginx -t &&
   bash "$release/compose.sh" exec -T --interactive=false web wget -q -O - http://127.0.0.1/api/knowledge-bases/public | grep -q '"code":200'; then
  ln -sfn "$previous" "$base/previous"
  ln -sfn "$release" "$base/current"
  bash "$release/compose.sh" ps
  echo "Published $tag"
else
  echo "Deployment failed; restoring $(basename "$previous")" >&2
  if bash "$previous/compose.sh" up -d --no-deps --force-recreate --wait --wait-timeout 180 backend web; then
    echo 'Previous application version restored; this workflow still fails.' >&2
  else
    echo 'Rollback failed; inspect application containers on the server.' >&2
  fi
  exit 1
fi

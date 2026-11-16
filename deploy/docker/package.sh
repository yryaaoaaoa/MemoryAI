#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
archive=${1:?Usage: package.sh /absolute/path/to/release.tar}
bundle=$(mktemp -d)
trap 'rm -rf "$bundle"' EXIT
files=(backend.Dockerfile frontend.Dockerfile .dockerignore nginx.conf application.yml compose.yaml es-init.sh es-index.json .env.example README.md compose.sh)
for file in "${files[@]}"; do
  cp "$repo/deploy/docker/$file" "$bundle/$file"
done
cp "$repo/ai-rag-agent/bootstrap/target/bootstrap-1.0.0.jar" "$bundle/app.jar"
cp -R "$repo/ai-job-prep-frontend/dist" "$bundle/dist"
cp "$repo/ai-rag-agent/knowledge/src/main/resources/schema.sql" "$bundle/schema.sql"
mkdir "$bundle/migrations"
cp "$repo/ai-rag-agent/knowledge/src/main/resources/migrations/20261116_learning_note_tree.sql" "$bundle/migrations/"
tar -cf "$archive" -C "$bundle" .

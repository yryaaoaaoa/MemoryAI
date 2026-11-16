#!/bin/sh
set -eu
status=$(curl -sS -o /dev/null -w '%{http_code}' http://elasticsearch:9200/knowledge_chunk)
case "$status" in
  200) echo 'knowledge_chunk already exists; preserving it.' ;;
  404) curl -fsS -X PUT http://elasticsearch:9200/knowledge_chunk -H 'Content-Type: application/json' --data-binary @/init/es-index.json ;;
  *) echo "Elasticsearch index check failed: HTTP $status" >&2; exit 1 ;;
esac

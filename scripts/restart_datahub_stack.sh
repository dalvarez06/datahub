#!/usr/bin/env bash
set -euo pipefail

: "${DATAHUB_VERSION:=debug}"
: "${UI_INGESTION_DEFAULT_CLI_VERSION:=1.3.1.4}"
: "${COMPOSE_PROFILES:=quickstart,quickstart-backend,quickstart-frontend,quickstart-consumers,quickstart-storage}"
export DATAHUB_VERSION
export UI_INGESTION_DEFAULT_CLI_VERSION
export COMPOSE_PROFILES

compose_files=(
  -f docker/quickstart/docker-compose.quickstart-profile.yml
  -f docker/quickstart/docker-compose.override.local.yml
)

docker compose "${compose_files[@]}" down -v
docker compose "${compose_files[@]}" up -d

echo "Waiting for DataHub GMS to be ready..."
for _ in {1..120}; do
  if curl -sSf http://localhost:8080/health >/dev/null; then
    echo "DataHub GMS is up."
    break
  fi
  sleep 2
done

if ! curl -sSf http://localhost:8080/health >/dev/null; then
  echo "DataHub GMS did not become ready in time." >&2
  exit 1
fi

rm -f /Users/davidalvarez/2026-de/money/.openlineage_relay_state_datahub
uv run --with requests /Users/davidalvarez/2026-de/money/scripts/create_structured_property.py

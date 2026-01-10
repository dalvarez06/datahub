#!/usr/bin/env bash
set -euo pipefail

compose_files=(
  -f docker/quickstart/docker-compose.quickstart.yml
  -f docker/quickstart/docker-compose.override.local.yml
)

docker compose "${compose_files[@]}" down -v
docker compose "${compose_files[@]}" up -d
rm /Users/david/pro/money/.openlineage_relay_state_datahub
uv run /Users/david/pro/money/scripts/create_structured_property.py

#!/usr/bin/env bash
set -euo pipefail

: "${DATAHUB_VERSION:=head}"
: "${UI_INGESTION_DEFAULT_CLI_VERSION:=1.3.1.4}"
: "${COMPOSE_PROFILES:=quickstart,quickstart-backend,quickstart-frontend,quickstart-consumers,quickstart-storage}"
: "${DATAHUB_FRONTEND_IMAGE:=acryldata/datahub-frontend-react:debug}"
: "${DATAHUB_GMS_IMAGE:=acryldata/datahub-gms:debug}"
: "${DATAHUB_BUILD_LOCAL_IMAGES:=1}"
export DATAHUB_VERSION
export UI_INGESTION_DEFAULT_CLI_VERSION
export COMPOSE_PROFILES
export DATAHUB_FRONTEND_IMAGE
export DATAHUB_GMS_IMAGE

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

compose_files=(
  -f "${REPO_ROOT}/docker/quickstart/docker-compose.quickstart-profile.yml"
  -f "${REPO_ROOT}/docker/quickstart/docker-compose.override.local.yml"
)

if [[ "${DATAHUB_BUILD_LOCAL_IMAGES}" == "1" ]]; then
  "${REPO_ROOT}/gradlew" :datahub-frontend:docker :metadata-service:war:docker
fi

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

if [[ "${USER:-}" == "davidalvarez" ]]; then
  : "${MONEY_REPO:=/Users/davidalvarez/2026-de/money}"
else
  : "${MONEY_REPO:=${HOME}/pro/money}"
fi

rm -f "${MONEY_REPO}/.openlineage_relay_state_datahub"

if [[ ! -f "${MONEY_REPO}/scripts/create_structured_property.py" ]]; then
  echo "Missing script: ${MONEY_REPO}/scripts/create_structured_property.py" >&2
  exit 1
fi

uv run --with requests "${MONEY_REPO}/scripts/create_structured_property.py"

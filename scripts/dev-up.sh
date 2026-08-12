#!/usr/bin/env bash
set -euo pipefail
docker compose up -d
echo "Waiting for postgres + redis to become healthy..."
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q postgres)")" = "healthy" ]; do sleep 1; done
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q redis)")" = "healthy" ]; do sleep 1; done
echo "postgres + redis are up."

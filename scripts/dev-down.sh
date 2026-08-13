#!/usr/bin/env bash
set -euo pipefail

docker compose down --remove-orphans
echo "postgres and redis are stopped."

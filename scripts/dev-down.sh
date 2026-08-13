#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
RUNTIME_DIR="$REPO_ROOT/.run"
PID_FILE="$RUNTIME_DIR/payout-service.pid"
JAR_FILE="$RUNTIME_DIR/payout-service.jar"

cd "$REPO_ROOT"

if [ -f "$PID_FILE" ]; then
	pid=$(cat "$PID_FILE")
	app_jar=$(cat "$JAR_FILE" 2>/dev/null || true)
	process_command=$(ps -p "$pid" -o command= 2>/dev/null || true)

	if [ -n "$process_command" ] && [ -n "$app_jar" ] && printf '%s' "$process_command" | grep -F -- "$app_jar" >/dev/null 2>&1; then
		echo "Stopping Spring Boot gracefully (PID $pid)..."
		kill -TERM "$pid"
		elapsed=0
		while kill -0 "$pid" 2>/dev/null && [ "$elapsed" -lt 20 ]; do
			sleep 1
			elapsed=$((elapsed + 1))
		done
		if kill -0 "$pid" 2>/dev/null; then
			echo "WARN - Spring Boot did not stop within 20s; forcing shutdown" >&2
			kill -KILL "$pid"
		else
			echo "OK   - Spring Boot stopped"
		fi
	elif [ -n "$process_command" ]; then
		echo "WARN - PID $pid is not the managed Spring Boot process; leaving it running" >&2
	else
		echo "Removing stale Spring Boot runtime state."
	fi

	rm -f "$PID_FILE" "$JAR_FILE"
else
	echo "Spring Boot is not managed by dev-up.sh."
fi

echo "Stopping Postgres and Redis gracefully..."
docker compose down --remove-orphans --timeout 20
echo "All managed development services are stopped."

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
RUNTIME_DIR="$REPO_ROOT/.run"
PID_FILE="$RUNTIME_DIR/payout-service.pid"
JAR_FILE="$RUNTIME_DIR/payout-service.jar"
LOG_FILE="$RUNTIME_DIR/payout-service.log"
HEALTH_URL="${HEALTH_URL:-http://localhost:8080/actuator/health}"
APP_PORT="${APP_PORT:-8080}"
DEPENDENCY_TIMEOUT="${DEPENDENCY_TIMEOUT:-60}"
APP_STARTUP_TIMEOUT="${APP_STARTUP_TIMEOUT:-90}"

fail() {
	echo "FAIL - $1" >&2
	exit 1
}

app_is_healthy() {
	curl -fsS --max-time 2 "$HEALTH_URL" >/dev/null 2>&1
}

fail_if_app_port_is_occupied() {
	if lsof -nP -iTCP:"$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
		lsof -nP -iTCP:"$APP_PORT" -sTCP:LISTEN >&2
		fail "port $APP_PORT is occupied by an unhealthy, unmanaged process"
	fi
}

wait_for_service() {
	local service=$1
	local elapsed=0
	local container_id
	local health

	while [ "$elapsed" -lt "$DEPENDENCY_TIMEOUT" ]; do
		container_id=$(docker compose ps -q "$service")
		if [ -n "$container_id" ]; then
			health=$(docker inspect -f '{{.State.Health.Status}}' "$container_id" 2>/dev/null || true)
			if [ "$health" = "healthy" ]; then
				echo "OK   - $service is healthy"
				return 0
			fi
		fi
		sleep 1
		elapsed=$((elapsed + 1))
	done

	docker compose ps "$service" >&2
	fail "$service did not become healthy within ${DEPENDENCY_TIMEOUT}s"
}

managed_process_is_running() {
	[ -f "$PID_FILE" ] || return 1

	local pid
	pid=$(cat "$PID_FILE")
	case "$pid" in
		''|*[!0-9]*) return 1 ;;
	esac

	kill -0 "$pid" 2>/dev/null
}

managed_process_matches() {
	managed_process_is_running || return 1
	[ -f "$JAR_FILE" ] || return 1

	local pid
	local app_jar
	pid=$(cat "$PID_FILE")
	app_jar=$(cat "$JAR_FILE")
	ps -p "$pid" -o command= 2>/dev/null | grep -F -- "$app_jar" >/dev/null 2>&1
}

stop_managed_process() {
	local pid
	local elapsed=0

	managed_process_matches || return 0
	pid=$(cat "$PID_FILE")
	echo "Stopping unhealthy Spring Boot process (PID $pid)..."
	kill -TERM "$pid"
	while kill -0 "$pid" 2>/dev/null && [ "$elapsed" -lt 20 ]; do
		sleep 1
		elapsed=$((elapsed + 1))
	done
	if kill -0 "$pid" 2>/dev/null; then
		echo "WARN - Spring Boot did not stop gracefully; forcing shutdown" >&2
		kill -KILL "$pid"
	fi
	rm -f "$PID_FILE" "$JAR_FILE"
}

wait_for_app() {
	local timeout=$1
	local elapsed=0

	while [ "$elapsed" -lt "$timeout" ]; do
		if app_is_healthy; then
			return 0
		fi
		if [ -f "$PID_FILE" ] && ! managed_process_matches; then
			return 1
		fi
		sleep 1
		elapsed=$((elapsed + 1))
	done
	return 1
}

cd "$REPO_ROOT"
mkdir -p "$RUNTIME_DIR"

command -v curl >/dev/null 2>&1 || fail "curl is required for application health checks"
command -v lsof >/dev/null 2>&1 || fail "lsof is required for application port checks"

echo "Starting Postgres and Redis..."
docker compose up -d
wait_for_service postgres
wait_for_service redis

if [ -f "$PID_FILE" ] && ! managed_process_is_running; then
	echo "Removing stale Spring Boot runtime state."
	rm -f "$PID_FILE" "$JAR_FILE"
fi

if app_is_healthy; then
	echo "OK   - Spring Boot is already healthy"
	echo "All development services are online."
	exit 0
fi

if managed_process_is_running; then
	if ! managed_process_matches; then
		fail "PID file points to a different process; remove $PID_FILE after checking it"
	fi
	echo "Waiting for the existing Spring Boot process..."
	if wait_for_app 15; then
		echo "OK   - Spring Boot is healthy"
		echo "All development services are online."
		exit 0
	fi
	stop_managed_process
fi

fail_if_app_port_is_occupied

echo "Building Spring Boot application..."
./mvnw -q -DskipTests package
fail_if_app_port_is_occupied

app_jar=""
for candidate in "$REPO_ROOT"/target/payout-webhook-service-*.jar; do
	if [ -f "$candidate" ] && [ "${candidate##*.}" = "jar" ] && [[ "$candidate" != *.original ]]; then
		app_jar=$candidate
		break
	fi
done
[ -n "$app_jar" ] || fail "built application JAR was not found under target/"

echo "Starting Spring Boot application..."
nohup java -jar "$app_jar" >"$LOG_FILE" 2>&1 &
app_pid=$!
printf '%s\n' "$app_pid" >"$PID_FILE"
printf '%s\n' "$app_jar" >"$JAR_FILE"

if ! wait_for_app "$APP_STARTUP_TIMEOUT"; then
	echo "Spring Boot startup log:" >&2
	tail -n 60 "$LOG_FILE" >&2 || true
	stop_managed_process
	fail "Spring Boot did not become healthy within ${APP_STARTUP_TIMEOUT}s"
fi

echo "OK   - Spring Boot is healthy (PID $app_pid)"
echo "Health: $HEALTH_URL"
echo "Log:    $LOG_FILE"
echo "All development services are online."

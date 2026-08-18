#!/usr/bin/env bash
# Verifies the local environment before Workshop 1 starts.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

pass() { echo "OK   - $1"; }
fail() { echo "FAIL - $1"; exit 1; }

command -v java >/dev/null 2>&1 || fail "java not found on PATH"
JAVA_VERSION_RAW=$(java -version 2>&1 | head -1)
JAVA_VERSION=$(echo "$JAVA_VERSION_RAW" | grep -oE '"[0-9]+' | tr -d '"')
[[ "$JAVA_VERSION" -ge 17 ]] && pass "Java $JAVA_VERSION found" || fail "Java 17+ required, found: $JAVA_VERSION_RAW"

[[ -x "$REPO_ROOT/mvnw" ]] || fail "Maven wrapper is missing or not executable: $REPO_ROOT/mvnw"
[[ -f "$REPO_ROOT/.mvn/wrapper/maven-wrapper.properties" ]] || fail "Maven wrapper properties are missing; update or re-clone the repository"
[[ -f "$REPO_ROOT/.mvn/wrapper/maven-wrapper.jar" ]] || fail "Maven wrapper JAR is missing; update or re-clone the repository"
(cd "$REPO_ROOT" && ./mvnw --version >/dev/null 2>&1) || fail "Maven wrapper could not start"
pass "Maven wrapper available"

command -v docker >/dev/null 2>&1 || fail "docker CLI not found (expected via Rancher Desktop)"
docker version >/dev/null 2>&1 || fail "docker daemon not reachable - is Rancher Desktop running with dockerd engine?"
pass "docker daemon reachable"

docker compose version >/dev/null 2>&1 || fail "docker compose plugin not found"
docker compose -f "$REPO_ROOT/docker-compose.yml" config --quiet >/dev/null 2>&1 || fail "docker-compose.yml is invalid"
pass "docker compose available"

command -v curl >/dev/null 2>&1 || fail "curl not found (required for application health checks)"
pass "curl found"

command -v lsof >/dev/null 2>&1 || fail "lsof not found (required for application port checks)"
pass "lsof found"

port_is_listening() {
	local port=$1
	lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
}

compose_service_is_healthy() {
	local service=$1
	local container_id
	local health

	container_id=$(docker compose -f "$REPO_ROOT/docker-compose.yml" ps -q "$service" 2>/dev/null || true)
	[ -n "$container_id" ] || return 1
	health=$(docker inspect -f '{{.State.Health.Status}}' "$container_id" 2>/dev/null || true)
	[ "$health" = "healthy" ]
}

app_is_healthy() {
	curl -fsS --max-time 2 http://localhost:8080/actuator/health >/dev/null 2>&1
}

verify_port() {
	local port=$1
	local service=$2

	if ! port_is_listening "$port"; then
		pass "port $port is available for $service"
		return
	fi

	if [ "$service" = "Spring Boot" ]; then
		if app_is_healthy; then
			pass "port $port is used by a healthy workshop Spring Boot service"
			return
		fi
	elif compose_service_is_healthy "$service"; then
		pass "port $port is used by a healthy workshop $service service"
		return
	fi

	echo "Port $port listener:" >&2
	lsof -nP -iTCP:"$port" -sTCP:LISTEN >&2
	fail "port $port is occupied by a conflicting process. Stop or reconfigure it, then rerun this check; the workshop $service service requires port $port."
}

verify_port 5432 postgres
verify_port 6379 redis
verify_port 8080 "Spring Boot"

command -v gh >/dev/null 2>&1 && pass "gh CLI found" || echo "WARN - gh CLI not found (needed for Copilot CLI/coding agent demos)"
command -v copilot >/dev/null 2>&1 && pass "copilot CLI found" || echo "WARN - copilot CLI not found, install before Workshop 1"

echo "All required checks passed."

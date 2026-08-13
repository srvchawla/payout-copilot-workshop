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

command -v gh >/dev/null 2>&1 && pass "gh CLI found" || echo "WARN - gh CLI not found (needed for Copilot CLI/coding agent demos)"
command -v copilot >/dev/null 2>&1 && pass "copilot CLI found" || echo "WARN - copilot CLI not found, install before Workshop 1"

echo "All required checks passed."

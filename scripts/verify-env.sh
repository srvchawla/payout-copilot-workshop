#!/usr/bin/env bash
# Verifies the local environment before Workshop 1 starts.
set -euo pipefail

pass() { echo "OK   - $1"; }
fail() { echo "FAIL - $1"; exit 1; }

command -v java >/dev/null 2>&1 || fail "java not found on PATH"
JAVA_VERSION=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')
[[ "$JAVA_VERSION" -ge 17 ]] && pass "Java $JAVA_VERSION found" || fail "Java 17+ required, found $JAVA_VERSION"

command -v docker >/dev/null 2>&1 || fail "docker CLI not found (expected via Rancher Desktop)"
docker version >/dev/null 2>&1 || fail "docker daemon not reachable - is Rancher Desktop running with dockerd engine?"
pass "docker daemon reachable"

docker compose version >/dev/null 2>&1 || fail "docker compose plugin not found"
pass "docker compose available"

command -v gh >/dev/null 2>&1 && pass "gh CLI found" || echo "WARN - gh CLI not found (needed for Copilot CLI/coding agent demos)"
command -v copilot >/dev/null 2>&1 && pass "copilot CLI found" || echo "WARN - copilot CLI not found, install before Workshop 1"

echo "All required checks passed."

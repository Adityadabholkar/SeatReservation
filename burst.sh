#!/usr/bin/env bash
# One-command on-sale stampede.   Usage: ./burst.sh <BASE_URL> [ADMIN_TOKEN]
# Needs JDK 21+ locally, or Docker (falls back to a temurin container automatically).
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
ADMIN="${2:-${ADMIN_TOKEN:-admin-secret}}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

java_major() { java -version 2>&1 | awk -F'[".]' '/version/ {print $2; exit}'; }

if command -v java >/dev/null 2>&1 && [ "$(java_major)" -ge 21 ] 2>/dev/null; then
  exec java "$DIR/tools/Burst.java" "$BASE_URL" "$ADMIN"
fi

echo "JDK 21 not found locally - running the burst inside Docker (eclipse-temurin:21)..."
DOCKER_URL="${BASE_URL/localhost/host.docker.internal}"
DOCKER_URL="${DOCKER_URL/127.0.0.1/host.docker.internal}"
exec docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  -e BURST_REQUESTS -e BURST_SEATS -e BURST_CONCURRENCY -e HOT_SEATS -e HOT_CONTENDERS \
  -v "$DIR/tools:/w" -w /w eclipse-temurin:21-jdk \
  java Burst.java "$DOCKER_URL" "$ADMIN"

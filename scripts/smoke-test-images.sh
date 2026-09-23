#!/usr/bin/env bash
# End-to-end smoke check for docker-compose.images.yml: proves the gateway
# can reach the RAG service by its internal Compose service name when both
# are started from published GHCR images, with no source checkout of
# ai-research-assistant and no paid LLM call.
#
# Usage: ./scripts/smoke-test-images.sh
# Optional: GATEWAY_IMAGE / RAG_IMAGE to pin specific tags (see
# docker-compose.images.yml for defaults).
set -euo pipefail
cd "$(dirname "$0")/.."

# Own project name and non-default host ports, so the check neither collides
# with a stack already running on 8000/8080 nor removes its volumes.
export GATEWAY_HOST_PORT="${GATEWAY_HOST_PORT:-18080}" RAG_HOST_PORT="${RAG_HOST_PORT:-18000}"
compose=(docker compose -p gateway-images-smoke -f docker-compose.images.yml)

cleanup() {
  "${compose[@]}" logs --no-color gateway research-assistant 2>&1 | tail -n 80
  "${compose[@]}" down --volumes >/dev/null 2>&1 || true
}
trap cleanup EXIT

# A placeholder key is enough: readiness and this check never trigger a real
# LLM call (see ai-research-assistant's ADR-003 on /health scope).
export ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-smoke-test-placeholder-key}"

echo "==> starting research-assistant and gateway from published images"
"${compose[@]}" up -d research-assistant gateway

echo "==> waiting for research-assistant to become healthy"
for _ in $(seq 1 30); do
  status=$("${compose[@]}" ps --format '{{.Health}}' research-assistant 2>/dev/null || true)
  if [ "$status" = "healthy" ]; then
    break
  fi
  sleep 3
done
if [ "$status" != "healthy" ]; then
  echo "::error::research-assistant never became healthy"
  exit 1
fi

echo "==> waiting for gateway to report UP"
for _ in $(seq 1 30); do
  code=$("${compose[@]}" exec -T gateway curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health 2>/dev/null || true)
  if [ "$code" = "200" ]; then
    break
  fi
  sleep 3
done
if [ "$code" != "200" ]; then
  echo "::error::gateway never reached /actuator/health=200"
  exit 1
fi

# The actual reachability proof: the gateway container resolving and
# reaching the RAG service by its internal Compose service name, the same
# path RAG_BASE_URL uses for real queries.
echo "==> checking gateway can reach research-assistant over the Compose network"
"${compose[@]}" exec -T gateway curl --fail -s -o /dev/null http://research-assistant:8000/health

echo "==> OK: gateway reached research-assistant via the internal Compose network"

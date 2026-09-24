#!/usr/bin/env bash
# End-to-end smoke check for docker-compose.images.yml: proves the gateway's
# own RAG client configuration (RAG_BASE_URL) reaches the RAG service by its
# internal Compose service name when both are started from GHCR images, with
# no source checkout of ai-research-assistant and no paid LLM call.
#
# How: RAG_HEALTH_ENABLED=true makes the gateway's /actuator/health include a
# probe of RAG's /health (no retrieval, no LLM). Gateway UP therefore means
# the gateway resolved and reached a ready RAG. The script then stops RAG and
# requires the gateway to turn DOWN, which shows the probe is really active
# (a gateway image predating the probe would stay UP and fail this step).
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

# Opt-in gateway health probe of RAG; off in the compose file by default.
export RAG_HEALTH_ENABLED=true

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

# Gateway UP above already required a successful gateway -> RAG /health call.
# Prove the probe is live: with RAG stopped the gateway must report DOWN.
echo "==> stopping research-assistant; gateway health must turn DOWN"
"${compose[@]}" stop research-assistant >/dev/null
for _ in $(seq 1 15); do
  code=$("${compose[@]}" exec -T gateway curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health 2>/dev/null || true)
  if [ "$code" = "503" ]; then
    break
  fi
  sleep 2
done
if [ "$code" != "503" ]; then
  echo "::error::gateway health stayed ${code:-unreachable} with RAG stopped; the RAG health probe is not active (gateway image predates it, or RAG_BASE_URL is not used)"
  exit 1
fi

echo "==> OK: gateway health tracks the RAG service reached via RAG_BASE_URL over the Compose network"

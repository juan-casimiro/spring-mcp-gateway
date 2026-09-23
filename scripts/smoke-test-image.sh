#!/usr/bin/env bash
# Smoke-test a built gateway image: start it and require /actuator/health to
# return exactly HTTP 200 before an overall deadline.
#
# Usage: scripts/smoke-test-image.sh <image-reference>
#
# Proves: the image starts and the gateway reports healthy with its default
# configuration and no RAG service reachable (the health endpoint is
# anonymous and has no RAG health indicator).
# Does NOT prove: MCP behaviour, RAG connectivity, or live LLM calls.
#
# Environment (defaults suit CI; override only to shorten runs when testing
# this script): SMOKE_DEADLINE_SECONDS=120, SMOKE_POLL_INTERVAL_SECONDS=2,
# SMOKE_REQUEST_TIMEOUT_SECONDS=4, SMOKE_LOG_LINES=50.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <image-reference>" >&2
  exit 2
fi

image=$1
deadline_seconds=${SMOKE_DEADLINE_SECONDS:-120}
poll_interval=${SMOKE_POLL_INTERVAL_SECONDS:-2}
request_timeout=${SMOKE_REQUEST_TIMEOUT_SECONDS:-4}
log_lines=${SMOKE_LOG_LINES:-50}
health_url="http://localhost:8080/actuator/health"
container="gateway-smoke-test-$$-${RANDOM}"

# Registered before the container exists, so no exit path can leak it. The
# original exit status is preserved; signals become an ordinary exit that
# runs this same trap. The container is deliberately not started with --rm,
# so an early crash's logs survive until they are printed here.
# shellcheck disable=SC2329  # invoked via trap
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [ "$status" -ne 0 ]; then
    echo "--- last ${log_lines} log lines of ${container} ---" >&2
    docker logs --tail "$log_lines" "$container" >&2 2>&1 || true
    echo "--- end of container logs ---" >&2
  fi
  docker rm -f "$container" > /dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! docker run -d --name "$container" "$image" > /dev/null; then
  echo "::error::container could not be started from ${image}" >&2
  exit 1
fi

start=$SECONDS
while [ $((SECONDS - start)) -lt "$deadline_seconds" ]; do
  if [ "$(docker inspect --format '{{.State.Running}}' "$container")" != "true" ]; then
    exit_code=$(docker inspect --format '{{.State.ExitCode}}' "$container")
    echo "::error::container exited during startup (exit code ${exit_code})" >&2
    exit 1
  fi

  status=$(docker exec "$container" curl --silent --output /dev/null \
    --write-out '%{http_code}' --max-time "$request_timeout" --noproxy '*' \
    "$health_url" 2> /dev/null || true)
  if [ "$status" = "200" ]; then
    echo "image ${image} started and ${health_url} returned 200"
    exit 0
  fi
  sleep "$poll_interval"
done

echo "::error::container ran but ${health_url} did not return 200 within ${deadline_seconds}s (last status: ${status:-none})" >&2
exit 1

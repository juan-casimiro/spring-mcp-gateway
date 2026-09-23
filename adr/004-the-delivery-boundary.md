# ADR-004: the delivery boundary

## Context

Every green `main` build publishes versioned and `latest` images to GHCR, but
nothing deploys them to a public environment.

The gateway fronts `ai-research-assistant`, which calls a paid Anthropic API.
A public endpoint without production-grade controls would create unbounded
cost exposure. Operating a public service would also add availability and
maintenance concerns outside this academic portfolio's scope.

The published image demonstrates a reproducible build without those risks.
The supported evaluation path remains `docker compose up`, with the gateway
bound to `127.0.0.1`.

## Decision

Automation stops at the published GHCR artifact. There is no public deployment
or hosted demo. This mirrors the "Deliberately not built" decision in
`ai-research-assistant`'s ADR-003.

## What public deployment would require

Public deployment would be separate, scoped work requiring:

- **Authentication:** replace the static demo token with credentials that can
  be issued, expired, and revoked per caller.
- **Rate limiting:** replace the process-local limiter with caller-aware limits
  that hold across replicas and restarts.
- **Secret management:** store and rotate the gateway token and Anthropic key
  outside deployment manifests.
- **A cost ceiling:** enforce a hard spend cap independent of request rate.
- **TLS:** terminate HTTPS before sending credentials over the network.

## Consequences

- GHCR provides versioned delivery evidence without hosting cost or an on-call
  surface.
- Reviewers run the gateway locally; there is no hosted demo URL.
- Public deployment starts with the requirements above and its own scoped work
  (as tracked for `ai-research-assistant` in JUA-28).
- Revisit this decision if the cost or reliability trade-off changes.

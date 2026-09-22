# ADR-004: the delivery boundary

## Context

CI runs on every push and pull request (`ci.yml`). Every push to `main` that
passes also builds and publishes a versioned image to GHCR (`publish.yml`),
tagged `ghcr.io/juan-casimiro/spring-mcp-gateway:<sha>` and `:latest`. That is
as far as automation goes: nothing deploys the image to a running,
publicly reachable environment, and no such infrastructure exists in this
repo.

Two things drive that boundary:

- The gateway fronts `ai-research-assistant`, which calls a paid LLM API
  (Anthropic) on every query. `ai-research-assistant`'s ADR-003
  ("Deliberately not built") already rejected a public endpoint for the same
  underlying reason: an unauthenticated endpoint sitting in front of a live
  API key is an open wallet — anyone who finds the URL can spend against it.
- Both repos are portfolio work built during an active job search. A live
  URL that goes down, times out, or gets discovered and hammered during
  interview season is a worse signal to a reviewer than no URL at all — it
  invites debugging under exactly the wrong time pressure, for a demo nobody
  asked to be permanently live.

Publishing to GHCR without deploying anywhere keeps the versioned image as
evidence of a working, reproducible build without taking on hosting risk.
`docker compose up` — against that published image or a local build —
remains the actual way anyone evaluates the project; see `docker-compose.yml`,
where the gateway port is bound to `127.0.0.1` rather than published on all
interfaces.

## Decision

Automation stops at the artifact. CI builds, tests, and publishes a
versioned GHCR image on every green `main` build; nothing triggers
deployment beyond that, and no reachable environment for it exists.

This mirrors `ai-research-assistant`'s no-cloud-deployment decision, with one
addition specific to this gateway: even where this repo already has more
auth and rate-limiting than `ai-research-assistant` does, neither is
production-grade yet (below), so the boundary holds regardless of which
service in the chain would be exposed.

## What public deployment would require

The gap is worth naming precisely, because an unexplained absence of a
public URL reads as unawareness, while a documented, scoped decision reads
as judgement. The gateway already has partial versions of some of this; none
of it is sufficient on its own for exposure beyond `localhost`.

- **Auth.** A static bearer token exists (ADR-001), with a public demo
  default and a startup warning when that default is used unchanged. That is
  enough to keep a same-machine demo honest. It is not MCP OAuth
  authorization: no discovery, no per-caller identity, no expiry, no
  revocation. Public exposure needs credentials that can be issued and
  revoked per caller without a redeploy.
- **Rate limiting.** The process-local Resilience4j limiter (ADR-003, 30
  requests per 30 seconds) is a per-process cost backstop, sized from local
  measurement. It resets on restart, does not coordinate across multiple
  instances, and has no notion of which caller is making the request. Public
  exposure needs a limiter that holds under multiple replicas and can
  distinguish callers.
- **Secret management.** `MCP_API_TOKEN` and the RAG service's Anthropic key
  currently arrive as environment variables through Compose — adequate for a
  machine only the operator runs, not for a shared or cloud environment,
  where the same values belong in a real secrets store with rotation and
  access control instead of plaintext env vars in a deployment manifest.
- **A cost ceiling.** Nothing today caps total spend against the Anthropic
  key beyond the request-volume limiter above, which bounds request rate,
  not cost per request. Public exposure needs a hard spend cap — a
  provider-side budget alert or kill switch, or a gateway-enforced daily
  ceiling — as a backstop independent of that limiter.
- **TLS.** The demo runs on plain HTTP inside the Compose network. Public
  exposure needs HTTPS termination in front of the gateway; sending a bearer
  token over plain HTTP is one of the reasons this service isn't reachable
  from outside `localhost` today.

None of these is large in isolation, but doing all of them properly — not as
a bolt-on assembled under interview-season time pressure — is real, scoped
work, not a missing checkbox.

## Consequences

- The GHCR image is real, versioned evidence of a working, reproducible
  build — `docker compose up` can run against a pulled tag, not only a local
  build — without taking on hosting cost or an on-call surface for a project
  with no users.
- A reviewer's only way to run the gateway is `docker compose up` on their
  own machine; there is no hosted demo URL to link from a CV or portfolio
  page.
- If public deployment is picked up later, it starts from the checklist
  above instead of from "what's missing," and should land as its own scoped
  work rather than a rider on unrelated feature work — the same way
  `ai-research-assistant` tracks its equivalent gap separately (JUA-28).
- This is a standing decision, not a permanent one: it should be revisited
  if the cost/reliability trade-off changes, not treated as fixed forever.

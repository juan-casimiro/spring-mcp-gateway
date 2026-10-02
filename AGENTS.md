# spring-mcp-gateway

Short project map and durable agent guidance. Use the README and ADRs for detail.

## Shared development guidance

Follow `AGENTS.md` in the `development-config` checkout beside this repository's
main checkout (`../development-config` from the main checkout root; from a
worktree, find the main checkout with `git worktree list`). If it is
unavailable, report that and stop before task work.

## Project map

Java 25 / Spring Boot 4.1 MCP gateway exposing the sibling `ai-research-assistant` FastAPI RAG service over Streamable HTTP:

```text
MCP client → MCP tool → ResearchGateway → RagClient → FastAPI POST /query
```

- `src/main/java/com/juancasimiro/mcpgateway/mcp/`: MCP tool and response model.
- `src/main/java/com/juancasimiro/mcpgateway/application/research/`: validated question, gateway abstraction, and answer model.
- `src/main/java/com/juancasimiro/mcpgateway/integration/rag/`: HTTP client, upstream DTOs, and exception taxonomy.
- `src/main/java/com/juancasimiro/mcpgateway/config/`: authentication, health, and Resilience4j configuration.
- `adr/`: four records for architecture, tool surface, resilience, and delivery.

The sole tool is `query_research_corpus`. There is no ingest tool, retrieval-option control, public deployment, or authoritative corpus-listing endpoint; see ADR-002 and ADR-004 before proposing these.

## Development and verification

Use `./mvnw test` for unit and contract tests, `./mvnw clean verify` for the full build, and `./mvnw spring-boot:run` to run (requires the RAG service). Live integration tests use `RAG_BASE_URL=http://localhost:8000 ./mvnw verify -Plive-rag`.

Quality reports and mutation tests are optional diagnostics, not CI gates. Docker image builds skip tests; run Maven checks separately.

The `eval/` tool-selection check is opt-in and makes paid Anthropic API calls; see `eval/README.md`.

## Contracts to preserve

- Preserve source ordering; `context_sufficient=false` is valid, and `insufficiency_reason` is diagnostic only.
- Validate local input before resilience so invalid requests consume no retry, breaker call, or rate-limit permit.
- Resilience annotations require active AspectJ support. Verify behavior at the boundary.
- Exception taxonomy and Resilience4j configuration are coupled. Check ADR-003 and focused tests before changing them; aspect order is `Retry(CircuitBreaker(RateLimiter(call)))`.
- Extend WireMock tests for upstream HTTP contracts; live RAG tests are excluded from the normal suite.

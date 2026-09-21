# ADR-001: Java MCP gateway in front of the Python RAG service

## Context

The retrieval pipeline already existed in the Python
[`ai-research-assistant`](https://github.com/juan-casimiro/ai-research-assistant)
service as a FastAPI `/query` endpoint, evaluated in that repo's
[ADR-001](https://github.com/juan-casimiro/ai-research-assistant/blob/main/adr/001-chunking-and-retrieval.md).
It does not speak MCP, so MCP clients cannot call it directly.

Reimplementing retrieval in Java was discarded immediately: redoing and
re-evaluating a working pipeline would have been wasted effort for a
portfolio project.

## Options considered

- **A. Add an MCP server to the Python service.**
- **B. A separate MCP gateway in Java, in front of the unchanged service.**

## Decision

Option B. MCP clients are hosts we do not control, so every policy that must
apply to all callers has to live on the server side. A separate layer keeps
that policy out of the retrieval service:

- **Abstraction:** clients see one tool, `query_research_corpus`, with its own
  schema. `ResearchGateway` hides the upstream contract, and
  `RagClientRequestExecutor` pins `use_bm25` and `use_query_rewriting` off
  instead of exposing them.
- **Security:** a single ingress for MCP clients; input is validated in
  `ResearchQuestion` before any upstream call; the rate limiter is a spend
  backstop ([ADR-003](003-resilience-policy.md)). Auth belongs here.
- **Resilience and observability:** retry, circuit breaker and rate limiter
  run in a separate process from the work they protect, and one trace spans
  the Java/Python boundary.
- **Scalability:** the CPU/memory-heavy RAG service and the I/O-bound gateway
  can be sized, scaled and redeployed independently.

Java was chosen for academic purposes: hands-on experience with Spring Boot 4,
Spring AI 2's MCP server, `RestClient`, Resilience4j and Boot's OpenTelemetry
support. Option A would have been viable.

## Consequences

- One extra network hop; on the README trace, 3.40 s of 3.44 s is spent in
  the RAG service.
- Two services to deploy, with an HTTP contract guarded by WireMock tests.
- Known gaps: no authentication yet; the RAG service's own compose file
  publishes port `8000` on all interfaces, so it is not isolated behind the
  gateway; multiple gateway instances are untested (per-process rate limiter,
  session-based Streamable HTTP transport).

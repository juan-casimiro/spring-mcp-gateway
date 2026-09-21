# ADR-001: Java MCP gateway in front of the Python RAG service

## Context

The retrieval pipeline already exists in the Python
[`ai-research-assistant`](https://github.com/juan-casimiro/ai-research-assistant)
service, exposed as a FastAPI `/query` endpoint. Its chunking, embedding,
reranking, BM25 and query-rewriting choices were evaluated there — see that
repo's [ADR-001](https://github.com/juan-casimiro/ai-research-assistant/blob/main/adr/001-chunking-and-retrieval.md)
and [ADR-002](https://github.com/juan-casimiro/ai-research-assistant/blob/main/adr/002-evaluation-methodology.md).

The goal is to make that retrieval available to MCP clients (Claude Desktop,
MCP Inspector, other agent hosts). The FastAPI service does not speak MCP, so
an MCP client cannot call it directly today.

MCP clients are hosts we do not control. Circuit breaking, retries, rate
limiting, tracing, input validation, authentication and the choice of which
operations are exposed cannot be delegated to them: whatever policy applies to
every caller has to live on the server side.

## Options considered

**A. Add an MCP server to the Python service.** Fewest moving parts. Most
policy concerns could be built in Python too (MCP SDK, retry/breaker and
rate-limit libraries, the OpenTelemetry instrumentation the service already
has). But every concern would share a process, deployment and release cycle
with the retrieval and LLM code.

**B. A separate MCP gateway in front of the unchanged Python service.** The
RAG service keeps its HTTP contract; the gateway owns the MCP surface and
every cross-cutting policy.

**C. Reimplement retrieval in Java** (e.g. Spring AI vector stores with Java
embedding and reranking), removing the Python service.

## Decision

Option B, with the gateway written in Java on Spring Boot 4 and Spring AI.

## Why a separate MCP layer

### Abstraction of the exposed service

- MCP clients see one tool, `query_research_corpus`, with a camelCase schema
  (`question`, `resultCount`; `answer`, `sources`, `contextSufficient`,
  `insufficiencyReason`), not the RAG service's snake_case `/query` contract.
- The `ResearchGateway` interface separates MCP-facing and application models
  from `RagClient` and its DTOs. The upstream contract can change, be
  versioned, or be replaced by another backend without changing what MCP
  clients see.
- The tool surface is deliberately narrower than the upstream API.
  `use_bm25` and `use_query_rewriting` are pinned off when
  `RagClientRequestExecutor` builds the upstream request, rather than exposed, so experimental retrieval options stay unreachable
  and an upstream default change cannot silently alter gateway behaviour.
- Further backends can be exposed as additional tools from the same endpoint
  without clients integrating with each service individually.

### Security

- The gateway is the single ingress point for MCP clients. The RAG service,
  which holds the corpus and spends paid LLM calls, does not need to be
  reachable by clients at all.
- Input is validated before any upstream call (`ResearchQuestion`: 1–1,000
  characters after trimming, `resultCount` 1–20), so malformed or oversized
  requests never reach retrieval or the LLM and do not consume retry or
  circuit-breaker capacity.
- The `rag` rate limiter caps downstream attempts as a spend backstop — see
  [ADR-003](003-resilience-policy.md).
- Authentication and authorization belong at this layer, once, rather than in
  each backend service.

### Resilience and observability

- Retry, circuit breaker and rate limiter wrap every downstream attempt in
  `RagClientRequestExecutor`, and upstream failures are translated into
  explicit exception types (`RagTimeoutException`, `RagUnavailableException`,
  `RagContractException`, …). Policy detail is in
  [ADR-003](003-resilience-policy.md).
- Protection runs in a separate process from the work it protects: a stalled
  or overloaded retrieval process cannot disable its own circuit breaker.
- One `rag.query` span per logical query carries retrieval-specific
  attributes, and the trace continues into the Python service, so a single
  trace crosses the Java/Python boundary (see the README's
  [One request, one trace](../README.md#one-request-one-trace)).

### Scalability

- The two layers have different resource profiles. The RAG service is
  CPU/memory-heavy (embedding, cross-encoder reranking, LLM calls); the
  gateway is I/O-bound request handling. As separate deployments they can be
  sized and scaled independently.
- Resilience, rate-limit and exposure policy can change and be redeployed
  without rebuilding or restarting the RAG service and its models.

## Why Java

Option A could deliver most of the same capabilities in Python; the choice of
Java for the gateway is not a claim that only Java can. The gateway was built
in Java deliberately, as an academic exercise: to gain hands-on experience
with current Spring — Spring Boot 4, Spring AI 2's MCP server annotations
(`@McpTool`) over Streamable HTTP, `RestClient`, `@ConfigurationProperties`,
Resilience4j integration and Boot's OpenTelemetry support — on a real
integration problem rather than a toy example.

## Why retrieval was not reimplemented in Java (option C rejected)

- The Python pipeline is the evaluated one. Its chunking, embedding model,
  reranker and the decisions not to enable BM25 or query rewriting by default
  are backed by the evaluation recorded in the Python ADRs. A Java rewrite
  would be a second, unevaluated implementation that would need that work
  repeated before it could be trusted.
- The Python ML ecosystem (FastEmbed, cross-encoder rerankers, the evaluation
  harness) is where that work lives. Porting it adds risk without any
  expected gain in retrieval quality.
- The gateway's value is the boundary it provides, not where retrieval runs.
  Keeping retrieval in Python keeps each service focused on one job.

## Consequences

- An extra network hop per call. On the trace in the README, 3.40 s of the
  3.44 s total is spent inside the RAG service, so gateway overhead is small
  relative to retrieval and generation.
- Two services to build, deploy and monitor, and an HTTP contract between
  them that must be kept compatible. The WireMock contract tests guard the
  gateway side of it.

Known gaps between this rationale and the current implementation:

- **No authentication yet.** The gateway and its Actuator endpoints are
  unauthenticated; the gateway is only bound to localhost in the Docker
  Compose stack. Auth is a responsibility this layer is meant to own, not one
  it fulfils today.
- **The RAG service is not network-isolated in the demo stack.** The
  `ai-research-assistant` compose file publishes port `8000` on all
  interfaces for direct local use. Keeping the backend private is enabled by
  this design, not yet enforced by it.
- **Horizontal scaling is untested.** The rate limiter is per gateway
  process, so each instance has its own budget (ADR-003). The MCP server runs
  the session-based Streamable HTTP transport, so multiple instances would
  likely need session affinity or a stateless transport. Neither has been
  exercised.

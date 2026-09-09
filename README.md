# spring-mcp-gateway

A Spring Boot MCP gateway that exposes an existing FastAPI retrieval-augmented generation (RAG) service to MCP clients.

The gateway publishes MCP tools over Streamable HTTP. MCP-facing models are mapped to application models, and the `ResearchGateway` abstraction isolates application code from the FastAPI `/query` JSON contract.

```text
MCP client → MCP tool → ResearchGateway → RagClient → FastAPI /query
```

## MCP tools

### `query_research_corpus`

Searches the biomedical research corpus and returns an evidence-backed answer.

Inputs:

- `question` (required string): the question to answer
- `resultCount` (optional integer): maximum retrieved chunks; defaults to `8`

Output:

- `answer`: generated answer
- `sources`: ordered source identifiers
- `contextSufficient`: whether the retrieved context was sufficient
- `insufficiencyReason`: explanation when context is insufficient; otherwise may be `null`

The gateway pins the upstream `use_bm25` and `use_query_rewriting` flags off explicitly rather than inheriting the RAG service's defaults, so a change to those defaults cannot silently alter gateway behaviour. On this corpus, query rewriting measured no change and BM25 measured one attributable regression — see [ADR-001 in `ai-research-assistant`](https://github.com/juan-casimiro/ai-research-assistant/blob/main/adr/001-chunking-and-retrieval.md) for the full evaluation.

## Prerequisites

- Java 25
- The included Maven Wrapper
- The FastAPI RAG service from [`ai-research-assistant`](https://github.com/juan-casimiro/ai-research-assistant) for running the application or live integration tests

Set up and start the upstream service by following its [`README`](https://github.com/juan-casimiro/ai-research-assistant#readme).

## Configuration

The upstream RAG service defaults to `http://localhost:8000`. Override it with:

```bash
export RAG_BASE_URL=http://your-rag-service:8000
```

The configured connection and read timeouts are `4s` and `60s`, respectively.

OTLP metric and trace export are disabled by default for local development. Enable them independently with:

```bash
export OTEL_METRICS_EXPORT_ENABLED=true
export OTEL_TRACING_EXPORT_ENABLED=true
```

Tracing samples all requests by default. Spring Boot Actuator, Micrometer, and OpenTelemetry provide the observability foundation.

## Build and run

Build the application:

```bash
./mvnw clean package
```

Run the gateway after starting the FastAPI RAG service:

```bash
./mvnw spring-boot:run
```

## Docker

Docker with BuildKit is the only build prerequisite; no local Java, Maven cache,
or prebuilt `target/` directory is needed. From the repository root:

```bash
docker build --pull -t spring-mcp-gateway:local .
```

The builder uses Java 25 and the Maven Wrapper, then extracts the JAR with
[Spring Boot 4.1 tools mode](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html).
The runtime contains a Java 25 JRE, curl for health probes, and separate Boot
dependency/application layers. It runs as UID/GID `10001:10001`; application
files are root-owned. Java runs as PID 1 and receives Docker stop signals directly.

BuildKit caches Maven downloads across source changes. Wrapper/POM inputs are
copied before source files, and stable dependency layers precede application
code. Builds also work with an empty cache. The Java 25 Ubuntu Noble base tags
receive updates; `--pull` refreshes them. These are repeatable source builds,
not a promise of byte-identical images across base-image updates. No platform is
hardcoded; Docker selects the base image for the target architecture.

Image packaging skips test execution; run `./mvnw clean test` separately before
using the image. The Docker build context includes only the Maven build inputs
and source tree, excluding local build outputs, Git history, and `.env` files.
Never place credentials in source resources or pass secrets as build arguments.

### Run and configure

Set `RAG_BASE_URL` to an address reachable **from the container**:

```bash
docker run -d --name mcp-gateway \
  -p 127.0.0.1:8080:8080 \
  -e RAG_BASE_URL=http://rag-service:8000 \
  spring-mcp-gateway:local
```

Replace `rag-service` with your upstream hostname. For another container, attach
both containers to the same user-defined Docker network (`--network <network>`)
and use the upstream container's network name. For a service on the host, Docker
Desktop provides `host.docker.internal`; on Linux Docker Engine, add
`--add-host=host.docker.internal:host-gateway` and ensure the upstream listens on
an interface reachable from Docker. The existing `localhost:8000` application
default refers to the gateway container itself and will not reach a separate RAG
service. Multi-service setup is deferred to JUA-66.

All Spring configuration remains external through environment variables, such
as `RAG_BASE_URL`, `RAG_RATE_LIMIT_FOR_PERIOD`, `OTEL_TRACING_EXPORT_ENABLED`, and
`SERVER_PORT`. Use `JAVA_TOOL_OPTIONS` for JVM options. Future secrets should be
injected at runtime using your deployment's secret mechanism; Spring config-tree
imports can read mounted secret files. Do not bake them into the image.

The default container port is `8080`. If changing ports or the Actuator path,
adjust the port mapping and set `HEALTHCHECK_URL` to the **internal** health URL:

```bash
docker run -d --name mcp-gateway-custom \
  -p 127.0.0.1:8081:9090 \
  -e SERVER_PORT=9090 \
  -e HEALTHCHECK_URL=http://localhost:9090/actuator/health \
  -e RAG_BASE_URL=http://rag-service:8000 \
  spring-mcp-gateway:local
```

### Verify and stop

```bash
docker logs mcp-gateway
curl --fail http://localhost:8080/actuator/health
docker inspect --format '{{.State.Health.Status}}' mcp-gateway
docker exec mcp-gateway id
```

Expect a JSON health response with `"status":"UP"`, Docker health `healthy` after the first successful
probe, and UID/GID `10001`. The probe runs every 30 seconds, permits 30 seconds
for startup, and marks the container unhealthy after three consecutive failures.
It uses a four-second HTTP timeout and fails on HTTP errors or connection errors.
Docker health status does not itself restart the container.

Actuator health reports gateway health; it does not verify RAG reachability or
perform paid retrieval/LLM calls. Use the MCP Inspector flow below against
`http://localhost:8080/mcp` to verify a real upstream query separately.

```bash
docker stop mcp-gateway
docker rm mcp-gateway
```

## Verify with MCP Inspector

1. Start `ai-research-assistant` by following its linked README above.
2. Start the gateway:

   ```bash
   ./mvnw spring-boot:run
   ```

3. In another terminal, start MCP Inspector:

   ```bash
   npx @modelcontextprotocol/inspector
   ```

4. In Inspector, select **Streamable HTTP** and connect to `http://localhost:8080/mcp`.
5. Open **Tools** and confirm that `query_research_corpus` is listed.
6. Invoke the tool with:

   ```json
   {
     "question": "What does CT-FFR measure in coronary artery disease?",
     "resultCount": 8
   }
   ```

   `resultCount` is optional and defaults to `8`.

The end-to-end check succeeds when Inspector connects, discovers the tool, and the invocation reaches the upstream FastAPI service and returns a grounded `answer`, ordered `sources`, `contextSufficient`, and `insufficiencyReason`.

## Tests

Run the normal unit, application-context, and WireMock-backed RAG contract tests:

```bash
./mvnw test
```

For opt-in coverage, complexity, CRAP-style summaries, and focused mutation testing,
see [diagnostic quality checks](quality/README.md). The
[JUA-82 audit](quality/JUA-82-audit.md) records the behavioural gaps and test-effectiveness evidence.

`RagClientIT` is excluded from normal test runs. To execute it against a running FastAPI RAG service:

```bash
RAG_BASE_URL=http://localhost:8000 ./mvnw verify -Plive-rag
```

## Technology stack

- Java 25
- Spring Boot 4.1.1
- Spring AI 2.0.1
- Maven 3.9.16 via the Maven Wrapper
- Spring MVC and `RestClient`
- Spring Boot Actuator, Micrometer, and OpenTelemetry
- JUnit 5, Mockito, AssertJ, and WireMock 4.2.2

Spring AI dependency versions are managed through the imported Spring AI BOM.

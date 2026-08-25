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

## Tests

Run the normal unit, application-context, and WireMock-backed RAG contract tests:

```bash
./mvnw test
```

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

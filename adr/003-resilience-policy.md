# ADR-003: Resilience policy and parameter rationale

## Context

The gateway protects one downstream dependency: `ai-research-assistant`'s
`POST /query` endpoint. The policy should keep transient failures bounded,
avoid repeating expensive work after a timeout, and report technical failure
honestly to an MCP client.

`POST /query` is safe to retry despite using POST because it does not mutate
the corpus or other application state. A retry repeats retrieval, reranking,
and generation, so retries must still be limited.

## Decision

- The gateway is the sole retry owner.
- Only availability failures are retried, with at most three total attempts.
- Timeouts and contract failures are not retried.
- The circuit breaker measures downstream attempts, including retries.
- No bulkhead is configured because measurement did not establish a useful
  concurrency ceiling.
- A process-local rate limiter bounds request volume as a cost-control policy.
- Technical failure is returned as an MCP tool error, never as a successful
  degraded response.

## Retry and timeout policy

### Failure classification

| Condition | Gateway exception | Retried? | Breaker result |
| --- | --- | :---: | --- |
| Connection failure other than a socket timeout | `RagUnavailableException` | Yes | Failure |
| HTTP 5xx other than 504 | `RagUnavailableException` | Yes | Failure |
| Connect or read timeout | `RagTimeoutException` | No | Failure |
| HTTP 504 | `RagTimeoutException` | No | Failure |
| HTTP 4xx | `RagContractException` | No | Ignored |
| Null or malformed successful body | `RagContractException` | No | Ignored |
| Breaker open | `RagCircuitOpenException` | No | No downstream call |
| Rate limit exhausted | `RagRateLimitException` | No | Ignored |
| Invalid local input | `InvalidResearchQuestionException` | No | Never enters resilience path |
| `context_sufficient=false` | None | No | Success |

Timeouts are not retried because the downstream may still be doing expensive
work. Retrying could start a second retrieval and LLM call while the first is
still running. The current HTTP request factory does not distinguish connect
and read timeouts reliably without inspecting message text, so both use the
safer non-retryable classification.

Contract failures are ignored by the breaker because waiting for the service
to recover cannot fix an incompatible request or response. An insufficient
corpus result is different: the pipeline completed successfully, so
`context_sufficient=false` remains a valid domain response.

### Configuration and total budget

| Setting | Value | Rationale |
| --- | --- | --- |
| `max-attempts` | 3 | Allows two retries for a transient availability failure without unbounded amplification. |
| `wait-duration` | 1s | Adds at most two seconds of backoff to one logical request. |
| `retry-exceptions` | `RagUnavailableException` | Keeps timeout and contract failures non-retryable by construction. |
| `rag.connection-timeout` | 4s | Generous for a local Compose-network connection. |
| `rag.read-timeout` | 60s | Covers the measured query path and the Python service's bounded LLM calls. |

The cross-layer budget has one owner:

- Java makes at most three attempts.
- The Python service performs no application-level retry.
- The Anthropic client is configured with `max_retries=0`.

This avoids multiplicative retries. Before the SDK retry was disabled, its
default two retries could combine with three gateway attempts to produce up
to nine LLM calls for one MCP request.

The gateway owns the retry budget even though this repeats the full pipeline.
For example, an Anthropic 429 or 5xx that becomes a Python 500 is retried by
the gateway as an availability failure. An SDK retry would repeat less work,
but a single visible owner is easier to bound and circuit-break.

The 60-second read timeout applies per attempt, not per logical request. A
retryable 5xx arriving near the timeout on all three attempts could therefore
take about 182 seconds: three 60-second attempts plus two 1-second waits. A
fresh breaker permits this first three-attempt request because it does not
evaluate rates until five calls have been recorded. This tail is accepted for
this portfolio system; a production service with a caller deadline should add
an overall time budget.

### Evidence for the timeout values

A warm sample of 12 sequential queries using `n_results=20` completed in
2.580–7.074 seconds, with a 5.868-second median. The sample is useful sizing
evidence, not a production latency percentile. The 60-second read timeout
also accommodates the Python service's 35-second grounded-answer timeout and
the optional 10-second rewrite followed by a 35-second answer call.

The gateway normally requests eight results and disables BM25 and query
rewriting, so the measured 20-result path is conservative for current use.
Exposing a slower workload requires remeasuring both the read timeout and the
circuit breaker's slow-call threshold.

HTTP 503 remains retryable with the other 5xx responses. A cold start usually
takes 30–60 seconds, so two one-second waits will not bridge it; the value of
the policy is recovery from short failures in an already-warm service. The
gateway deliberately does not parse response text to distinguish loading from
a permanent startup error.

## Circuit breaker

| Setting | Value | Rationale |
| --- | --- | --- |
| `sliding-window-type` | `COUNT_BASED` | Straightforward for low, bursty traffic. |
| `sliding-window-size` | 10 attempts | Keeps a small recent history. |
| `minimum-number-of-calls` | 5 attempts | Avoids evaluating a rate from only one or two failures. |
| `failure-rate-threshold` | 50% | Opens when half or more recorded attempts fail. |
| `slow-call-duration-threshold` | 20s | About 2.8 times the measured 7.074-second maximum while still below the 60-second read timeout. |
| `slow-call-rate-threshold` | 50% | Opens when half or more recorded attempts are slow. |
| `wait-duration-in-open-state` | 30s | Pauses calls before allowing a recovery probe. |
| `permitted-number-of-calls-in-half-open-state` | 1 | Limits recovery testing to one real query. |
| `automatic-transition-from-open-to-half-open-enabled` | `false` | Uses a lazy transition on the next caller instead of a background monitoring thread. |
| `record-exceptions` | `RagException` | New gateway RAG exceptions fail closed unless explicitly ignored. |
| `ignore-exceptions` | `RagContractException`, `RequestNotPermitted` | Contract and local rate-limit failures do not indicate downstream health. |

Resilience4j opens at or above a configured percentage. A 50% threshold means
half the recorded attempts, not a strict majority.

The aspect order is:

```text
Retry(CircuitBreaker(RateLimiter(downstream call)))
```

The breaker therefore records each protected RAG attempt rather than each MCP
request. With three attempts, one logical request can contribute three
failures. This is intentional: retries consume downstream capacity and are
additional evidence that the protected dependency is unhealthy.

The original 60-second slow-call threshold was ineffective because the
client's 60-second read timeout failed the call first. Measurement also
contradicted the assumption that normal queries took roughly 60 seconds; the
observed maximum was about seven seconds. The corrected 20-second threshold
can detect degraded but still-completing calls.

The single half-open call uses the real query path. A lightweight health check
would not exercise retrieval or the LLM and therefore would not prove that the
failed dependency path had recovered.

No Resilience4j fallback method is configured. A fallback throwing a retryable
exception would be seen by the outer retry aspect and repeatedly call an open
breaker. Instead, `RagClient` translates `CallNotPermittedException` into the
gateway-owned `RagCircuitOpenException` while preserving the original cause.

### Accepted limitations

The RAG service's readiness check excludes the Anthropic call. A service that
reports ready can therefore fail the single half-open probe when Anthropic is
degraded, returning the breaker to open even if its local retrieval path is
healthy. The real query remains the right probe because a lightweight health
check would not test the failing path.

The Python service keeps `max_tokens=1024`. Reaching that cap during structured
output can cause a deterministic parse failure that surfaces as a retryable
500, spending all three gateway attempts. This is accepted because observed
answers used roughly 130–200 tokens; the limit should be revisited if truncated
responses appear.

## Concurrency and request-volume controls

### No bulkhead

The initial hypothesis was that synchronous cross-encoder reranking serialized
concurrent Python requests. Reranking was moved off the event loop with
`asyncio.to_thread()`, which fixed the structural problem.

Normal-load measurements then showed throughput continuing to improve from
one through eight concurrent requests. Median latency increased from 2.63s at
concurrency 1 to 4.39s at concurrency 8, while throughput increased from about
0.38 to 1.77 requests per second. No saturation point was observed, so the
measurements do not justify a bulkhead value of 2, 4, 8, or another number.

The gateway therefore has no bulkhead. A future limit could still be chosen as
an explicit abuse- or cost-control policy, but it must not be presented as a
measured service-capacity limit.

### Process-local rate limit

| Setting | Value | Rationale |
| --- | --- | --- |
| `limit-for-period` | 30 (`RAG_RATE_LIMIT_FOR_PERIOD`) | Policy limit on paid-call volume, not a measured capacity. |
| `limit-refresh-period` | 30s (`RAG_RATE_LIMIT_REFRESH_PERIOD`) | Defines the policy window and remains configurable. |
| `timeout-duration` | 0 | Rejects immediately instead of holding an MCP call while waiting for a permit. |

The rate limiter is shared by callers within one gateway process; it is not
per-client or distributed. Retries consume permits because they are real
downstream attempts. An open breaker rejects before the rate limiter, so it
does not consume a permit.

## Caller-facing failure contract

Technical failures are never converted into `QueryResearchCorpusResponse`.
The tool rethrows them, and Spring AI returns an MCP tool error
(`isError=true`) with the gateway exception's safe message. This keeps an
outage distinct from the successful domain result `contextSufficient=false`
and prevents a calling model from treating technical error text as research
evidence.

| Exception | Client-facing message |
| --- | --- |
| `RagUnavailableException` | "The research corpus is currently unavailable. Do not answer from general knowledge; tell the user that retrieval failed." |
| `RagTimeoutException` | "The research request timed out. Do not answer from general knowledge; tell the user that retrieval did not complete." |
| `RagContractException` | "The research service could not process this request. This is an internal error; do not retry with the same input." |
| `RagCircuitOpenException` | "The research service is temporarily unavailable because the circuit breaker is open." |
| `RagRateLimitException` | "The research service request limit has been reached. Please try again later." |
| `InvalidResearchQuestionException` | States the violated question-length or result-count bound. |

The tool logs unavailable, timeout, circuit-open, rate-limit, and validation
failures at WARN with the safe message only (no stack trace, no question text),
and contract failures at ERROR. Rate-limit rejection is an expected operational
condition, so it follows the circuit-open treatment; a sustained WARN rate shows
a client exceeding the limit. `RagRateLimitException` is created without a cause
because the MCP layer appends cause messages to the tool error, which would
expose the Resilience4j limiter diagnostic to callers.

## Verification finding

Integration testing found that the resilience annotations were inert until
`spring-boot-starter-aspectj` was added. This was a production-activation gap,
not test plumbing: configuration and annotations compiled while no advice ran.
Resilience behavior must therefore be verified at the HTTP boundary rather
than inferred from annotations.

## Consequences

- Retry amplification is bounded and has one owner, but one logical request
  can still last about 182 seconds in the documented worst case.
- Breaker thresholds are interpreted in downstream attempts, not user
  requests.
- Slow successful calls can now contribute to opening the breaker before the
  client read timeout is reached.
- No unsupported bulkhead number is presented as measured capacity.
- Rate limiting controls process-local request volume, not concurrency or a
  distributed budget.
- MCP callers receive explicit technical errors instead of fabricated
  degraded successes.

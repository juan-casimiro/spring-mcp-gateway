# ADR-003: Resilience policy and parameter rationale

## Context

The gateway sits between an MCP client and `ai-research-assistant`, whose
`POST /query` path performs retrieval, reranking, and a paid LLM call. A
failure policy therefore has to balance three risks: hiding a real outage,
repeating expensive work that may still be running, and presenting a
technical failure to an LLM as though it were a valid research result.

This ADR records the policy implemented around `RagClient.query`. It also
records where measurement disproved the initial design and where evidence is
still missing. A bulkhead is deliberately not configured yet; JUA-58 owns the
concurrency measurement needed to choose its limit.

## Decisions made

### One retry owner, with three total attempts

The gateway is the only retry owner. Its named `rag` retry makes at most three
total attempts with a fixed one-second wait between attempts. The Python
service configures the Anthropic client with `max_retries=0`, so there is no
multiplicative retry across Java, Python, and the provider SDK.

Putting the budget at the gateway gives the component that owns the
user-facing latency budget control of retries and lets the circuit breaker
observe them. The trade-off is that a gateway retry repeats embedding,
retrieval, reranking, and generation, whereas an SDK retry would repeat only
the LLM call. This extra local work is accepted in exchange for one visible
budget with one owner. The provider failures normally retried by the SDK occur
before generation and consume no output tokens, so moving ownership does not
add token cost.

Three attempts and one-second waits keep the additional delay to roughly two
seconds rather than tens of seconds. This budget can recover from a short
transport or server interruption, but it intentionally does not wait for the
Python service's 30–60-second model-loading window. A loading response and a
permanent startup failure both use HTTP 503 and differ only in human-readable
detail. Parsing that text would create a brittle cross-service contract, so
both spend the short retry budget and then fail clearly. The loading case will
usually not recover within the budget; that limitation is explicit.

`POST /query` is safe to retry despite POST not being idempotent by HTTP
semantics because this endpoint mutates no state. That property is a
precondition of the policy, not an assumption that arbitrary POST requests are
retryable.

### Retry only availability failures

The gateway retries only `RagUnavailableException`:

| Outcome | Gateway classification | Retry | Circuit breaker |
| --- | --- | :---: | :---: |
| Non-timeout transport failure | `RagUnavailableException` | yes | failure |
| Non-504 HTTP 5xx | `RagUnavailableException` | yes | failure |
| HTTP 504 or socket timeout | `RagTimeoutException` | no | failure |
| HTTP 4xx or invalid response contract | `RagContractException` | no | ignored |
| Locally rejected tool input | `InvalidResearchQuestionException` | n/a | never reached |
| Valid `context_sufficient=false` result | successful response | n/a | success |

Timeouts are not retried because downstream work may already have completed or
may still be executing. Repeating the request could create a second concurrent
pipeline and a second paid LLM call. The Python service's 35-second grounded
answer timeout is returned as HTTP 504 so that an inner LLM timeout receives
the same non-retryable treatment as the gateway's own read timeout.

All other server-side 5xx responses and non-timeout transport failures default
to unavailable. This is deliberately conservative, not a claim that every 5xx
is transient. Statuses such as 501 or 505 can be permanent and will waste the
three-attempt budget. The cheaper error is accepted because classifying an
unknown outage as a contract failure would cause the breaker to ignore it and
remain closed through a real dependency failure.

Contract failures are deterministic for identical input and do not establish
that the dependency is unavailable, so retrying them wastes the budget and
counting them could open the breaker on caller or integration defects.
`RagContractException` is explicitly ignored rather than merely omitted from
the recorded list so its circuit-breaker metric is labelled as ignored rather
than successful.

The exception hierarchy is flat below `RagException`. In particular,
`RagTimeoutException` is not a subtype of `RagUnavailableException`. This
makes timeout retry exclusion the default: making timeouts retryable requires
an explicit configuration addition rather than an accidental deletion from an
ignore list. The breaker records the `RagException` supertype and then ignores
`RagContractException`, so any future upstream exception is conservatively a
breaker failure while remaining non-retryable unless explicitly added.

### Timeouts bound each layer

The gateway uses a four-second connection timeout and a 60-second read
timeout. The Python service permits one 35-second grounded-answer LLM attempt;
its optional query rewrite has a separate 10-second timeout. Query rewriting
is pinned off by the gateway, but even the potential sequential 10 + 35-second
LLM budget remains inside the outer read timeout with room for retrieval,
reranking, and response handling.

The 60-second read timeout is intentionally generous. Twelve sequential warm
queries using the maximum `n_results=20`, with BM25 and rewriting disabled,
took 2.580–7.074 seconds. The observed median was 5.868 seconds and the
nearest-rank sample p90 was 7.056 seconds. This is representative evidence,
not a statistically reliable production p99. The gateway normally defaults to
eight results, not twenty, so the measured workload is a pessimistic retrieval
case rather than the literal default request shape.

The two timeout types cannot be distinguished reliably under
`SimpleClientHttpRequestFactory`: both surface as `SocketTimeoutException` and
are separable only by message. Both are therefore classified as non-retryable.
This gives up a cheap retry for a true connection timeout, but avoids
misclassifying a read timeout and repeating expensive work. Connection refused
and the other common transport failures do not share this ambiguity and remain
retryable.

### One breaker for one downstream dependency

The gateway uses one count-based circuit breaker for the RAG dependency. Its
window contains ten downstream attempts, evaluation begins after five calls,
and either a 50% failure rate or a 50% slow-call rate opens it. With the minimum
sample, three failures or three slow calls are enough to open; with a full
window, five are enough. This deliberately reacts when half or more of a full
window is degraded while requiring repeated evidence rather than tripping on one
failure in a low-traffic, single-user portfolio service. The small window also
avoids requiring a production traffic volume that this service does not have.

The slow-call duration threshold is 20 seconds. A warm sample observed a
maximum of 7.074 seconds, making the threshold roughly 2.8 times that maximum,
while the Python service can spend up to 35 seconds on the grounded-answer
call. The 20–35-second band is therefore a useful degradation signal: the
dependency is still returning successfully, but materially outside the
observed envelope.

The original proposal pinned the slow-call threshold to the 60-second read
timeout because normal queries were assumed to take roughly 60 seconds.
Measurement contradicted that assumption by about an order of magnitude. A
60-second threshold would also have been unreachable in practice: the read
timeout would convert the call into a failure before it could be recorded as a
slow success. Replacing it with 20 seconds turned a decorative setting into an
active detector.

The 20-second value is coupled to the gateway pinning query rewriting off. If
rewriting becomes a tool parameter, a legitimate call could contain the
sequential 10-second rewrite and 35-second answer budgets. The threshold must
then be revisited. A second breaker is not the answer because it would split
state for one downstream dependency.

The breaker stays open for 30 seconds. This is long enough to stop an immediate
retry storm and let a short dependency interruption clear, without pretending
to cover the Python service's 30–60-second cold start. Automatic transition is
disabled, so an idle service does not change state without a real caller. After
the delay, one genuine `/query` is permitted in half-open state. One probe
limits cost and decides recovery quickly; multiple simultaneous paid probes
would add little evidence for this traffic profile.

The half-open probe deliberately does not call `/health`. The health endpoint
checks model and corpus readiness but excludes a live Anthropic round-trip. A
healthy response therefore cannot prove that the expensive query path has
recovered. The residual risk is that a real half-open probe can run the full
pipeline and time out when Anthropic, rather than the Python service, is
degraded.

The default Resilience4j aspect order is retained: retry wraps the circuit
breaker, so the breaker measures downstream HTTP attempts rather than one
MCP-level invocation. This opens the breaker faster during a genuine outage.
There is no evidence that custom aspect ordering would improve behaviour.

### Technical failures remain MCP errors

There is no `fallbackMethod` and no degraded `QueryResearchCorpusResponse`.
Technical failures are logged and rethrown at the `@McpTool` boundary so Spring
AI represents them as MCP tool errors. `contextSufficient=false` is reserved
for the successful domain result in which the RAG pipeline completed but the
corpus did not contain enough evidence.

Conflating those outcomes would invite a calling model to treat an outage as a
research finding or to present technical error text as an answer. The
exception messages therefore instruct the model not to answer from general
knowledge and not to retry deterministic contract failures with identical
input.

Avoiding `fallbackMethod` also prevents an outer retry aspect from retrying a
fallback against an already-open breaker. `CallNotPermittedException` is not
on the retry whitelist, so it fails immediately. The accepted cost is an
abstraction leak: the MCP tool layer imports a Resilience4j exception even
though it otherwise depends on the application-level `ResearchGateway`.

## Deferred: bulkhead limit

No bulkhead is configured. A numeric limit without concurrency evidence would
be guesswork and could either reject useful work or permit enough simultaneous
requests to overload the Python service and multiply paid LLM calls.

JUA-58 will measure latency at 1, 2, 4, and 8 concurrent requests. Its specific
hypothesis is that Chroma and BM25 lookups are moved off the Python event loop,
but the cross-encoder rerank still executes synchronously and may serialize
concurrent requests. That result will determine whether the correct response
is a measured gateway bulkhead, a Python-side concurrency fix, or both. Once
that decision is implemented, this ADR must be amended with the chosen limit,
queue behaviour, measurements, and cost-containment rationale.

## Consequences

- A transient availability failure can consume at most three full gateway
  attempts and roughly two seconds of retry waiting.
- Timeouts and contract failures fail once rather than repeating expensive or
  deterministic work.
- Repeated hard failures or sustained latency degradation stop new calls for
  30 seconds, after which one real request tests recovery.
- The caller can distinguish a valid insufficient-evidence result from a
  technical failure and avoid hallucinating around an outage.
- Some permanent 5xx responses spend the retry budget, and the short budget is
  knowingly ineffective for cold-start 503 responses.
- Until JUA-58 is complete, concurrent calls are not bounded at the gateway.

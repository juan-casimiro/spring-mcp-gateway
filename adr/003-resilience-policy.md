# ADR-003: Resilience policy and parameter rationale

## Context

The gateway's reason to exist, per [Epic C](https://linear.app/juan-casimiro-agent/issue/JUA-44/epic-c-resilience4j-on-the-rag-client):
an MCP client cannot itself do timeouts, retry, circuit breaking, or
concurrency limiting. The gateway can, and does, for its one downstream
dependency — `ai-research-assistant`'s `/query` endpoint.

Guiding principle: **retry at the layer that owns the failure.** A 429 from
the LLM provider surfaces as a failed ~35s pipeline call; retrying it in Java
re-runs retrieval, reranking *and* generation. That retry belongs inside the
Python service, not here. Every parameter below has a stated reason, and the
bulkhead question was settled with a measurement rather than an assertion.

This document's previous version, merged under
[JUA-58](https://linear.app/juan-casimiro-agent/issue/JUA-58/bulkhead-measure-downstream-concurrency-behaviour-then-bound-it)
and JUA-72, covered only the concurrency conclusion and the rate limiter and
said explicitly that the rest was pending this issue. That content is folded
in below, unchanged in substance, alongside everything else this issue adds.

## Why `/query` is safe to retry at all

`POST /query` is not idempotent by HTTP semantics. It is safe to retry here
regardless, because it mutates nothing — no corpus write, no state change,
purely retrieval and generation over an existing index. That precondition is
what makes any retry policy on this call legal; it was not assumed safe by
default.

## Retry policy

One shared `Retry` instance (`resilience4j.retry.instances.rag`), **not** one
instance per failure type. A Resilience4j `Retry` instance has a single
`maxAttempts` and the failure type isn't known before the call is made, so
per-failure attempt counts were considered and dropped as unimplementable
without multiple instances routed to by guesswork.

| Setting | Value | Why |
| --- | --- | --- |
| `max-attempts` | 3 | Roughly three total attempts — enough to ride out a transient blip without turning one MCP call into an unbounded loop. |
| `wait-duration` | 1s | Short backoff; total retry time is seconds, not tens of seconds — the caller is a synchronous MCP tool call, not a background job. |
| `retry-exceptions` | `RagUnavailableException` only | Retryable failures are exactly one type. See classification below. |

### Retry per failure class, not blanket

| Upstream condition | Exception | Retried? | Circuit breaker |
| --- | :---: | :---: | :---: |
| Connection refused, DNS failure, reset, broken pipe (any `ResourceAccessException` without a `SocketTimeoutException` cause) | `RagUnavailableException` | ✅ | failure |
| `504 Gateway Timeout` | `RagTimeoutException` | ❌ | failure |
| Any other `5xx` (500–503, 505, 507, 508, 511, …) | `RagUnavailableException` | ✅ | failure |
| Connect or read timeout (`ResourceAccessException`/`SocketTimeoutException`) | `RagTimeoutException` | ❌ | failure |
| Any `4xx`, including `422` | `RagContractException` | ❌ | ignored |
| Null or malformed successful body | `RagContractException` | ❌ | ignored |
| Breaker open (`CallNotPermittedException`) | `RagCircuitOpenException` | ❌ | n/a (rejected before a call is made) |
| Rate limiter exhausted (`RequestNotPermitted`) | `RagRateLimitException` | ❌ | ignored |
| Local input rejected (`InvalidResearchQuestionException`) | — | n/a | never reaches the breaker |
| `context_sufficient=false` | — | n/a | success |

This is the shipped mapping in `RagClientRequestExecutor` and
`resilience4j.circuitbreaker.instances.rag`. It is intentionally more
conservative than an earlier draft that named only `500 / 502 / 503` as
retryable: any other `5xx` this service has never returned (505, 507, 508,
511, …) still defaults to `RagUnavailableException` rather than the ignored
`RagContractException`. That is a trade, not a taxonomy — some of those
statuses (501, 505) are permanent server-side refusals where retrying is
pure waste, and three wasted attempts against a status this service has
never produced is the cheaper mistake than a breaker that stays closed
through a real outage because an unrecognised failure silently didn't count.
The same reasoning applies on the transport side: `ConnectException` was the
originally measured real failure, but DNS failure, reset, and broken pipe are
also availability failures, so the default for *any* other
`ResourceAccessException` is `RagUnavailableException`, not silent exclusion.
Only after both the HTTP-status and transport axes are exhausted — meaning
the downstream was reached and responded — does the remainder default to
`RagContractException`; that residue is message-conversion territory
(`UnknownContentTypeException` and friends), which is genuinely contract-shaped
rather than a possible outage.

**Read timeout is not retried, deliberately.** A read timeout means the
request may still be executing downstream; retrying spawns a second
concurrent expensive call (embed → Chroma → rerank → LLM) against work that
might still complete. Connect timeout is classified non-retryable too, even
though it is semantically cheap to retry — distinguishing connect from read
timeout depends on the `ClientHttpRequestFactory` in use, and both surface as
`SocketTimeoutException` under the current `SimpleClientHttpRequestFactory`,
separable only by message text. Connection *refused* is the dominant real
failure (confirmed against the live stack) and arrives as an unambiguous
`ResourceAccessException` without a timeout cause under any factory, so it
stays retryable without depending on that unresolved distinction. Promoting
connect timeout to retryable later, if the factory choice is ever revisited,
is a one-line config change; the flat exception hierarchy already supports it
without restructuring.

### 503 — retryable, honestly

`ai-research-assistant` raises `503` for two distinct conditions:
`_startup_error` set (**permanent** — no re-attempt mechanism; needs a
process restart) and `not _ready` (**transient** — clears in roughly 30–60s).
Only the response's `detail` string distinguishes them, and the gateway does
**not** parse it.

All `503` is retried under the shared budget: 3 attempts, ~1s apart, so under
~3 seconds total. Startup takes 30–60s, so retrying a 503-loading will almost
never succeed inside that budget — the retry exists for transient failures in
an already-warm service, not to wait out a cold start. `503` stays retryable
anyway rather than being special-cased out of the `5xx` family, because
special-casing one status costs more implementation and test surface than it
buys: the retry budget is cheap to spend on a failure that mostly won't
succeed, and doing so keeps the classification table uniform. The startup
case is expected to fail fast — a few wasted seconds, then a clean
`RagUnavailableException` — and report state to the caller rather than block
on it. The same standard was applied to `MAX_RETRIES` removal in
`ai-research-assistant`: no implied-but-unused retry behaviour anywhere in
either service.

## Total retry budget across Java, Python, and the Anthropic SDK

**One owner: the gateway.** Up to 3 attempts, `RagUnavailableException`
only.

- **Java (this service):** `resilience4j.retry.instances.rag.max-attempts: 3`.
- **Python (`ai-research-assistant`):** the Anthropic SDK client is built
  with `max_retries=0` — no automatic retry inside the LLM call
  ([JUA-73](https://linear.app/juan-casimiro-agent/issue/JUA-73/bound-the-llm-client-explicit-timeout-no-sdk-retries-504-on-upstream)).
  Before that change, `init_chat_model` inherited the SDK default of 2
  automatic retries with a 600s timeout, which would have made a single
  Java-level retry attempt fan out into up to 3 LLM calls on its own — 9 LLM
  calls for one logical MCP request, worst case, before this was fixed.

**Why the gateway and not the SDK.** The gateway owns the user-facing
latency budget and can circuit-break; the Python service cannot see the
MCP-level request. The trade-off is real and is recorded rather than
designed around: an SDK-level retry would re-run only the failed LLM call,
while a gateway-level retry re-runs the entire pipeline (embed → Chroma
lookup → BM25 → rerank → LLM). The money cost of that trade is close to a
wash — the failures the SDK would have retried (429/529, and 5xx before
generation starts) consume no tokens — so what is actually being bought is a
single retry budget with a single owner, not a cheaper one.

**Downstream timeout is now distinguishable from a downstream failure.**
Before JUA-73, an LLM timeout inside `ai-research-assistant` surfaced as an
unhandled exception → generic `500`, which this service's table classifies
as retryable — spawning a second full pipeline for work that had already run
and had already been billed. JUA-73 catches `APITimeoutError` around the
`/query` LLM call and raises `HTTPException(504)`; the gateway maps `504` →
`RagTimeoutException`, non-retryable. A downstream timeout is now on the same
footing as the gateway's own read timeout: both mean *the expensive work may
already have happened; do not pay for it twice.*

### Accepted cross-repo gap: `max_tokens=1024` is unchanged

`ai-research-assistant` keeps `max_tokens=1024` rather than adding headroom.
If generation ever hits that cap mid-structured-output, `with_structured_output`
fails to parse **deterministically**, which this gateway would classify as a
`5xx`/transport-shaped failure and retry — burning the full budget on a
failure that can never succeed on any attempt. Accepted because observed
answers run roughly 130–200 tokens, about 20% of the budget, across the
[JUA-51](https://linear.app/juan-casimiro-agent/issue/JUA-51/end-to-end-verification-with-mcp-inspector-readme-quickstart)
Inspector sample — the only answer-text evidence available, since the eval
harness doesn't store answer content. Raising the cap is the lever if this is
ever observed in practice; a length constraint on the `/query` system prompt
was explicitly rejected as the fix, because it would invalidate the existing
`context_sufficient` accuracy measurement taken against the current prompt
wording.

## Timeouts

| Setting | Value | Why |
| --- | --- | --- |
| `rag.connection-timeout` | 4s | Local-network connect against a Compose service; generous relative to observed near-zero connect times. |
| `rag.read-timeout` | 60s | Measured headroom — see below. |

**Measured, not guessed.** 12 sequential warm `POST /query` calls against the
running RAG service (`n_results=20`, BM25 off, query rewriting off) returned
HTTP 200 with total latency 2.580–7.074s, median 5.868s, mean 5.189s,
nearest-rank observed p90 7.056s. This is a representative sample, not a
statistically reliable production p99.

The 60s read timeout is retained: roughly 8.5× the observed maximum, and it
comfortably contains the Python service's bounded 35s grounded-answer
timeout plus retrieval/reranking overhead, with room for the optional
sequential 10s rewrite + 35s grounded-answer path if query rewriting is ever
enabled.

**Record correction:** the measurement's own write-up described `n_results=20`
as "the current Java gateway request shape." That's wrong — the gateway
default is `DEFAULT_RESULT_COUNT = 8` (`QueryResearchCorpusTool`), and
`RagClientRequestExecutor.toRequest` pins `use_bm25`/`use_query_rewriting` to
`false` unconditionally regardless of caller input (see
[ADR-002](002-tool-surface-decisions.md)). The timeout conclusion is
unaffected — `n_results=20` retrieves and reranks more than the default 8, so
the measurement is the pessimistic case and still bounds real traffic — but
this document does not repeat the original claim.

**Coupling to note:** the read timeout and the breaker's slow-call threshold
(below) are both sized for the one workload the gateway can actually produce,
because BM25 and query rewriting are pinned off. If either is ever exposed as
a tool parameter, both values need revisiting against the slower workload
they'd unlock — not by adding a second breaker instance (Resilience4j has one
`slowCallDurationThreshold` per instance, and two instances would split
breaker state across what is a single downstream dependency).

## Circuit breaker

| Setting | Value | Why |
| --- | --- | --- |
| `sliding-window-type` | `COUNT_BASED` | Simpler to reason about than a time-based window for a low, bursty MCP traffic volume. |
| `sliding-window-size` | 10 | Small enough to react within a handful of MCP calls; large enough that one bad attempt can't misrepresent the window. |
| `minimum-number-of-calls` | 5 | The breaker won't evaluate a failure rate on fewer than 5 recorded attempts, avoiding an open decision off a tiny, unrepresentative sample. |
| `failure-rate-threshold` | 50% | Resilience4j opens the breaker when the failure rate is *at or above* the threshold, not only above it — 50% means half or more of the recorded attempts failing is the bar for "this dependency is unhealthy," not a single blip. |
| `slow-call-duration-threshold` | 20s | Measured — see below. Originally pinned to the 60s read timeout; that value was inert. |
| `slow-call-rate-threshold` | 50% | Same at-or-above bar as the failure rate, now that the duration threshold can actually fire. |
| `wait-duration-in-open-state` | 30s | Time before the breaker allows a probe; short enough to recover quickly, long enough not to hammer a service that just failed. |
| `permitted-number-of-calls-in-half-open-state` | 1 | One real `/query` probe. No synthetic health check — see half-open risk below. |
| `automatic-transition-from-open-to-half-open-enabled` | `false` | Lazy, caller-driven transition: the breaker only checks whether `wait-duration-in-open-state` has elapsed when the next real call arrives, rather than an internal scheduled thread flipping the state on a timer regardless of traffic. Neither option spends a downstream call by itself — moving to half-open only changes which outcome the *next* call is evaluated against; the trade is eager timer-driven state (and the background thread it needs) versus a lazy check with no extra thread, and lazy is enough for MCP call volume this low. |
| `record-exceptions` | `RagException` | Listed as the supertype rather than enumerating `RagUnavailableException`/`RagTimeoutException`/`RagContractException` individually, so a new type thrown from inside the protected method is recorded as a failure by default instead of silently being treated as a successful call. |
| `ignore-exceptions` | `RagContractException`, `RequestNotPermitted` | Neither indicates downstream trouble — see below. |

### Negative result: the slow-call threshold was inert at 60s

The original design pinned `slowCallDurationThreshold` to the read timeout
(60s) on the premise that normal `/query` duration was "~60s," so an untuned
threshold risked opening the breaker on successful calls. That premise was
wrong by roughly an order of magnitude: measured normal latency tops out
around 7s (above). A 60s threshold could only ever have fired *after* the 60s
read timeout already turned the call into a hard failure — a configured knob
with no reachable code path, the same shape as the `MAX_RETRIES` removal in
`ai-research-assistant`.

The corrected value is **20s** — roughly 2.8× the observed maximum, and far
enough below the 60s read timeout that the detector can actually fire. It
lands inside the band the Python service can actually produce: a grounded
answer is capped at 35s server-side, so calls in the 20–35s range are the
observable "degraded but not down" signal this setting exists to catch;
beyond 35s the service itself returns 504 and the call is recorded as a hard
failure instead. `slow-call-rate-threshold`, previously left at the default
because the duration threshold could never fire, is now a live setting and
had to be stated explicitly (50%, matching the failure-rate bar) rather than
left implicit.

### Why the breaker counts attempts, not logical requests

The aspect order on `RagClientRequestExecutor.query` is
`Retry(CircuitBreaker(RateLimiter(...)))` — the default Resilience4j
ordering, not tuned. Practical effect: retry wraps the breaker-protected
call, so **each real HTTP attempt to the RAG service contributes separately**
to circuit-breaker metrics. With `max-attempts: 3`, one logical MCP request
can therefore register up to three breaker calls/failures.

This is intentional, not an artifact to fix. The breaker protects the
downstream dependency; retried attempts consume real downstream capacity and
are additional evidence of its health, and counting them individually limits
retry amplification during an outage — the breaker opens faster precisely
when retries are compounding load on an already-struggling service.
Consequently, `minimum-number-of-calls`, the sliding-window size, and the
failure-rate threshold above must be read as *protected RAG attempts*, not
*user requests* — the two are not the same unit once retries are active.

### No `fallbackMethod`

With the default aspect order, a `fallbackMethod` that throws
`RagUnavailableException` would itself be retried by the outer `Retry`
aspect against an already-open breaker — the opposite of the intended
short-circuit. Instead, `CallNotPermittedException` is left to propagate out
of the annotated method and is translated at the `RagClient` boundary into
`RagCircuitOpenException` (message: *"The research service is temporarily
unavailable because the circuit breaker is open."*), preserving the
Resilience4j exception as cause for diagnostics. This is exception
**translation**, not a fallback: no synthetic `QueryResearchCorpusResponse`
is manufactured, and the MCP call still fails.

The accepted trade-off: `retryExceptions` contains only
`RagUnavailableException`, so `CallNotPermittedException` passes through the
`Retry` aspect untouched and fails instantly — correct behaviour — but it
means `RagClient` (the integration layer) imports a Resilience4j type at
runtime to perform the translation. That is a small abstraction leak, named
here deliberately rather than engineered around.

### Half-open probe risk

The single half-open probe runs one genuine `/query` call — no `/health`
probe, no synthetic LLM call, because a lightweight probe wouldn't exercise
the path that's actually failing. Residual risk, carried forward from
`ai-research-assistant`'s own ADR-003: that service's readiness check
deliberately excludes the LLM round-trip, so "ready" can still mean Anthropic
itself is unreachable. If Anthropic is degraded rather than the RAG service
being down, the half-open probe runs the full expensive path and can time
out, consuming the one permitted probe on a call that was never going to
succeed. Accepted rather than built around — a synthetic probe would need to
exercise the LLM call to be meaningful, which defeats the point of a cheap
probe.

### Why `RagContractException` is ignored, not recorded as failure

A `4xx` or malformed body means the request or response contract was
invalid — a bug in this codebase or a version drift with the upstream
contract, not evidence the RAG *service* is unhealthy. Recording it as a
breaker failure would open the breaker on a class of error that retrying, or
waiting out an open state, can never fix. `ignoreExceptions` marks these
calls `kind=ignored` in `resilience4j.circuitbreaker.calls` — not
`kind=successful`, which would misrepresent a contract failure as a working
call, and not counted toward the failure rate either.

`RequestNotPermitted` (rate-limiter rejection) is ignored for the same
reason: exhausting the local rate limit says nothing about downstream health.

## Bulkhead: no configured limit — negative result

**Hypothesis tested:** in `ai-research-assistant`'s `retrieve()`, Chroma and
BM25 lookups run off-thread via `asyncio.to_thread`, but the cross-encoder
rerank previously ran synchronously on the event loop — a plausible
serialisation point under concurrent load.

**Verified true structurally**, and fixed: reranking (call and lazy score
consumption) now also runs through `asyncio.to_thread()`, preserving ranking
and the `/query` contract. This is an event-loop correctness fix, not a
capacity increase — see the
[Python retrieval ADR](https://github.com/juan-casimiro/ai-research-assistant/blob/main/adr/001-chunking-and-retrieval.md).

**The performance measurement did not support a specific bulkhead number.**
Concurrent-load testing at 1, 2, 4, and 8 requests showed continued
throughput growth through concurrency 8 — median latency scaled from 2.63s
(c=1) to 4.39s (c=8), throughput from ~0.38 req/s to ~1.77 req/s — with no
saturation point observed in that range. That result does **not** justify a
performance-derived bulkhead ceiling of 2, 4, 8, or any other value: there is
no measured point at which adding concurrency stops helping. A supplementary
5× artificial rerank workload made the pre-fix blocking behaviour visible
under amplification, but its CPU/RSS figures describe that artificial load,
not normal service footprint, and were not used to justify any ceiling.

**Conclusion:** the gateway ships no `Bulkhead` instance and no configured
concurrency limit from this work. A bulkhead may still be justified later as
an explicit *policy* boundary — abuse protection, request-storm containment,
or cost containment against a paid LLM API — but its value would be a policy
choice, stated as such, not a measured saturation threshold. This is the
"negative result" this ADR is required to surface: the initial assumption
was that a specific number was measurable; the measurement instead showed
the system continuing to scale across the tested range, which is itself the
useful finding.

## Global request budget (rate limiting)

Distinct from the bulkhead question above, and delivered separately
([JUA-72](https://linear.app/juan-casimiro-agent/issue/JUA-72)): the `rag`
rate limiter applies to each downstream attempt at
`RagClientRequestExecutor`, including retries. It is shared process-wide —
neither per-client nor distributed — and is a configurable spend-backstop
policy, not a measured service-capacity or monetary-budget guarantee.

| Setting | Value | Why |
| --- | --- | --- |
| `limit-for-period` | 30 (`RAG_RATE_LIMIT_FOR_PERIOD`) | A policy ceiling on paid-LLM-call volume per window, not a measured limit. |
| `limit-refresh-period` | 30s (`RAG_RATE_LIMIT_REFRESH_PERIOD`) | Matches the period above; both are configurable per deployment. |
| `timeout-duration` | 0 | Exhausted requests fail immediately rather than queuing — a caller-visible rejection is preferable to holding an MCP request open waiting for a permit. |

Multiple gateway instances each hold their own independent budget. Aspect
order is `Retry → CircuitBreaker → RateLimiter`, so an open breaker consumes
no rate-limit permit. `RequestNotPermitted` is ignored by the breaker (a
rejection counts as neither success nor failure) and sits outside the
retry policy's `RagUnavailableException` allowlist; `RagClient` translates it
to `RagRateLimitException` alongside the breaker-open translation before
returning to callers.

## Failure classification — production activation gap (negative finding)

Integration testing ([JUA-59](https://linear.app/juan-casimiro-agent/issue/JUA-59/resilience-integration-tests-breaker-opens-fallback-fires))
exposed that the `@Retry`/`@CircuitBreaker`/`@RateLimiter` annotations on
`RagClientRequestExecutor.query` were **inert** until the required AOP/AspectJ
runtime support was actually present and active — annotation presence alone
does not prove the advice executes. The fix is the explicit
`spring-boot-starter-aspectj` dependency in `pom.xml`. This was a genuine
production-correctness gap, not test plumbing: without it, every value
documented in this ADR would have been configured and silently unenforced.
It is the concrete reason resilience behaviour must be proven by integration
tests stubbing the upstream service, rather than trusted on the strength of
annotations compiling.

## Fallback contract: what an MCP caller sees

**Technical failures are not represented as `QueryResearchCorpusResponse`.**
`contextSufficient=false` is reserved exclusively for the successful domain
outcome where the pipeline completed and the corpus itself lacked evidence.
Collapsing failure and "no evidence" into the same shape would let a calling
model mistake an outage for a knowledge finding, or surface technical error
text as answer content — the opposite of an actionable contract for an LLM
consumer.

At the `@McpTool` boundary (`QueryResearchCorpusTool.query`), every
classified failure is **rethrown** — never converted into a response object
— so Spring AI translates it into an MCP tool error result (`isError=true`;
this was verified during implementation rather than assumed) with the
exception's own message as the client-facing text. Explicit boundary logging
is not yet applied uniformly to every type; see the note below the table.

| Exception | Boundary log severity | Client-facing message |
| --- | --- | --- |
| `RagContractException` | ERROR | "The research service could not process this request. This is an internal error; do not retry with the same input." |
| `RagUnavailableException` | WARN | "The research corpus is currently unavailable. Do not answer from general knowledge; tell the user that retrieval failed." |
| `RagTimeoutException` | WARN | "The research request timed out. Do not answer from general knowledge; tell the user that retrieval did not complete." |
| `RagCircuitOpenException` | WARN | "The research service is temporarily unavailable because the circuit breaker is open." |
| `RagRateLimitException` | **not logged at this boundary** (open question) | "The research service request limit has been reached. Please try again later." |
| `InvalidResearchQuestionException` | WARN | States the specific bound violated (question length, result count). |

`RagContractException` is the one ERROR-level case: it means either the
gateway sent something invalid or the upstream returned something invalid —
a real bug either way, not expected operating behaviour like an unavailable
dependency or an open breaker.

**`RagRateLimitException` currently propagates without matching either
boundary catch clause.** `QueryResearchCorpusTool.query` catches
`RagContractException` (ERROR) and, separately,
`RagUnavailableException | RagTimeoutException | RagCircuitOpenException |
InvalidResearchQuestionException` (WARN); `RagRateLimitException` is in
neither list, so it still reaches the caller with its correct message —
Spring AI's generic exception translation doesn't depend on the boundary
catch — but no `LOGGER` call fires for it here. This is a known, tracked gap
rather than an oversight in this document:
[JUA-83](https://linear.app/juan-casimiro-agent/issue/JUA-83/define-and-test-logging-policy-for-ragratelimitexception)
exists specifically to settle and test the intended logging severity for
rate-limit rejections, deliberately separated from this ADR because it's an
observability/policy decision, not a retry/breaker/timeout parameter.

### Why the exception hierarchy is flat, not nested

`RagException` (abstract) has three siblings hanging directly off it —
`RagUnavailableException`, `RagTimeoutException`, `RagContractException` —
rather than `RagTimeoutException extends RagUnavailableException`. An
earlier draft nested them so a timeout would inherit breaker-recording
automatically. The cost of that shortcut: retry *non-participation* for
timeouts then depended entirely on an `ignoreExceptions: [RagTimeoutException]`
line and on Resilience4j's ignore-beats-retry precedence — delete that one
line and timeouts silently become retryable again, which is exactly the
double-billing failure this whole design exists to prevent. Flat inverts the
default: `retryExceptions: [RagUnavailableException]` doesn't match a timeout
by inheritance, so making timeouts retryable would take a deliberate
addition, not an accidental deletion. `recordExceptions: [RagException]`
keeps breaker recording correct across all current and future siblings
without relying on inheritance for that part.

`RagCircuitOpenException` and `RagRateLimitException` are later additions to
the same flat family, but they are structurally different from the other
three: they are translated in `RagClient.executeQuery`, which wraps the
resilience-annotated `RagClientRequestExecutor.query` rather than living
inside it. `record-exceptions`/`ignore-exceptions` on the breaker instance
therefore never evaluate against these two types at all — the breaker only
ever sees what escapes the annotated method itself
(`RagUnavailableException`, `RagTimeoutException`, `RagContractException`,
or a rejection like `CallNotPermittedException`/`RequestNotPermitted`
raised by the aspects before the method runs). `CallNotPermittedException`
and `RequestNotPermitted` are caught one layer up and turned into
`RagCircuitOpenException`/`RagRateLimitException` purely for the caller's
benefit; by the time that translation happens, the breaker/rate-limiter
bookkeeping is already final.

`InvalidResearchQuestionException` sits **outside** the `RagException` tree
entirely, in `application.research`. It is thrown from `ResearchQuestion`'s
compact constructor before `researchGateway.query(...)` is ever called, so a
locally-rejected request never reaches the breaker or the retry budget —
structurally, not by configuration. The accepted trade: local rejections
produce no metric, since they never appear in
`resilience4j.circuitbreaker.calls` and no separate counter was added. There
is no operational decision on a single-user demo that such a count would
drive, and the WARN log line answers "did this ever happen" well enough; a
`MeterRegistry` counter at the catch site is the cheap retrofit if that
changes.

## Explicitly declined

Recorded so a later change doesn't silently re-open a settled question:

- **Per-failure-type retry counts / multiple `Retry` instances** — the
  failure type isn't known before the call, so nothing could route to a
  second instance.
- **Custom retry deadline mechanism / `TimeLimiter`.** The 60s read timeout
  bounds each *individual* attempt, not the whole retried operation: three
  attempts each capable of taking up to 60s, plus two 1s inter-attempt
  waits, put the worst-case wall clock for one logical MCP call at roughly
  182s — a retryable `5xx` arriving just under the read timeout on every
  attempt is the case that reaches it. Accepted rather than bounded with a
  `TimeLimiter`: that worst case requires the same failure shape on all
  three attempts, which the failure-rate and slow-call-rate thresholds above
  are already tuned to open the breaker well before, and correctness (not
  spending a fourth attempt, not double-billing) mattered more here than
  capping the tail latency of an already-degrading dependency. Worth
  revisiting if this MCP tool call is ever fronted by a caller with its own
  tighter timeout than 182s.
- **Runtime Chroma or LLM health preflight, or a `/health` probe before
  `/query`** — would add a second network round trip to every call for a
  freshness guarantee the half-open probe already provides less expensively.
- **Parsing `ai-research-assistant`'s 503 `detail` string** to distinguish
  startup-permanent from loading-transient — see the 503 section above.
- **A second circuit-breaker instance** for a hypothetical rewrite-enabled
  workload — the gateway pins that workload off (ADR-002); if it's ever
  exposed, the fix is raising the one existing threshold, not splitting
  breaker state across what is a single downstream dependency.
- **Relaying FastAPI's 422 detail body** to the MCP caller — fragile to
  parse (a nested Pydantic error array) and buys nothing over the generic
  `RagContractException` message.
- **A performance-derived bulkhead value** — see Bulkhead above; no
  saturation point was measured in range.

## Consequences

- The total retry budget across every layer is enumerated and singly owned:
  the gateway retries `RagUnavailableException` up to 3 times; the Python
  service and the Anthropic SDK retry nothing.
- The circuit breaker's slow-call threshold reflects measured `/query`
  latency (20s) rather than an inert value (60s) that could never have
  fired before the read timeout did.
- No bulkhead ships from this work. Concurrency measurement showed
  continued scaling through 8 concurrent requests rather than establishing
  a ceiling — a negative result recorded here rather than a number invented
  to look decisive.
- MCP callers receive one of six typed, message-bearing exceptions on
  failure, never a fabricated "degraded" response indistinguishable from a
  legitimate `contextSufficient=false` result.
- The AOP/AspectJ activation gap is a standing reminder that resilience
  annotations must be proven live by integration tests, not trusted at
  compile time.
- Any future change to `use_bm25`/`use_query_rewriting` pinning, or any
  change that lets a caller select a slower workload, requires revisiting
  both the read timeout and the slow-call threshold together.

# ADR-003: Resilience policy and parameter rationale

## Scope

This entry records the settled JUA-58 concurrency conclusion. The broader
retry, circuit-breaker, timeout, and failure-contract rationale remains
pending under JUA-60; this document does not mark that issue complete.

## Downstream concurrency (JUA-58)

The Python RAG service's cross-encoder reranker was a blocking inference
call inside its asynchronous retrieval path. The verified implementation
offloads both the call and lazy score consumption through `asyncio.to_thread()`
while preserving ranking and the `/query` contract. This is an event-loop
correctness decision, not a measured capacity increase. See the Python
[retrieval ADR](https://github.com/juan-casimiro/ai-research-assistant/blob/main/adr/001-chunking-and-retrieval.md)
for the implementation rationale.

Normal-workload measurements continued scaling through concurrency 8 and
did not establish a saturation threshold. They therefore do not justify a
performance-derived bulkhead ceiling of 2, 4, 8, or another value. The
artificial five-pass rerank experiment was diagnostic only: its CPU and RSS
figures do not describe normal production resource use or establish a
capacity ceiling. Detailed measurement evidence remains in JUA-58 in Linear.

## Policy implication

JUA-58 adds no gateway bulkhead implementation or configured limit. A
bulkhead introduced separately would be an explicit policy boundary for
bounded in-flight work, abuse protection, request storms, and/or paid LLM
cost containment. Its configured value must be described as a policy choice,
not a measured saturation threshold, unless future evidence establishes one.

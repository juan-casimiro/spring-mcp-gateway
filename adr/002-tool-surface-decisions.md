# ADR-002: Tool surface decisions, including what was declined

## Context

[JUA-43](https://linear.app/juan-casimiro-agent/issue/JUA-43/epic-b-mcp-tool-surface-design)
frames the MCP tool surface as an API/product-design problem, not a
collection of `@McpTool` annotations: more tools is not automatically
better, and every exposed field or parameter is a promise to callers.
[JUA-50](https://linear.app/juan-casimiro-agent/issue/JUA-50/expose-query_research_corpus-as-an-mcp-tool-over-streamable-http)
shipped the one tool the gateway exposes today, `query_research_corpus`
(`QueryResearchCorpusTool`). This entry records four boundary decisions
that shaped that tool's surface — including what was deliberately left
out.

## Decisions

### 1. `POST /ingest` is not exposed

The RAG service's `/ingest` endpoint has no corresponding MCP tool, and
nothing in the codebase references it. The RAG service itself has no
authentication: the gateway never forwards its bearer token upstream
(README), and the RAG service's own compose file still publishes port
`8000` on all interfaces, so it is directly reachable and not isolated
behind the gateway (tracked separately in
[JUA-90](https://linear.app/juan-casimiro-agent/issue/JUA-90/bind-the-rag-demos-published-port-to-localhost)).
A read-only tool surface is the only defensible boundary while that is
true — a write endpoint reachable by any MCP client, sitting in front of
an unauthenticated service, would let a client mutate the corpus with no
accountability for who did it. This is independent of
[JUA-89](https://linear.app/juan-casimiro-agent/issue/JUA-89/add-authentication-to-the-mcp-gateway)'s
gateway-side bearer authentication, which protects the gateway's own
endpoints, not the RAG service's.

### 2. `insufficiency_reason` stays exposed for transparency, but is not a routing signal

`insufficiency_reason` is a free-text field the RAG service's own
`QueryResponse` and `GroundedAnswer` models mark explicitly as
*"debug/demo only — no consumer branches on this value"*
(`ai-research-assistant/main.py:219,241`). The gateway forwards it
unchanged through `RagQueryResponse` → `ResearchAnswer` →
`QueryResearchCorpusResponse`, and it reaches the MCP client as-is.

The gateway does not treat it as signal anywhere in its own logic. Where
the gateway needs a stable, low-cardinality outcome for observability, it
already uses the boolean `context_sufficient` — tagged directly onto the
RAG-call span (`rag.context_sufficient` in `RagClient`) and onto the
`mcp.tool.duration` timer (`QueryResearchCorpusTool`). `insufficiency_reason`
is not read by any gateway code path.

**Decision:** keep forwarding the field. The gateway's tool surface is
aimed at researchers evaluating retrieval quality as much as at the MCP
client itself, and for that audience the field adds transparency: when
`context_sufficient` is `false`, the one-sentence reason lets a person
see *why* the corpus fell short (wrong topic, missing detail, etc.)
without digging into RAG-service logs. That is a legitimate use — a human
reading the answer, not a client branching on it. The two must not be
conflated: do not build any gateway behaviour on the field's contents,
and do not treat its wording as stable — it is one LLM-generated
sentence, not a structured value, and its own producer disclaims it as
non-authoritative. Nothing in this gateway's contract promises an MCP
client anything beyond that one sentence. No code or contract change was
made for this decision; `insufficiency_reason` was already exposed and
already unused by gateway logic — this entry records that as the
deliberate position, not a change in behaviour.

### 3. Retrieval knobs are pinned, not exposed

`RagQueryRequest` accepts four fields upstream: `question`, `n_results`,
`use_bm25`, `use_query_rewriting`. The gateway exposes only `question`
and `n_results` (as the tool's `question`/`resultCount` parameters, the
latter bounded 1–20). `RagClientRequestExecutor.toRequest` pins
`use_bm25` and `use_query_rewriting` to `false` unconditionally.

This follows the RAG service's own evaluation
([`ai-research-assistant` ADR-001](https://github.com/juan-casimiro/ai-research-assistant/blob/main/adr/001-chunking-and-retrieval.md),
"Hybrid Search Evaluation"): against the golden QA set, vector-only
retrieval scored 101/103 at n=8; enabling BM25 alone scored 100/103, with
one attributable regression, and query rewriting alone changed zero
verdicts. The RAG service already defaults both flags off for this
reason. Exposing them as MCP tool parameters would hand a caller two
knobs with no demonstrated benefit and one demonstrated regression on the
only corpus they have been evaluated against — dead knobs, not a
meaningful choice.

### 4. `list_corpus_documents` is deferred

No tool, class, or reference for `list_corpus_documents` (or a corpus
manifest) exists in the gateway. In `ai-research-assistant`,
`corpus_manifest.json` records ingestion *intent* — the articles a caller
told `ingest_corpus.py` to load — while `/health` reports the *actual*
live chunk count via `check_existing_chunks()`. The two can diverge:
partial ingestion, a PDF that failed to parse, or re-ingestion drift all
leave the manifest describing a corpus state that no longer matches what
is queryable. A tool that reported the manifest as "the corpus" would be
confidently wrong whenever that drift occurs, with no way for a caller to
detect it.

**Decision:** defer `list_corpus_documents` until there is a source of
truth for the corpus's actual queryable contents (e.g. an endpoint
reporting ingested documents, not intended ones). Add it only if a real
agent use case needs to enumerate the corpus — consistent with JUA-43's
"additional tools only get added if a real agent use case justifies
adding the corresponding RAG endpoint."

## Consequences

- The tool surface stays at one tool, `query_research_corpus`, with two
  caller-controlled parameters (`question`, `resultCount`) and no write
  path.
- `insufficiency_reason` remains in the MCP response contract as a
  transparency aid for researchers reading answers, not as a signal for
  clients to act on; a future change to omit it, or to stop the RAG
  service from returning it at all, is a separate decision against a
  separate contract (this gateway's MCP response, or
  `ai-research-assistant`'s `/query` response) and is not
  made here.
- `use_bm25` and `use_query_rewriting` stay pinned off. If a future corpus
  or evaluation shows a benefit, revisiting this is a configuration change
  in `RagClientRequestExecutor`, not a redesign.
- No corpus-listing tool exists yet; adding one later needs an upstream
  RAG-service change first (a real ingested-document listing), which is
  out of this gateway's scope.

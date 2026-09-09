# JUA-82 — Test coverage and effectiveness audit

Baseline coverage was already **98.29% lines / 100% branches**, and default PIT
already killed **24/24 mutants**. All three metrics were unchanged after this work.
The main value is the behavioural gaps found and the verification-first checks
that challenged assertion strength on code the suite already executed.

The most instructive finding came from the first explicit-count challenge: an
over-specific mock returned null for the wrong argument, so the test failed by
**NPE rather than the intended assertion**. That evidence was rejected. The test
was corrected to supply a response independently of arguments and verify the
forwarded `ResearchQuestion`; the repeated challenge then failed at the intended
argument assertion (8 instead of 3). A failing test alone was not sufficient proof.

## Scope and baseline

Audited production code against all existing unit, Spring/WireMock, configuration,
tool, and opt-in live tests before changing assertions. Starting revision:
`2bbf52c` on `main`, clean and synchronized with `origin/main`. Baseline:
46 deterministic tests passed with Temurin 25.0.4 and Maven 3.9.16. The shell's
default Java 21 cannot compile this project; all reported successful runs use 25.

Settled amendments take precedence over obsolete implementation prompts:
timeouts are separate from retryable unavailability; failures are MCP errors;
breaker accounting counts HTTP attempts; rate limiting applies per attempt;
and JUA-58 deliberately introduced no gateway bulkhead.

## Behaviour-to-test audit

| Contract / completed work | Existing protection and weakness | Improvement |
| --- | --- | --- |
| JUA-49 request flags and JSON mapping | Exact request stub protects both flags; single HTTP source cannot establish ordering | Unsorted, non-palindromic sources with a duplicate; actual MCP calls also pin default and explicit counts |
| JUA-50 tool mapping | Default count and insufficient-context mapping covered; explicit non-default count absent | Explicit count 3, trimming, true context and null reason through unit and transport tests |
| JUA-78 local bounds | Constructor boundaries covered, but trim-before-length ordering and no-call boundary weak | Padded maximum-length question; verify no gateway interaction and unchanged HTTP/breaker/permit budget |
| JUA-78 response failures | Empty body and unsupported content type tested; malformed JSON absent | Malformed JSON classification at HTTP boundary, plus no retry/breaker recording |
| JUA-78 log and rethrow | Identity assertions prove rethrow, but pass if logging is deleted or wrong severity | ERROR contract log with throwable; WARN expected failures without throwable; detach test appenders after each test |
| JUA-56 timeout | Real configured read timeout exercised, but no attempt-count assertion | Assert exactly one upstream request |
| JUA-57/59 retries and startup | Persistent 503 exhaustion covered; successful recovery and both 503 details absent | Loading and startup-failed details use same bounded policy; 503 then insufficient-context success stops at two attempts and records both outcomes |
| JUA-57/59 breaker | Opens/rejects and configured half-open values tested; recovery absent | One real successful half-open query closes; one timeout reopens without retry |
| JUA-72 shared spend limit | Strong Spring/WireMock tests already prove permit exhaustion, retries consuming permits, rejection accounting, and open-breaker permit preservation | Keep these tests; deliberately remove limiter advice to challenge them |
| JUA-50/51/78 MCP protocol | Inspector evidence existed; Java tool unit tests cannot prove discovery or `isError` | Actual SDK client over Streamable HTTP: handshake, generated schema, success, insufficiency, safe 503/504/422 errors and local rejection |
| JUA-48/49 tracing foundation | Auto-configured builder used but regression unprotected | Active trace reaches WireMock with same trace ID, distinct client span and sampled flag; no OTLP collector |

Contract/resilience fixtures now explicitly reset HTTP state and use a high test-only
permit budget where rate limiting is not the subject. Rate-limit tests retain their
two-permit policy and isolated contexts. This avoids accidental exhaustion as tests
are added without disabling the actual advice being verified elsewhere.

The empty `contextLoads` smoke test overlaps other Spring contexts. It is low-value
for behaviour but remains a conventional startup check; deleting it does not improve
regression protection. Configuration tests remain useful beside behavioural tests:
the latter intentionally override delays/windows and cannot protect production defaults.

## Findings requiring interpretation

* Spring AI 2.0.1 returns duplicate safe-message lines on the tested technical and
  validation errors. Tests require `isError=true`, only approved message lines,
  and no successful structured response, while allowing the repetition count to
  change. No framework workaround is introduced to remove duplication.
* Boot's test-wide `management.tracing.export.enabled=false` also disables trace
  propagation. The tracing test enables global tracing while explicitly disabling
  OTLP tracing/metrics exporters. It checks the sampled bit, not equality of all
  W3C trace flags. Java-to-stub propagation is proven; Python ingestion and Jaeger
  export remain separate observability work.
* Semantic field completeness and consistency for syntactically valid JSON are
  neither explicitly validated by the gateway nor asserted by this suite. The
  exact behaviour for missing fields remains dependent on converter defaults.
  This audit adds malformed-JSON protection; it does not invent new required-field
  or domain-consistency rules or hide that remaining risk.
* `RagRateLimitException` propagates but has no explicit log branch in the tool.
  JUA-72 did not define its logging severity. Choosing a severity and adding that
  branch are follow-up policy work tracked in JUA-83; this PR promises no production behaviour changes.

## Deliberate limits

No paid/live RAG calls, models, Chroma, Python changes, time-based startup waits,
or new resilience policy. `RagClientIT` remains opt-in: its sufficient-context query
checks answer/sources but cannot guarantee a sufficient answer from a changing
corpus/model. Deterministic MCP success/insufficiency fixtures cover gateway semantics;
live grounding quality still needs its own controlled evaluation.

Half-open transitions are invoked explicitly to avoid a 30-second sleep; production
delay and one-probe configuration remain pinned separately. Slow-call timing under
load, concurrent half-open rejection, rate-period renewal, and real connect-timeout
timing are not claimed by these tests. The exception/cause walk and real read-timeout
tests protect classification without relying on unreachable network addresses.

ADR-003 preserves retry → circuit breaker → rate limiter, but does not require
explicit numeric aspect-order configuration. The existing interaction tests verify
that behaviour; no inconsistency requiring a production change was found. Explicit
ordering as dependency-upgrade hardening remains follow-up work, outside this PR.

No production refactor or mandatory numeric threshold was warranted by this audit.

## Deterministic results

| Signal | Before | After |
| --- | ---: | ---: |
| Deterministic tests | 46 passing | 62 passing |
| JaCoCo lines | 115/117 (98.29%) | 115/117 (98.29%) |
| JaCoCo branches | 28/28 (100%) | 28/28 (100%) |
| JaCoCo instructions | 411/416 (98.80%) | 411/416 (98.80%) |
| PIT default mutants, focused three classes | 24/24 killed | 24/24 killed |

PIT had no default survivors, uncovered mutants, or timeout/error outcomes. Its
100% score and the unchanged coverage percentages are expected: this work protects
additional **behaviours**, mostly on lines already executed by earlier tests.
The untouched entry-point `main` accounts for the two uncovered lines. Adding a
test solely to invoke that delegation would not improve this assessment.

The original audit’s additional `STRONGER,INLINE_CONSTS` pass on the same classes killed **48/49**
mutants (97.96%), with one survivor, no uncovered mutants and no timeout/error
outcomes. The survivor is `InlineConstantMutator` at executor line 42:
`Substituted 0 with 1`. Bytecode inspection shows `iconst_0; anewarray Object`
for `.uri("/query")`'s varargs array. Spring's URI expansion returns literal
components unchanged when they contain no `{` placeholder, so the extra unused
null slot does not change this fixed URI. Classified as **equivalent for this
implementation**, not a missing assertion. No test or production refactor was
added just to kill it. The raw score remains 48/49; it is not adjusted to 100%.
[`evidence/mutation-summary.json`](evidence/mutation-summary.json) preserves the
counts and exact survivor metadata. The latest local HTML/XML output under
`target/pit-reports` belongs to the cleanup rerun described below.

Hotspots (unchanged production code):

* `RagClientRequestExecutor.query`: PMD cyclomatic 13, cognitive 13. JaCoCo reports
  complexity 5 for the bytecode method and 3 for its separate HTTP-status lambda;
  both have 100% line coverage, so their CRAP-style scores are 5 and 3. The method
  combines HTTP classification, transport cause inspection and response conversion;
  stronger contract tests are justified, but splitting it just to reduce 13 is not.
* `ResearchQuestion` compact constructor: JaCoCo complexity/CRAP-style 6, 100% lines
  and branches. PMD 7.17's selected source rules omit the compact constructor.
* `hasCause`: PMD cyclomatic/cognitive 3/3; JaCoCo complexity/CRAP-style 3, 100% lines.
* `QueryResearchCorpusTool.query`: PMD cyclomatic/cognitive 6/3, JaCoCo
  complexity/CRAP-style 2, 100% lines. Logging was executed before but unasserted.

There is no high-complexity + low-line-coverage hotspot in this small codebase.
The stronger signals here are the behavioural audit and targeted production
challenges, not the coverage/complexity combination. No existing repository
static-analysis gate or CI workflow was found; compilation and deterministic tests
are the existing executable checks. `-Pquality clean verify` now also produces
JaCoCo and diagnostic PMD reports and packages the application successfully.

The committed `quality/evidence` CSV/JSON files are snapshot evidence for the
original audit changes in commit `8728fab` (baseline source `2bbf52c`), not a live
quality dashboard. The repository currently has no CI workflow running these
checks, so snapshots are not automatically refreshed. Later reruns must be recorded
explicitly and compared with these snapshots.

The summarizer was independently
checked with known full/half/zero coverage examples (complexity 4 gives CRAP-style
4/6/20), ordering checks, and a PMD analysis-error fixture that must be rejected.

## Cleanup revalidation (2026-09-08)

Both `./mvnw test` and `./mvnw -Pquality clean verify` pass all 62 tests.
Fresh coverage, complexity, and CRAP-style CSVs exactly match the committed after
snapshots. Default PIT again kills 24/24 mutants. The original nine deliberate challenges
remain historical audit evidence; they were not repeated for documentation cleanup.

## PIT discrepancy investigation (2026-09-08)

**Historical audit: 48/49 killed. Current clean result: 46/47 killed.** Both retain
the same equivalent URI-varargs survivor. The current result was obtained twice
with `./mvnw -Pmutation -Dmutators=STRONGER,INLINE_CONSTS clean test-compile pitest:mutationCoverage`.
Final default PIT, also starting with `clean`, kills 24/24. No production code,
tests, or committed PIT configuration changed during this investigation.

The current absence of the accessor mutants is **explained by PIT's default
record filter**. The origin of the historical bytecode is **unresolved**:

* Inspection of the installed PIT 1.30.0 `RecordFilter`/`RecordFilterFactory`
  bytecode shows that enabled-by-default `FRECORD` excludes methods on the
  earliest single-line method location in a record.
* Current `javap -c -l` output places generated Object methods and both
  `ResearchQuestion` accessors on record declaration line 5. Historical XML
  places the accessor mutants on component lines 6 and 7. Historical executor
  lambda name `lambda$1` also differs from current `lambda$query$0`; source-line
  and instruction indexes shift. These are differences in generated bytecode
  metadata, not evidence of a production-source change.
* A diagnostic run on the same clean-compiled classes with `-Dfeatures=-FRECORD`
  restores both accessor mutants, and existing tests kill both. Disabling the
  filter also exposes other generated record methods: its complete result is
  49 killed, 2 survived, 2 uncovered (53 total). This is an explanatory experiment,
  **not** the accepted configuration or a reproduction of historical 48/49.
* Standalone compilation of the unchanged record and exception with installed
  Temurin javac 25.0.1 and 25.0.4 (`-g -parameters`) produces line-5 accessors in
  both cases. Neither reproduces the historical line-6/7 metadata. The historical
  Maven log says compilation was skipped; historical class files and their exact
  compiler invocation were not retained. We cannot establish the original compiler
  or how those class files entered that incremental build.
* Clean Maven recompiles all 19 production and 12 test files. No PIT history
  configuration is present in the POM and no history/cache option was passed.
  The filter toggle changes generation without changing source. No cache was
  deleted or score-driven exclusion added. Repeated clean runs establish the
  current result on this toolchain, not reproducibility across arbitrary compilers.

Toolchain: OpenJDK/Temurin **25.0.4+7-LTS**, `javac 25.0.4`, Maven **3.9.16**
(build `2bdd9fddda4b155ebf8000e807eb73fd829a51d5`), Maven Compiler **3.15.0**
(`javac`, debug, parameters, release 25), PIT **1.30.0**, PIT JUnit 5 plugin
**1.2.3**, macOS 26.6.2 aarch64, UTF-8. Full version outputs, XML SHA-256 hashes,
current non-killed metadata, missing accessor identities and the explicit generated
lambda mapping are in [`evidence/mutation-rerun.json`](evidence/mutation-rerun.json).
The comparison treats line/index shifts as metadata and pairs the four status-lambda
mutants by descriptor and operation; it does not present renamed lambda methods as
new or missing behaviour. Raw XML remains local, outside the portable committed
summary. [`evidence/mutation-summary.json`](evidence/mutation-summary.json) retains
the historical result. Further attribution requires the historical class files or
compiler/build provenance; this bounded investigation does not establish it.

## Verification-first evidence

Each row ran green → deliberate production break → assertion failure → restore →
green. There were no accepted compilation/test errors. Every source hash before
and after restoration is identical; `git diff --exit-code -- src/main` also passes.
Exact selectors, edits, hashes, exit codes, and failure messages are preserved in
[`evidence/effectiveness.json`](evidence/effectiveness.json). The runner can reproduce them.

| Production challenge | Observed failure |
| --- | --- |
| Reverse upstream sources | HTTP contract assertion reports all four order differences |
| Admit result count 21 | Expected validation exception is not thrown |
| Ignore explicit result count and always send 8 | Verified gateway argument is 8 instead of 3 |
| Downgrade contract log to WARN | Expected ERROR, observed WARN |
| Remove retry annotation | Both startup variants make 1 request instead of 3 |
| Remove rate-limit annotation | Third call succeeds instead of throwing rejection |
| Stop classifying 504 as timeout | Actual MCP error contains unavailable wording instead of timeout wording |
| Force sufficient context true | MCP insufficient-context case reports true instead of false |
| Replace injected builder with `RestClient.builder()` | WireMock request has no traceparent header |

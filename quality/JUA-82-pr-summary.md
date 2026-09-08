# test: strengthen gateway contracts and verify test effectiveness

Baseline coverage was already **98.29% lines / 100% branches**, and default PIT
already killed **24/24 mutants**. These metrics were unchanged after the work.
The main value is the behavioural gaps found and verification-first testing:
explicit count forwarding, source order, malformed responses, logging severity,
validation isolation, retry/half-open recovery, MCP errors, and trace propagation.
Production behaviour is unchanged.

The key verification-first finding: the first explicit-count challenge failed by
NPE because an over-specific mock returned null for the wrong argument. That
evidence was rejected. The test was corrected, and the repeated challenge failed
at the intended argument assertion (8 instead of 3).

Adds opt-in JaCoCo/PMD/PIT analysis and a reproducible production-challenge runner.
The audit and portable evidence are in `quality/JUA-82-audit.md` and `quality/evidence`.
PIT targets the main validation/classification/mapping classes; the small `RagClient`
wrapper is exercised indirectly by resilience/rate-limit tests but is not mutated.
Committed CSV/JSON evidence is a snapshot of audit commit `8728fab`; no repository
CI workflow currently runs these checks or automatically refreshes the snapshots.

## Verification

- Java 25 cleanup rerun: `./mvnw test` and `./mvnw -Pquality clean verify` —
  both pass 62 tests (baseline 46); fresh quality CSVs exactly match the snapshots.
- Nine deliberate production challenges fail at the intended assertion, then pass
  after byte-for-byte restoration; production diff is empty.
- Coverage remains 98.29% lines and 100% branches; no metric-driven exclusions or gates.
- Largest PMD method hotspot: executor query, cyclomatic/cognitive 13/13, fully covered.
- PIT defaults: 24/24 killed before and after. Broader `STRONGER,INLINE_CONSTS`:
  latest clean-build rerun 46/47 killed, with the same equivalent unused URI-varargs
  survivor. Historical snapshot: 48/49; the latest run omits two previously killed
  record-accessor mutants. The generation difference is documented, not attributed
  to a test regression or hidden by refreshing the snapshot.
- CRAP-style summaries use a documented reproducible formula. `git diff --check` passes.

## Limits

No live/paid RAG calls, Python changes, collector export, or new resilience policy.
Incomplete-but-valid JSON semantic validation, rate-limit logging policy, timed
and concurrent resilience probes, and live grounding evaluation remain documented
risks. `RagRateLimitException` logging remains a follow-up policy decision because
JUA-72 did not define its severity. ADR-003's existing aspect-order behaviour is
preserved; explicit numeric ordering as hardening is also follow-up work. Spring AI's
duplicate safe error wording is tolerated without allowing other message content.
No thresholds, production refactor, or framework workaround was added.

Refs: JUA-82

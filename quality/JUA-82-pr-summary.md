# test: strengthen gateway contracts and verify test effectiveness

The suite executed almost every production line but left meaningful contracts
unasserted. This change protects explicit count forwarding, source order,
malformed responses, logging severity, local validation isolation, retry recovery,
half-open recovery, real MCP error delivery, and outgoing trace propagation.
Production behaviour is unchanged.

Adds opt-in JaCoCo/PMD/PIT analysis and a reproducible production-challenge runner.
The audit and portable evidence are in `quality/JUA-82-audit.md` and `quality/evidence`.

## Verification

- Java 25: `./mvnw -Pquality clean verify` — 62 tests pass (baseline 46).
- Nine deliberate production challenges fail at the intended assertion, then pass
  after byte-for-byte restoration; production diff is empty.
- Coverage remains 98.29% lines and 100% branches; no metric-driven exclusions or gates.
- Largest PMD method hotspot: executor query, cyclomatic/cognitive 13/13, fully covered.
- PIT defaults: 24/24 killed before and after. Broader `STRONGER,INLINE_CONSTS`:
  48/49 killed; one equivalent unused URI-varargs array-size mutant retained and explained.
- CRAP-style summaries use a documented reproducible formula. `git diff --check` passes.

## Limits

No live/paid RAG calls, Python changes, collector export, or new resilience policy.
Incomplete-but-valid JSON semantic validation, rate-limit logging policy, timed
and concurrent resilience probes, and live grounding evaluation remain documented
risks. Spring AI's duplicate safe error wording is tolerated without allowing other
message content. No thresholds, production refactor, or framework workaround was added.

Refs: JUA-82

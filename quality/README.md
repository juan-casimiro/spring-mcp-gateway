# Diagnostic quality checks

Use Java 25 and the Maven wrapper. These reports are opt-in measurements, not
numeric build gates. No live RAG service, model downloads, LLM calls, or telemetry
collector is needed. The default test suite remains `./mvnw test`.

Python 3 is required only for `quality/summarize.py`, `quality/compare_baseline.py`,
and `quality/render_summary.py`. All three use only the Python standard
library; Maven builds, tests, and JaCoCo/PMD/PIT runs do not require Python.

The GitHub Actions `build-and-test` check (JUA-67) runs the regular Maven
test lifecycle on every PR and push to `main` and can block merging. The
separate `quality-report` check (`.github/workflows/quality.yml`) runs this
quality profile plus the comparison/rendering below on the same triggers,
but is advisory only: it is not a required status check, so a quality
warning is visible without blocking a merge. The commands below generate
local reports under `target`; this repository does not commit quality
report snapshots.

## Coverage and complexity

```sh
./mvnw -Pquality clean verify
mkdir -p target/quality
cp target/site/jacoco/jacoco.xml target/quality/jacoco.xml
cp target/pmd.xml target/quality/pmd.xml
python3 quality/summarize.py
```

HTML reports: `target/site/jacoco/index.html` and `target/reports/pmd.html`.
Machine-readable summaries: `target/quality/{coverage,complexity,hotspots}.csv`.
The summarizer rejects PMD analysis errors.

`summarize.py` also writes `target/quality/metadata.json`: the commit passed
as its second argument (omit it for an ad-hoc local snapshot) plus the Java
version and JaCoCo/PMD plugin versions read directly from `pom.xml`, so this
never drifts from the actual toolchain:

```sh
python3 quality/summarize.py target/quality "$(git rev-parse HEAD)"
```

## Baseline comparison

`quality/compare_baseline.py` compares a head snapshot directory (as produced
above) against a base snapshot directory, both containing
`coverage.csv`/`hotspots.csv`/`complexity.csv`/`metadata.json`:

```sh
python3 quality/compare_baseline.py target/quality path/to/base-snapshot
```

Prints a JSON report on stdout with before/after/delta for coverage counters
and per-method complexity/CRAP-style risk, plus a `warnings` list. Omit the
base directory, or point it at one that doesn't exist, to report an
absent/expired baseline (`baseline_status: "missing"`). If the two
snapshots' `metadata.json` toolchain versions differ, the comparison reports
`baseline_status: "incompatible"` and names the mismatched field(s) instead
of computing a delta across incompatible measurements. In both non-`"ok"`
cases every comparison is the literal string `"unavailable"`, never a
numeric zero, and no warnings are emitted. A method present in the head
snapshot only is classified `"new"` and never triggers a warning on its own
complexity; only a method present in **both** snapshots (`"changed"`) can
warn, and only on a coverage decrease (`LINE`/`BRANCH`) or a CRAP-style/PMD
complexity increase. A malformed or incomplete snapshot directory raises and
exits non-zero rather than printing a report — this is what lets a workflow
distinguish a real quality warning from a broken quality tool.

This script does not talk to GitHub or CI.

`quality/render_summary.py <comparison.json>` renders that JSON as the
Markdown job summary posted on each PR (`$GITHUB_STEP_SUMMARY`): a
coverage table, a table of changed/new/removed methods (filtered to those
with an actual delta — a method whose numbers are unchanged between base
and head is omitted rather than listing every method in the codebase), the
equivalent PMD table, and the warnings list. It does not emit the
`::warning::` annotations itself; the workflow extracts `comparison["warnings"]`
directly (e.g. with `jq`) for that.

Run their tests with:

```sh
python3 -m unittest discover -s quality/tests
```

`quality/tests/fixtures/` holds one real (non-mocked) base/head snapshot
pair per required comparison case: a coverage decrease, a changed method's
complexity/CRAP increase, a new method, a missing baseline, and an
incompatible toolchain.

### How the workflow finds a base snapshot

The workflow measures the PR's own head commit (`github.event.pull_request.head.sha`),
not GitHub's synthetic `refs/pull/N/merge` commit — `actions/checkout` is
pinned explicitly to that SHA so the tree measured always matches the
commit recorded in `metadata.json` and shown in the job summary, and the
result reflects only the PR's own changes rather than also folding in
whatever has landed on `main` since the PR's base was set.

On every push to `main`, `quality.yml` publishes a compact `quality-baseline-<sha>`
artifact (the four snapshot files, not the raw XML/HTML) with 90-day
retention. On a PR, the workflow looks up the most recent successful `main`
run that measured the PR's exact base commit and downloads that artifact.
If none is found — no `main` run has measured that commit yet, or its
artifact has expired — the workflow checks out that exact base commit and
measures it fresh in the same job, rather than comparing against an
unrelated later baseline or silently skipping the comparison. A genuine
lookup/download failure (not merely "no baseline exists yet") fails the
job, which is what makes a broken quality tool distinguishable from a real
`missing`/`incompatible` result in the rendered report. Full per-PR reports
(`quality-reports-<sha>`, including the JSON comparison) are retained for 7
days.

Versions: JaCoCo 0.8.14, Maven PMD plugin 3.28.0 / PMD 7.17.0. The PMD ruleset
reports cyclomatic/cognitive complexity from 1 upward; these reporting levels
are deliberately **not** acceptance thresholds. Methods with zero cognitive
complexity are omitted by PMD. PMD's rules do not report this project's compact
record constructor; JaCoCo covers it in the bytecode report.

The per-bytecode-method CRAP-style diagnostic is
`C² × (1 − L)³ + C`, where `C` is JaCoCo covered + missed cyclomatic complexity
and `L` is covered / total executable lines (fraction, not percentage). It uses
line coverage, retains the JVM descriptor to distinguish overloads, includes
lambda methods, and applies no extra exclusions. JaCoCo's built-in filtering of
compiler-generated record code still applies. This is a reproducible risk signal,
not a claim of canonical source-level CRAP equivalence.

PMD source cyclomatic complexity counts constructs such as catches that JaCoCo
does not, and its cognitive score accounts for nesting. The CSV files keep these
definitions separate. Coverage and CRAP cannot establish assertion strength.

## Focused mutation analysis

```sh
./mvnw -Pmutation clean test-compile pitest:mutationCoverage
# Broader diagnostic pass on the same three classes:
./mvnw -Pmutation -Dmutators=STRONGER,INLINE_CONSTS clean test-compile pitest:mutationCoverage
```

PIT 1.30.0 with its JUnit platform plugin 1.2.3 mutates `ResearchQuestion`,
`RagClientRequestExecutor`, and `QueryResearchCorpusTool`, using the deterministic
`*Test` classes. `RagClientIT` is excluded by that selection. PIT's default
mutators and filters are retained. Reports are in `target/pit-reports`.
Run separately from the quality profile to keep JaCoCo instrumentation out of
PIT, and use `clean` to avoid reusing class files from a previous compiler run.

The selected classes contain the main validation (`ResearchQuestion`), HTTP/transport
classification (`RagClientRequestExecutor`), and MCP mapping (`QueryResearchCorpusTool`)
logic. `RagClient` is deliberately outside the current mutation scope: it is a small
delegating wrapper whose circuit-open and rate-limit exception translations are
already exercised indirectly by Spring/WireMock resilience and rate-limit tests.
That is behavioural coverage, not a claim that the wrapper has been mutation-tested;
broader mutation coverage can be evaluated separately.

PIT does not mutate YAML, remove annotations, reverse lists, or generally challenge
logging calls. A 100% score is not evidence that those contracts are protected.
Inspect every survivor and distinguish assertion failures from PIT timeouts or
tool failures. PIT 1.19.4 was tried and rejected because it cannot read Java 25
class files; the source/target Java level was not lowered to accommodate it.

Tool references: [JaCoCo counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html),
[PMD version](https://maven.apache.org/plugins/maven-pmd-plugin/examples/upgrading-PMD-at-runtime.html),
[PIT Maven options](https://pitest.org/quickstart/maven/),
[PIT release history](https://github.com/hcoles/pitest#releases).

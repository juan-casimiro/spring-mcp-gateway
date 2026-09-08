# Diagnostic quality checks

Use Java 25 and the Maven wrapper. These reports are opt-in measurements, not
numeric build gates. No live RAG service, model downloads, LLM calls, or telemetry
collector is needed. The default test suite remains `./mvnw test`.

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
Python uses only its standard library. The script rejects PMD analysis errors.

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
./mvnw -Pmutation test-compile pitest:mutationCoverage
# Broader diagnostic pass on the same three classes:
./mvnw -Pmutation -Dmutators=STRONGER,INLINE_CONSTS test-compile pitest:mutationCoverage
```

PIT 1.30.0 with its JUnit platform plugin 1.2.3 mutates `ResearchQuestion`,
`RagClientRequestExecutor`, and `QueryResearchCorpusTool`, using the deterministic
`*Test` classes. `RagClientIT` is excluded by that selection. PIT's default
mutators and filters are retained. Reports are in `target/pit-reports`.
Run separately from the quality profile to keep JaCoCo instrumentation out of PIT.

This bounded scope targets validation, error classification, and tool mapping.
It does not mutate YAML, remove annotations, reverse lists, or generally challenge
logging calls. A 100% score is not evidence that those contracts are protected.
Inspect every survivor and distinguish assertion failures from PIT timeouts or
tool failures. PIT 1.19.4 was tried and rejected because it cannot read Java 25
class files; the source/target Java level was not lowered to accommodate it.

## Reversible production challenges

After the suite passes, with no concurrent Maven process or source editor:

```sh
python3 quality/verify_effectiveness.py
# Or one named challenge:
python3 quality/verify_effectiveness.py source-order
```

The script runs the selected test green, changes exactly one production expression
or annotation, requires assertion failures (not compilation or test errors),
restores the original source bytes in `finally`, and runs the same test green again.
Each directory under `target/effectiveness` retains logs, a source backup, failure
messages, and before/restored SHA-256 hashes. Review those messages to ensure the
failure is for the intended reason. Do not interrupt with SIGKILL; if the process
is forcibly terminated, restore the exact file from that directory's backup before
continuing. Ordinary script failure restores the source automatically.

After all challenges, run `./mvnw -Pquality clean verify` and PIT again. Preserve
any evidence you need before `clean` removes `target`.

Tool references: [JaCoCo counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html),
[PMD version](https://maven.apache.org/plugins/maven-pmd-plugin/examples/upgrading-PMD-at-runtime.html),
[PIT Maven options](https://pitest.org/quickstart/maven/),
[PIT release history](https://github.com/hcoles/pitest#releases).

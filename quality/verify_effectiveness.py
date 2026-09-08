#!/usr/bin/env python3
"""Run one reversible production challenge at a time. Requires JAVA_HOME=JDK 25.

Do not run concurrently with editors or another Maven process. Source bytes are
restored in finally, even if a test/command fails. SIGKILL cannot be recovered by
finally: a byte-for-byte backup is also kept under target/effectiveness/<name>/.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent.parent
SOURCE = "src/main/java/com/juancasimiro/mcpgateway/"
CHALLENGES = {
    "source-order": (
        "integration/rag/RagClientRequestExecutor.java",
        "response.sources(),", "response.sources().reversed(),",
        "RagClientContractTest#sendsQueryAndDeserializesResponse"),
    "validation-upper-bound": (
        "application/research/ResearchQuestion.java",
        "resultCount > MAX_RESULT_COUNT", "resultCount > MAX_RESULT_COUNT + 1",
        "ResearchQuestionTest#rejectsResultCountAboveTwenty"),
    "explicit-count": (
        "mcp/QueryResearchCorpusTool.java",
        "resultCount != null ? resultCount : DEFAULT_RESULT_COUNT", "DEFAULT_RESULT_COUNT",
        "QueryResearchCorpusToolTest#forwardsExplicitResultCountAndTrimmedQuestion"),
    "contract-logging": (
        "mcp/QueryResearchCorpusTool.java",
        'LOGGER.error("Research corpus contract failure", exception);',
        'LOGGER.warn("Research corpus contract failure", exception);',
        "QueryResearchCorpusToolTest#rethrowsContractFailure"),
    "retry-advice": (
        "integration/rag/RagClientRequestExecutor.java",
        '@Retry(name = "rag")', '// retry deliberately removed for effectiveness check',
        "RagClientResilienceTest#retriesBothLoadingAndPermanentStartupFailuresWithinTheSameBudget"),
    "rate-limit-advice": (
        "integration/rag/RagClientRequestExecutor.java",
        '@RateLimiter(name = "rag")', '// limiter deliberately removed for effectiveness check',
        "RagClientRateLimitTest#permitsCallsWithinLimitThenRejectsWithoutRetryOrBreakerStatistics"),
    "timeout-classification": (
        "integration/rag/RagClientRequestExecutor.java",
        "statusCode.isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT)",
        "statusCode.isSameCodeAs(HttpStatus.REQUEST_TIMEOUT)",
        "McpTransportTest#deliversTechnicalFailureAsSafeMcpToolError"),
    "insufficient-context": (
        "integration/rag/RagClientRequestExecutor.java",
        "response.contextSufficient(),", "true,",
        "McpTransportTest#returnsResearchOutcomeAsSuccessfulMcpResult"),
    "tracing-builder": (
        "config/RagClientConfiguration.java",
        "return builder", "return RestClient.builder()",
        "RagClientTracingTest#propagatesCurrentTraceThroughTheConfiguredHttpClient"),
}


def run_test(selector, output):
    with output.open("w") as log:
        result = subprocess.run(["./mvnw", "-B", "-Dtest=" + selector, "test"],
                                cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=120)
    return result.returncode


def challenge(name):
    relative, original, broken, selector = CHALLENGES[name]
    path = ROOT / SOURCE / relative
    initial = path.read_bytes()
    source = initial.decode()
    if source.count(original) != 1:
        raise ValueError(f"Expected exactly one replacement site in {path}")
    output = ROOT / "target/effectiveness" / name
    output.mkdir(parents=True, exist_ok=True)
    (output / "source.backup").write_bytes(initial)
    record = {"name": name, "source": SOURCE + relative, "test": selector,
              "original": original, "broken": broken,
              "sha256_before": hashlib.sha256(initial).hexdigest()}
    record["green_before"] = run_test(selector, output / "green-before.log")
    if record["green_before"] != 0:
        raise RuntimeError(f"{name}: baseline failed; source untouched")
    try:
        path.write_text(source.replace(original, broken))
        record["broken_exit"] = run_test(selector, output / "broken.log")
        report = next((ROOT / "target/surefire-reports").glob("TEST-*." + selector.split("#")[0] + ".xml"))
        root = ET.parse(report).getroot()
        record["failures"] = [{"test": case.attrib["name"],
                               "message": failure.attrib.get("message", "")}
                              for case in root.findall("testcase") for failure in case.findall("failure")]
        record["errors"] = int(root.attrib["errors"])
    finally:
        path.write_bytes(initial)
        record["sha256_restored"] = hashlib.sha256(path.read_bytes()).hexdigest()
        record["green_restored"] = run_test(selector, output / "green-restored.log")
        (output / "result.json").write_text(json.dumps(record, indent=2) + "\n")
    if (record["broken_exit"] == 0 or not record["failures"] or record["errors"]
            or record["green_restored"] != 0):
        raise RuntimeError(f"{name}: inspect evidence; challenge did not complete as expected")
    print(json.dumps(record), flush=True)


if __name__ == "__main__":
    for name in sys.argv[1:] or CHALLENGES:
        challenge(name)

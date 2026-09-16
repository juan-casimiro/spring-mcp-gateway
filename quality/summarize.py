#!/usr/bin/env python3
"""Summarize JaCoCo/PMD reports deterministically; no acceptance thresholds.

Usage: python3 quality/summarize.py [report-directory] [commit-sha]
The directory must contain jacoco.xml and pmd.xml. Default: target/quality.
Also writes metadata.json (commit + toolchain versions read from pom.xml) so
quality/compare_baseline.py can tell whether two snapshots are comparable.
"""
import csv
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent.parent


def counters(element):
    return {c.attrib["type"]: (int(c.attrib["covered"]), int(c.attrib["missed"]))
            for c in element.findall("counter")}


def read_pom_toolchain(pom_path):
    """Read the toolchain versions that determine snapshot comparability.

    The maven-pmd-plugin version is recorded rather than the PMD engine
    version it resolves transitively (7.17.0 per quality/README.md); pom.xml
    does not declare that engine version directly.
    """
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(pom_path).getroot()
    java_version = pom.findtext("m:properties/m:java.version", namespaces=ns)
    quality_profile = pom.find(
        "m:profiles/m:profile[m:id='quality']", namespaces=ns)
    plugins = {plugin.findtext("m:artifactId", namespaces=ns): plugin.findtext("m:version", namespaces=ns)
               for plugin in quality_profile.findall(".//m:plugin", namespaces=ns)}
    return {
        "java_version": java_version,
        "jacoco_plugin_version": plugins["jacoco-maven-plugin"],
        "pmd_plugin_version": plugins["maven-pmd-plugin"],
    }


def write_metadata(directory, commit, pom_path=ROOT / "pom.xml"):
    metadata = {"commit": commit, **read_pom_toolchain(pom_path)}
    (directory / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")


def summarize(directory, commit=None, pom_path=ROOT / "pom.xml"):
    coverage = ET.parse(directory / "jacoco.xml").getroot()
    pmd = ET.parse(directory / "pmd.xml").getroot()
    if pmd.findall(".//{*}error") or pmd.findall(".//{*}configerror"):
        raise ValueError("PMD analysis contains errors; refusing an incomplete summary")
    with (directory / "coverage.csv").open("w", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(["counter", "covered", "missed", "percent"])
        for kind, (covered, missed) in sorted(counters(coverage).items()):
            writer.writerow([kind, covered, missed, f"{100 * covered / (covered + missed):.2f}"])

    rows = []
    for cls in coverage.findall(".//class"):
        for method in cls.findall("method"):
            counts = counters(method)
            covered, missed = counts.get("LINE", (0, 0))
            if not covered + missed:
                continue
            complexity = sum(counts["COMPLEXITY"])
            fraction = covered / (covered + missed)
            crap = complexity ** 2 * (1 - fraction) ** 3 + complexity
            rows.append([cls.attrib["name"].replace("/", "."), method.attrib["name"],
                         method.attrib["desc"], complexity, covered, missed,
                         f"{100 * fraction:.2f}", f"{crap:.3f}"])
    with (directory / "hotspots.csv").open("w", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(["class", "method", "descriptor", "jacoco_cyclomatic", "covered_lines",
                         "missed_lines", "line_percent", "crap_style"])
        writer.writerows(sorted(rows, key=lambda row: (-float(row[-1]), row[0], row[1], row[2])))

    # PMD includes exception branches and source-level nesting; JaCoCo does not.
    # Keep the two definitions separate instead of silently combining them.
    rows = []
    for violation in pmd.findall(".//{*}violation"):
        if "method" not in violation.attrib:
            continue
        match = re.search(r"complexity of (\d+)", violation.text or "")
        if not match:
            raise ValueError(f"Unrecognized PMD metric: {violation.text}")
        rows.append([violation.attrib["package"] + "." + violation.attrib["class"],
                     violation.attrib["method"], violation.attrib["beginline"],
                     violation.attrib["rule"], int(match[1])])
    with (directory / "complexity.csv").open("w", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(["class", "method", "line", "metric", "value"])
        writer.writerows(sorted(rows))

    write_metadata(directory, commit, pom_path)


if __name__ == "__main__":
    summarize(Path(sys.argv[1] if len(sys.argv) > 1 else "target/quality"),
              sys.argv[2] if len(sys.argv) > 2 else None)

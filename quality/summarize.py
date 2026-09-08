#!/usr/bin/env python3
"""Summarize JaCoCo/PMD reports deterministically; no acceptance thresholds.

Usage: python3 quality/summarize.py [report-directory]
The directory must contain jacoco.xml and pmd.xml. Default: target/quality.
"""
import csv
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def counters(element):
    return {c.attrib["type"]: (int(c.attrib["covered"]), int(c.attrib["missed"]))
            for c in element.findall("counter")}


def summarize(directory):
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


if __name__ == "__main__":
    summarize(Path(sys.argv[1] if len(sys.argv) > 1 else "target/quality"))

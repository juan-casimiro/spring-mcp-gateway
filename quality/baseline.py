"""Compare two quality/summarize.py snapshot directories deterministically.

A snapshot directory holds coverage.csv, hotspots.csv, complexity.csv, and
metadata.json, all produced by quality/summarize.py. See
quality/compare_baseline.py for the CLI entry point.

Missing or incomparable measurements are always labelled UNAVAILABLE, never a
numeric zero, so a report cannot be misread as "no coverage" or "no risk".
"""
import csv
import json


UNAVAILABLE = "unavailable"

# Coverage counters that warn on a decrease. Others (INSTRUCTION, METHOD,
# CLASS, COMPLEXITY) are still shown, but a decrease there is not itself
# treated as a regression signal for this project.
WARN_ON_DECREASE = {"LINE", "BRANCH"}


def read_snapshot(directory):
    """Read one summarize.py output directory. Raises if it is incomplete."""
    with (directory / "coverage.csv").open(newline="") as f:
        coverage = {row["counter"]: {"covered": int(row["covered"]), "missed": int(row["missed"]),
                                      "percent": float(row["percent"])}
                    for row in csv.DictReader(f)}
    with (directory / "hotspots.csv").open(newline="") as f:
        methods = {(row["class"], row["method"], row["descriptor"]):
                   {"jacoco_cyclomatic": int(row["jacoco_cyclomatic"]),
                    "covered_lines": int(row["covered_lines"]),
                    "missed_lines": int(row["missed_lines"]),
                    "line_percent": float(row["line_percent"]),
                    "crap_style": float(row["crap_style"])}
                   for row in csv.DictReader(f)}
    with (directory / "complexity.csv").open(newline="") as f:
        pmd = {(row["class"], row["method"], row["metric"]):
               {"line": int(row["line"]), "value": int(row["value"])}
               for row in csv.DictReader(f)}
    metadata = json.loads((directory / "metadata.json").read_text())
    return {"coverage": coverage, "methods": methods, "pmd": pmd, "metadata": metadata}


def _toolchain_mismatches(base_metadata, head_metadata):
    return [f"{field} differs: base={base_metadata.get(field)!r} head={head_metadata.get(field)!r}"
            for field in ("java_version", "jacoco_plugin_version", "pmd_plugin_version")
            if base_metadata.get(field) != head_metadata.get(field)]


def _unavailable_coverage(head_coverage):
    return {counter: {"base": UNAVAILABLE, "head": value["percent"], "delta": UNAVAILABLE, "warning": False}
            for counter, value in head_coverage.items()}


def _compare_coverage(base_coverage, head_coverage):
    result = {}
    for counter, head_value in head_coverage.items():
        if counter not in base_coverage:
            result[counter] = {"base": UNAVAILABLE, "head": head_value["percent"],
                                "delta": UNAVAILABLE, "warning": False}
            continue
        base_percent = base_coverage[counter]["percent"]
        delta = head_value["percent"] - base_percent
        result[counter] = {
            "base": base_percent, "head": head_value["percent"], "delta": delta,
            "warning": counter in WARN_ON_DECREASE and delta < 0,
        }
    return result


def _unavailable_methods(head_methods):
    entries = []
    for (cls, method, descriptor), head_value in head_methods.items():
        entries.append({"class": cls, "method": method, "descriptor": descriptor, "status": UNAVAILABLE,
                         "jacoco_cyclomatic": {"base": UNAVAILABLE, "head": head_value["jacoco_cyclomatic"],
                                                "delta": UNAVAILABLE},
                         "crap_style": {"base": UNAVAILABLE, "head": head_value["crap_style"],
                                         "delta": UNAVAILABLE, "warning": False}})
    return entries


def _compare_methods(base_methods, head_methods):
    entries = []
    for key in sorted(set(base_methods) | set(head_methods)):
        cls, method, descriptor = key
        in_base, in_head = key in base_methods, key in head_methods
        if in_base and in_head:
            base_value, head_value = base_methods[key], head_methods[key]
            crap_delta = head_value["crap_style"] - base_value["crap_style"]
            entries.append({
                "class": cls, "method": method, "descriptor": descriptor, "status": "changed",
                "jacoco_cyclomatic": {"base": base_value["jacoco_cyclomatic"], "head": head_value["jacoco_cyclomatic"],
                                       "delta": head_value["jacoco_cyclomatic"] - base_value["jacoco_cyclomatic"]},
                "crap_style": {"base": base_value["crap_style"], "head": head_value["crap_style"],
                               "delta": crap_delta, "warning": crap_delta > 0},
            })
        elif in_head:
            head_value = head_methods[key]
            entries.append({
                "class": cls, "method": method, "descriptor": descriptor, "status": "new",
                "jacoco_cyclomatic": {"base": UNAVAILABLE, "head": head_value["jacoco_cyclomatic"], "delta": UNAVAILABLE},
                "crap_style": {"base": UNAVAILABLE, "head": head_value["crap_style"], "delta": UNAVAILABLE,
                               "warning": False},
            })
        else:
            base_value = base_methods[key]
            entries.append({
                "class": cls, "method": method, "descriptor": descriptor, "status": "removed",
                "jacoco_cyclomatic": {"base": base_value["jacoco_cyclomatic"], "head": UNAVAILABLE, "delta": UNAVAILABLE},
                "crap_style": {"base": base_value["crap_style"], "head": UNAVAILABLE, "delta": UNAVAILABLE,
                               "warning": False},
            })
    return entries


def _unavailable_pmd(head_pmd):
    entries = []
    for (cls, method, metric), head_value in head_pmd.items():
        entries.append({"class": cls, "method": method, "metric": metric, "status": UNAVAILABLE,
                         "value": {"base": UNAVAILABLE, "head": head_value["value"], "delta": UNAVAILABLE,
                                    "warning": False}})
    return entries


def _compare_pmd(base_pmd, head_pmd):
    entries = []
    for key in sorted(set(base_pmd) | set(head_pmd)):
        cls, method, metric = key
        in_base, in_head = key in base_pmd, key in head_pmd
        if in_base and in_head:
            delta = head_pmd[key]["value"] - base_pmd[key]["value"]
            entries.append({"class": cls, "method": method, "metric": metric, "status": "changed",
                             "value": {"base": base_pmd[key]["value"], "head": head_pmd[key]["value"],
                                        "delta": delta, "warning": delta > 0}})
        elif in_head:
            entries.append({"class": cls, "method": method, "metric": metric, "status": "new",
                             "value": {"base": UNAVAILABLE, "head": head_pmd[key]["value"], "delta": UNAVAILABLE,
                                        "warning": False}})
        else:
            entries.append({"class": cls, "method": method, "metric": metric, "status": "removed",
                             "value": {"base": base_pmd[key]["value"], "head": UNAVAILABLE, "delta": UNAVAILABLE,
                                        "warning": False}})
    return entries


def _warnings(coverage, methods, pmd):
    warnings = []
    for counter, entry in sorted(coverage.items()):
        if entry["warning"]:
            warnings.append(f"{counter} coverage decreased from {entry['base']:.2f}% to {entry['head']:.2f}% "
                             f"({entry['delta']:+.2f}pp)")
    for entry in methods:
        if entry["crap_style"]["warning"]:
            warnings.append(f"{entry['class']}#{entry['method']}{entry['descriptor']}: crap_style increased from "
                             f"{entry['crap_style']['base']:.3f} to {entry['crap_style']['head']:.3f}")
    for entry in pmd:
        if entry["value"]["warning"]:
            warnings.append(f"{entry['class']}#{entry['method']} ({entry['metric']}): complexity increased from "
                             f"{entry['value']['base']} to {entry['value']['head']}")
    return warnings


def compare(base_snapshot, head_snapshot):
    """Compare two snapshots (as returned by read_snapshot).

    base_snapshot may be None to represent an absent/expired baseline.
    Returns a dict with a top-level "baseline_status" of "ok", "missing", or
    "incompatible"; when not "ok", every comparison is UNAVAILABLE rather
    than a misleading numeric delta.
    """
    head = head_snapshot
    if base_snapshot is None:
        return {
            "baseline_status": "missing",
            "base_commit": None, "head_commit": head["metadata"].get("commit"),
            "coverage": _unavailable_coverage(head["coverage"]),
            "methods": _unavailable_methods(head["methods"]),
            "pmd": _unavailable_pmd(head["pmd"]),
            "warnings": [],
        }

    mismatches = _toolchain_mismatches(base_snapshot["metadata"], head["metadata"])
    if mismatches:
        return {
            "baseline_status": "incompatible",
            "base_commit": base_snapshot["metadata"].get("commit"), "head_commit": head["metadata"].get("commit"),
            "toolchain_mismatches": mismatches,
            "coverage": _unavailable_coverage(head["coverage"]),
            "methods": _unavailable_methods(head["methods"]),
            "pmd": _unavailable_pmd(head["pmd"]),
            "warnings": [],
        }

    coverage = _compare_coverage(base_snapshot["coverage"], head["coverage"])
    methods = _compare_methods(base_snapshot["methods"], head["methods"])
    pmd = _compare_pmd(base_snapshot["pmd"], head["pmd"])
    return {
        "baseline_status": "ok",
        "base_commit": base_snapshot["metadata"].get("commit"), "head_commit": head["metadata"].get("commit"),
        "coverage": coverage, "methods": methods, "pmd": pmd,
        "warnings": _warnings(coverage, methods, pmd),
    }

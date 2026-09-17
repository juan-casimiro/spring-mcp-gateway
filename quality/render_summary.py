#!/usr/bin/env python3
"""Render a quality/compare_baseline.py comparison as a Markdown job summary.

Usage: python3 quality/render_summary.py <comparison.json>
Prints Markdown to stdout, suitable for appending to $GITHUB_STEP_SUMMARY.
Warning annotations are not rendered here; extract comparison["warnings"]
directly (e.g. with jq) to emit ::warning:: workflow commands.
"""
import json
from pathlib import Path
import sys


from baseline import UNAVAILABLE


def _fmt_percent(value):
    return UNAVAILABLE if value == UNAVAILABLE else f"{value:.2f}%"


def _fmt_delta_percent(value):
    return UNAVAILABLE if value == UNAVAILABLE else f"{value:+.2f}pp"


def _fmt_number(value):
    return UNAVAILABLE if value == UNAVAILABLE else str(value)


def _fmt_delta_number(value):
    return UNAVAILABLE if value == UNAVAILABLE else f"{value:+d}"


def _fmt_crap(value):
    return UNAVAILABLE if value == UNAVAILABLE else f"{value:.3f}"


def _fmt_delta_crap(value):
    return UNAVAILABLE if value == UNAVAILABLE else f"{value:+.3f}"


def _method_is_interesting(entry):
    if entry["status"] != "changed":
        return True  # new / removed / unavailable are always worth surfacing
    return entry["jacoco_cyclomatic"]["delta"] != 0 or entry["crap_style"]["delta"] != 0


def _pmd_is_interesting(entry):
    if entry["status"] != "changed":
        return True
    return entry["value"]["delta"] != 0


def _coverage_table(coverage):
    lines = ["| Counter | Base | Head | Delta |", "|---|---|---|---|"]
    for counter, entry in sorted(coverage.items()):
        marker = " :warning:" if entry["warning"] else ""
        lines.append(f"| {counter} | {_fmt_percent(entry['base'])} | {_fmt_percent(entry['head'])} | "
                     f"{_fmt_delta_percent(entry['delta'])}{marker} |")
    return lines


def _methods_table(methods):
    interesting = [entry for entry in methods if _method_is_interesting(entry)]
    if not interesting:
        return ["_No changed, new, or removed methods with a coverage/complexity delta._"]
    lines = ["| Class | Method | Status | CRAP before | CRAP after | Delta |", "|---|---|---|---|---|---|"]
    for entry in interesting:
        marker = " :warning:" if entry["crap_style"]["warning"] else ""
        lines.append(f"| {entry['class']} | `{entry['method']}{entry['descriptor']}` | {entry['status']} | "
                     f"{_fmt_crap(entry['crap_style']['base'])} | {_fmt_crap(entry['crap_style']['head'])} | "
                     f"{_fmt_delta_crap(entry['crap_style']['delta'])}{marker} |")
    return lines


def _pmd_table(pmd_entries):
    interesting = [entry for entry in pmd_entries if _pmd_is_interesting(entry)]
    if not interesting:
        return ["_No changed, new, or removed PMD complexity findings._"]
    lines = ["| Class | Method | Metric | Status | Before | After | Delta |", "|---|---|---|---|---|---|---|"]
    for entry in interesting:
        marker = " :warning:" if entry["value"]["warning"] else ""
        lines.append(f"| {entry['class']} | `{entry['method']}` | {entry['metric']} | {entry['status']} | "
                     f"{_fmt_number(entry['value']['base'])} | {_fmt_number(entry['value']['head'])} | "
                     f"{_fmt_delta_number(entry['value']['delta'])}{marker} |")
    return lines


def render(comparison):
    status = comparison["baseline_status"]
    base_commit = comparison["base_commit"] or "none"
    head_commit = comparison["head_commit"] or "unknown"
    lines = [
        "## Quality report (advisory — does not affect merge status)",
        "",
        f"Base: `{base_commit}` &middot; Head: `{head_commit}` &middot; Baseline status: **{status}**",
        "",
    ]

    if status == "incompatible":
        lines.append("Toolchain changed since the base commit was last measured, so no numeric "
                      "comparison is shown (every value below is `unavailable`):")
        lines.extend(f"- {mismatch}" for mismatch in comparison["toolchain_mismatches"])
        lines.append("")
    elif status == "missing":
        lines.append("No comparable baseline could be found or measured for the base commit; "
                      "showing head-only measurements as `unavailable` comparisons.")
        lines.append("")

    lines.append("### Coverage")
    lines.append("")
    lines.extend(_coverage_table(comparison["coverage"]))
    lines.append("")

    lines.append("### Method complexity / CRAP-style risk (JaCoCo)")
    lines.append("")
    lines.extend(_methods_table(comparison["methods"]))
    lines.append("")

    lines.append("### PMD complexity (source-level)")
    lines.append("")
    lines.extend(_pmd_table(comparison["pmd"]))
    lines.append("")

    lines.append("### Warnings")
    lines.append("")
    if comparison["warnings"]:
        lines.extend(f"- :warning: {warning}" for warning in comparison["warnings"])
    else:
        lines.append("None.")
    lines.append("")

    return "\n".join(lines)


if __name__ == "__main__":
    print(render(json.loads(Path(sys.argv[1]).read_text())))

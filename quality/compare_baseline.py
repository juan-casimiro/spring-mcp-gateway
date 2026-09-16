#!/usr/bin/env python3
"""Compare a head quality snapshot against a base snapshot (or its absence).

Usage: python3 quality/compare_baseline.py <head-dir> [base-dir]
Both directories are quality/summarize.py output directories (coverage.csv,
hotspots.csv, complexity.csv, metadata.json). Omit base-dir, or pass a path
that does not exist, to report an absent/expired baseline. Prints the
comparison as JSON on stdout; a non-zero exit means the comparison itself
failed (malformed input), which is distinct from a reported quality warning.
"""
import json
from pathlib import Path
import sys

from baseline import compare, read_snapshot


def main(argv):
    head_dir = Path(argv[0])
    base_dir = Path(argv[1]) if len(argv) > 1 else None
    head_snapshot = read_snapshot(head_dir)
    base_snapshot = read_snapshot(base_dir) if base_dir is not None and base_dir.is_dir() else None
    print(json.dumps(compare(base_snapshot, head_snapshot), indent=2))


if __name__ == "__main__":
    if not sys.argv[1:]:
        sys.exit(__doc__)
    main(sys.argv[1:])

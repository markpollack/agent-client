#!/usr/bin/env python3
"""Require one complete, successful ten-scenario provider parity report."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


EXPECTED_COUNTS = {
    "tests": 10,
    "skipped": 0,
    "failures": 0,
    "errors": 0,
}


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="Surefire TEST-*.xml report")
    return parser.parse_args()


def main() -> int:
    report = parse_args().report
    try:
        suite = ET.parse(report).getroot()
    except (OSError, ET.ParseError) as error:
        print(f"ERROR: cannot read Surefire report {report}: {error}", file=sys.stderr)
        return 1

    if local_name(suite.tag) != "testsuite":
        print(f"ERROR: expected a testsuite root in {report}, found {suite.tag!r}", file=sys.stderr)
        return 1

    try:
        actual = {name: int(suite.attrib[name]) for name in EXPECTED_COUNTS}
    except (KeyError, ValueError) as error:
        print(f"ERROR: invalid Surefire counts in {report}: {error}", file=sys.stderr)
        return 1

    testcase_count = sum(1 for child in suite if local_name(child.tag) == "testcase")
    if actual != EXPECTED_COUNTS or testcase_count != EXPECTED_COUNTS["tests"]:
        print(
            f"ERROR: provider parity report {report} has counts {actual} and "
            f"{testcase_count} testcase records; expected {EXPECTED_COUNTS}",
            file=sys.stderr,
        )
        return 1

    print(f"provider parity report passed: {report} ({actual})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

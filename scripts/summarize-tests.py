"""Summarize existing Gradle JUnit reports; never treat absent reports as a pass."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-results", action="store_true")
    arguments = parser.parse_args()
    files = set()
    for module in ("domain", "data", "feature", "legacy", "app", "benchmark"):
        for directory in ("test-results", "outputs/androidTest-results"):
            files.update((ROOT / module / "build" / directory).rglob("TEST-*.xml"))
    if not files:
        print("No Gradle JUnit XML reports found; no test pass is inferred.")
        return 1 if arguments.require_results else 0
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in sorted(files):
        try:
            root = ET.parse(path).getroot()
            suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
            for suite in suites:
                for key in totals:
                    totals[key] += int(suite.get(key, "0"))
        except (OSError, ET.ParseError, ValueError) as error:
            print(f"Invalid test report {path.relative_to(ROOT)}: {error}", file=sys.stderr)
            return 1
    print(f"{len(files)} report files: " + ", ".join(f"{key}={value}" for key, value in totals.items()))
    print("Counts summarize reports currently on disk; clean/stale-report management belongs to the invoking build.")
    return 1 if totals["failures"] or totals["errors"] or totals["tests"] == 0 else 0


if __name__ == "__main__":
    sys.exit(main())

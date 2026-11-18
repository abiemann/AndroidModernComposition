"""Summarize JVM method tests by default, or optional non-UI device reports."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-results", action="store_true")
    parser.add_argument("--scope", choices=("host", "device"), default="host")
    arguments = parser.parse_args()
    files = set()
    modules = ("domain", "data", "feature", "legacy", "app") if arguments.scope == "host" else ("data", "app")
    directory = "test-results" if arguments.scope == "host" else "outputs/androidTest-results"
    for module in modules:
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
    executed = totals["tests"] - totals["skipped"]
    if executed <= 0:
        print("No tests executed; skipped tests do not establish a pass.")
    return 1 if totals["failures"] or totals["errors"] or executed <= 0 else 0


if __name__ == "__main__":
    sys.exit(main())

"""Check local Markdown link targets without network requests or dependencies."""

from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {".git", ".gradle", ".tools", ".test-work", "build", "node_modules"}
LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
REQUIRED = ["README.md", "TODO.md", "DESIGN.md", "docs/REQUIREMENTS.md",
            "docs/STUDY_GUIDE.md", "docs/PRODUCTION.md", "docs/API.md"]


def main() -> int:
    failures = []
    checked = 0
    for name in REQUIRED:
        path = ROOT / name
        if not path.is_file() or not path.read_text(encoding="utf-8").strip():
            failures.append(f"Required documentation missing or empty: {name}")
    for source in ROOT.rglob("*.md"):
        if any(part in EXCLUDED for part in source.relative_to(ROOT).parts):
            continue
        for line_number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
            for match in LINK.finditer(line):
                target = match.group(1).strip().split(' "', 1)[0].strip("<>")
                parsed = urlsplit(target)
                if parsed.scheme or parsed.netloc or not parsed.path:
                    continue
                checked += 1
                resolved = (source.parent / unquote(parsed.path)).resolve()
                if not resolved.is_relative_to(ROOT) or not resolved.exists():
                    failures.append(f"{source.relative_to(ROOT)}:{line_number}: missing local target {target}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Documentation targets valid: {checked} local links. External URLs and heading anchors are not checked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

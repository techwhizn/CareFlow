#!/usr/bin/env python3
"""Verify first-party license copies and bundled frontend attribution."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("backend", "sdk/java", "sdk/python", "worker", "tools/local-models")
PACKAGES = ("react", "react-dom", "scheduler", "@phosphor-icons/react")


def main():
    for module in MODULES:
        for name in ("LICENSE", "NOTICE"):
            if (ROOT / module / name).read_bytes() != (ROOT / name).read_bytes():
                raise SystemExit(f"License copy differs: {module}/{name}")
    expected = (ROOT / "LICENSE").read_text() + "\n" + (ROOT / "NOTICE").read_text()
    for name in PACKAGES:
        expected += (
            f"\n\n--- {name} ---\n"
            + (ROOT / "web/node_modules" / name / "LICENSE").read_text()
        )
    if (ROOT / "web/public/THIRD-PARTY-NOTICES.txt").read_text() != expected:
        raise SystemExit("Frontend attribution differs from installed dependencies")
    print("First-party license copies and frontend attribution are consistent")


if __name__ == "__main__":
    main()

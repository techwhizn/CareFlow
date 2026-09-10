"""Explicitly contact configured models with synthetic text; never print credentials."""

import argparse
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "worker"))
from careflow.model_probe import check_models  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--env-file", type=Path, help="Load trusted local configuration as data"
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.env_file:
        for line in args.env_file.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                if key.startswith(("EMBEDDING_", "RERANK_", "GENERATION_")):
                    os.environ[key] = value
    result = json.dumps(check_models(), ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(result)
    print(result)
    return 0 if json.loads(result)["complete"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

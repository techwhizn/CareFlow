"""Compare matching evaluation reports or apply explicit human review ratings."""

import argparse
import json
from pathlib import Path

from careflow.evaluation import apply_reviews, compare


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("compare", "review"))
    parser.add_argument("first")
    parser.add_argument("second")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    first = json.loads(Path(args.first).read_text())
    second = json.loads(Path(args.second).read_text())
    result = (
        compare(first, second)
        if args.operation == "compare"
        else apply_reviews(first, second)
    )
    Path(args.output).write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    )


if __name__ == "__main__":
    main()

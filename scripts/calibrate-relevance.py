"""Select a relevance threshold from explicitly labelled, real rerank scores."""

import argparse
import json
from pathlib import Path

from careflow.relevance_calibration import calibrate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--minimum-precision", type=float, default=0.95)
    args = parser.parse_args()
    samples = json.loads(args.input.read_text(encoding="utf-8"))
    result = calibrate(samples, args.minimum_precision)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
    )
    if result["selected"] is None:
        parser.exit(
            2, "No threshold meets the precision target; configuration unchanged.\n"
        )
    print(json.dumps(result["selected"]))


if __name__ == "__main__":
    main()

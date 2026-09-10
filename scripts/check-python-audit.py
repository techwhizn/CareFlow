#!/usr/bin/env python3
"""Reject empty/skipped pip-audit reports; explicitly check PyTorch CPU's upstream version."""

import argparse
import json
from pathlib import Path
import re
import urllib.request


def torch_advisories(version):
    request = urllib.request.Request(
        "https://api.osv.dev/v1/querybatch",
        data=json.dumps(
            {
                "queries": [
                    {
                        "package": {"name": "torch", "ecosystem": "PyPI"},
                        "version": version,
                    }
                ]
            }
        ).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        data = json.loads(response.read(1024 * 1024))
    results = data["results"]
    if (
        len(results) != 1
        or results[0].get("error")
        or results[0].get("next_page_token")
    ):
        raise ValueError("Incomplete upstream PyTorch audit")
    return [v["id"] for v in results[0].get("vulns", [])]


def check(report, *, torch_cpu_version=None, lookup=torch_advisories):
    rows = report.get("dependencies")
    if not isinstance(rows, list) or not rows:
        raise ValueError("Audit did not cover any dependencies")
    supplemental = []
    for row in rows:
        if row.get("vulns"):
            raise ValueError("Known dependency vulnerabilities remain")
        if row.get("skip_reason"):
            if not (
                torch_cpu_version
                and row["name"] == "torch"
                and re.fullmatch(r"\d+\.\d+\.\d+\+cpu", torch_cpu_version)
            ):
                raise ValueError("Audit skipped a dependency")
            version = torch_cpu_version.split("+")[0]
            if lookup(version):
                raise ValueError("Known upstream PyTorch vulnerabilities remain")
            supplemental.append(
                {
                    "name": "torch",
                    "distribution_version": torch_cpu_version,
                    "upstream_version": version,
                    "source": "OSV PyPI advisory lookup",
                    "scope": "Upstream release; not CPU build provenance verification",
                }
            )
        elif "vulns" not in row:
            raise ValueError("Incomplete dependency audit row")
    return {"dependencies_checked": len(rows), "supplemental_checks": supplemental}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument(
        "--torch-cpu-requirements",
        type=Path,
        help="Resolved Linux requirements declaring the CPU build version",
    )
    args = parser.parse_args()
    cpu_version = None
    if args.torch_cpu_requirements:
        versions = re.findall(
            r"(?m)^torch==(\d+\.\d+\.\d+\+cpu)(?:\s|$)",
            args.torch_cpu_requirements.read_text(),
        )
        if len(versions) != 1:
            raise ValueError("Expected exactly one pinned PyTorch CPU build")
        cpu_version = versions[0]
    print(
        json.dumps(
            check(json.loads(args.report.read_text()), torch_cpu_version=cpu_version),
            indent=2,
        )
    )


if __name__ == "__main__":
    main()

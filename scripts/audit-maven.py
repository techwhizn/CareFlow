#!/usr/bin/env python3
"""Audit resolved public Maven coordinates with OSV; no source files or credentials are sent."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import urllib.request


ANSI = re.compile(r"\x1b\[[0-9;]*m")
COORDINATE = re.compile(
    r"^\s*([\w.-]+):([\w.-]+):jar:(?:[\w.-]+:)?([^:\s]+):(compile|runtime)\b"
)


def dependencies(text):
    result = {}
    for line in ANSI.sub("", text).splitlines():
        match = COORDINATE.match(line)
        if match:
            group, artifact, version, _scope = match.groups()
            result[(group + ":" + artifact, version)] = {
                "package": {"name": group + ":" + artifact, "ecosystem": "Maven"},
                "version": version,
            }
        elif ":jar:" in line:
            raise ValueError("Unrecognized Maven dependency line")
    if not result:
        raise ValueError("No resolved runtime dependencies found")
    return list(result.values())


def query_batch(queries):
    request = urllib.request.Request(
        "https://api.osv.dev/v1/querybatch",
        data=json.dumps({"queries": queries}).encode(),
        headers={
            "Content-Type": "application/json",
            "User-Agent": "CareFlow-dependency-audit/1",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024:
        raise ValueError("OSV response exceeds audit bound")
    result = json.loads(data)["results"]
    if len(result) != len(queries) or any(not isinstance(r, dict) for r in result):
        raise ValueError("Incomplete OSV batch response")
    return result


def audit(queries, query=query_batch):
    findings = {i: set() for i in range(len(queries))}
    pending = list(enumerate(queries))
    pages = 0
    while pending:
        pages += 1
        if pages > 100:
            raise ValueError("OSV pagination exceeded bound")
        next_page = []
        for (index, item), result in zip(
            pending, query([q for _, q in pending]), strict=True
        ):
            if result.get("error"):
                raise ValueError("OSV reported an incomplete package query")
            for vulnerability in result.get("vulns", []):
                findings[index].add(vulnerability["id"])
            if result.get("next_page_token"):
                next_page.append(
                    (index, {**item, "page_token": result["next_page_token"]})
                )
        pending = next_page
    return [
        {**item, "advisory_ids": sorted(findings[i])} for i, item in enumerate(queries)
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dependencies", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = audit(dependencies(args.dependencies.read_text()))
    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": "https://api.osv.dev/v1/querybatch",
        "dependencies": rows,
        "affected_packages": sum(bool(r["advisory_ids"]) for r in rows),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(
        f"Audited {len(rows)} packages; {report['affected_packages']} have known advisories"
    )
    raise SystemExit(1 if report["affected_packages"] else 0)


if __name__ == "__main__":
    main()

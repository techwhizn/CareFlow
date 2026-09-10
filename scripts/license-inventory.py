#!/usr/bin/env python3
"""Export declared Python distribution licenses without inferring missing legal terms."""

import importlib.metadata
import json
import platform
import sys


def inventory():
    rows = []
    for distribution in importlib.metadata.distributions():
        metadata = distribution.metadata
        declaration = metadata.get("License-Expression") or metadata.get("License")
        classifiers = [
            value
            for value in metadata.get_all("Classifier", [])
            if value.startswith("License ::")
        ]
        rows.append(
            {
                "name": metadata["Name"],
                "version": distribution.version,
                "license_expression": metadata.get("License-Expression"),
                "license_declaration": declaration,
                "license_classifiers": classifiers,
                "project_urls": metadata.get_all("Project-URL", []),
                "license_files": [
                    str(path)
                    for path in distribution.files or []
                    if any(
                        part.lower().startswith(("license", "copying", "notice"))
                        for part in path.parts
                    )
                ],
            }
        )
    return {
        "python": platform.python_version(),
        "platform": sys.platform,
        "distributions": sorted(rows, key=lambda row: row["name"].lower()),
        "missing_declarations": [
            row["name"]
            for row in rows
            if not row["license_declaration"] and not row["license_classifiers"]
        ],
    }


if __name__ == "__main__":
    print(json.dumps(inventory(), ensure_ascii=False, indent=2))

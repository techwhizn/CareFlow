"""Preserve explicit table labels and row identity; never infer missing units or values."""

import re


def describe(headers, values, name, location):
    issues = []
    if not headers or any(not str(label).strip() for label in headers):
        issues.append("TABLE_HEADER_MISSING")
    labels = [str(label).strip() for label in headers]
    if len(set(labels)) != len(labels):
        issues.append("TABLE_HEADER_DUPLICATE")
    if len(values) != len(headers):
        issues.append("TABLE_ROW_BROKEN")
    if not any(str(value).strip() for value in values if value is not None):
        issues.append("EMPTY_CONTENT")
    return {
        **location,
        "table_name": name,
        "headers": headers,
        # Parenthesised header annotations are retained verbatim, not guessed units.
        "header_annotations": [
            re.findall(r"[（(]([^()（）]+)[）)]", label) for label in labels
        ],
        "row_key": str(location.get("row", "")),
        "quality_codes": issues,
    }

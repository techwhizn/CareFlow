import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location(
    "maven_audit", Path(__file__).resolve().parents[2] / "scripts/audit-maven.py"
)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def test_maven_coordinates_preserve_classifier_versions_and_fail_on_empty_input():
    rows = audit.dependencies(
        "org.example:core:jar:1.2.3:compile\x1b[36m -- module x\norg.example:native:jar:linux:2.0:runtime"
    )
    assert [r["version"] for r in rows] == ["1.2.3", "2.0"]
    assert rows[0]["package"]["name"] == "org.example:core"
    with pytest.raises(ValueError):
        audit.dependencies("failed to resolve dependencies")
    with pytest.raises(ValueError):
        audit.dependencies("unexpected:jar:line")


def test_audit_follows_pagination_and_does_not_treat_partial_results_as_clean():
    calls = []
    queries = audit.dependencies("org.example:core:jar:1.2.3:compile")

    def query(rows):
        calls.append(rows)
        if len(calls) == 1:
            return [{"vulns": [{"id": "TEST-1"}], "next_page_token": "next"}]
        return [{"vulns": [{"id": "TEST-2"}]}]

    result = audit.audit(queries, query)
    assert result[0]["advisory_ids"] == ["TEST-1", "TEST-2"]
    assert calls[1][0]["page_token"] == "next"
    with pytest.raises(ValueError):
        audit.audit(queries, lambda _: [])
    with pytest.raises(ValueError):
        audit.audit(queries, lambda _: [{"error": "service unavailable"}])


def test_python_audit_rejects_empty_coverage_and_unapproved_skips():
    path = Path(__file__).resolve().parents[2] / "scripts/check-python-audit.py"
    module_spec = importlib.util.spec_from_file_location("python_audit", path)
    checker = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(checker)
    with pytest.raises(ValueError):
        checker.check({"dependencies": []})
    skipped = {
        "dependencies": [
            {"name": "torch", "version": "2.14.0+cpu", "skip_reason": "not on PyPI"}
        ]
    }
    with pytest.raises(ValueError):
        checker.check(skipped)
    result = checker.check(
        skipped,
        torch_cpu_version="2.14.0+cpu",
        lookup=lambda version: [] if version == "2.14.0" else ["BAD"],
    )
    assert result["supplemental_checks"][0]["upstream_version"] == "2.14.0"
    with pytest.raises(ValueError):
        checker.check(
            skipped, torch_cpu_version="2.14.0+cpu", lookup=lambda _: ["TEST-ADVISORY"]
        )

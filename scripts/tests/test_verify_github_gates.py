import importlib.util
from pathlib import Path

import pytest


MODULE_PATH = Path(__file__).parents[1] / "verify-github-gates.py"
SPEC = importlib.util.spec_from_file_location("verify_github_gates", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class FakeApi:
    def __init__(self, responses):
        self.responses = responses

    def get(self, path, params=None):
        key = (path, tuple(sorted((params or {}).items())))
        return self.responses[key]


def api_for(
    *, conclusion="success", missing_jobs=(), missing_checks=(), run_sha="head-sha"
):
    repo = "acme/careflow"
    branch = "main"
    branch_path = f"/repos/{repo}/git/ref/heads/{branch}"
    runs_path = f"/repos/{repo}/actions/runs"
    jobs_path = f"/repos/{repo}/actions/runs/42/jobs"
    protection_path = f"/repos/{repo}/branches/{branch}/protection"
    responses = {
        (branch_path, ()): {"object": {"sha": "head-sha"}},
        (
            runs_path,
            (("branch", branch), ("per_page", "20"), ("status", "completed")),
        ): {
            "workflow_runs": [
                {
                    "id": 42,
                    "name": "verify",
                    "head_sha": run_sha,
                    "conclusion": conclusion,
                }
            ]
        },
        (jobs_path, (("per_page", "100"),)): {
            "jobs": [
                {"name": name, "conclusion": "success"}
                for name in MODULE.REQUIRED_JOBS
                if name not in missing_jobs
            ]
        },
        (protection_path, ()): {
            "required_status_checks": {
                "contexts": [
                    name for name in MODULE.REQUIRED_JOBS if name not in missing_checks
                ]
            }
        },
    }
    return FakeApi(responses)


def test_verify_requires_current_successful_head():
    result = MODULE.verify(api_for(), "acme/careflow", "main")
    assert result["run_sha"] == "head-sha"
    assert result["successful_jobs"] == sorted(MODULE.REQUIRED_JOBS)


@pytest.mark.parametrize(
    "kwargs, message",
    [
        ({"conclusion": "failure"}, "conclusion is failure"),
        ({"run_sha": "old-sha"}, "does not match main SHA head-sha"),
        ({"missing_jobs": {"worker"}}, "Successful verify jobs missing: worker"),
        ({"missing_checks": {"web"}}, "Branch protection does not require: web"),
    ],
)
def test_verify_rejects_incomplete_gate(kwargs, message):
    with pytest.raises(RuntimeError, match=message):
        MODULE.verify(api_for(**kwargs), "acme/careflow", "main")

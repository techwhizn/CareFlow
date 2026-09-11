#!/usr/bin/env python3
"""Verify GitHub Actions and branch-protection gates for a repository.

The script is read-only. It requires a fine-grained token with repository
metadata and Actions/branch-protection read access; the token is read only
from GITHUB_TOKEN and is never printed or passed to a subprocess.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


REQUIRED_JOBS = {
    "secrets",
    "backend",
    "worker",
    "milvus-integration",
    "web",
}


class GithubApi:
    def __init__(self, token: str, api_root: str = "https://api.github.com"):
        self.api_root = api_root.rstrip("/")
        self.token = token

    def get(self, path: str, params: dict[str, str] | None = None):
        query = "?" + urllib.parse.urlencode(params) if params else ""
        request = urllib.request.Request(
            f"{self.api_root}{path}{query}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "careflow-gate-verifier",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read(8 * 1024 * 1024))
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"GitHub API {error.code} for {path}") from error
        except (urllib.error.URLError, TimeoutError) as error:
            raise RuntimeError(f"GitHub API unavailable for {path}") from error


def verify(api: GithubApi, repository: str, branch: str) -> dict:
    branch_ref = api.get(
        f"/repos/{repository}/git/ref/heads/{urllib.parse.quote(branch, safe='')}"
    )
    current_sha = branch_ref.get("object", {}).get("sha")
    if not current_sha:
        raise RuntimeError(f"Unable to resolve current {repository}:{branch} SHA")
    runs = api.get(
        f"/repos/{repository}/actions/runs",
        {"branch": branch, "status": "completed", "per_page": "20"},
    ).get("workflow_runs", [])
    if not runs:
        raise RuntimeError(f"No completed Actions run found for {repository}:{branch}")
    run = next((item for item in runs if item.get("name") == "verify"), runs[0])
    if run.get("head_sha") != current_sha:
        raise RuntimeError(
            f"Latest verify run SHA {run.get('head_sha')} does not match {branch} SHA {current_sha}"
        )
    if run.get("conclusion") != "success":
        raise RuntimeError(
            f"Latest verify run {run.get('id')} conclusion is {run.get('conclusion')}"
        )
    jobs = api.get(
        f"/repos/{repository}/actions/runs/{run['id']}/jobs", {"per_page": "100"}
    )
    job_rows = jobs.get("jobs", [])
    successful_jobs = {
        row.get("name") for row in job_rows if row.get("conclusion") == "success"
    }
    missing_jobs = sorted(REQUIRED_JOBS - successful_jobs)
    if missing_jobs:
        raise RuntimeError(f"Successful verify jobs missing: {', '.join(missing_jobs)}")

    protection = api.get(
        f"/repos/{repository}/branches/{urllib.parse.quote(branch, safe='')}/protection"
    )
    required = protection.get("required_status_checks") or {}
    contexts = set(required.get("contexts") or [])
    checks = {item.get("context") for item in required.get("checks") or []}
    required_names = contexts | checks
    missing_protection = sorted(REQUIRED_JOBS - required_names)
    if missing_protection:
        raise RuntimeError(
            "Branch protection does not require: " + ", ".join(missing_protection)
        )
    return {
        "repository": repository,
        "branch": branch,
        "run_id": run["id"],
        "run_sha": current_sha,
        "run_url": run.get("html_url"),
        "successful_jobs": sorted(successful_jobs),
        "required_status_checks": sorted(required_names),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--branch", default="main")
    args = parser.parse_args()
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        parser.error("GITHUB_TOKEN is required; no token was written to the repository")
    if not args.repository or "/" not in args.repository:
        parser.error("--repository OWNER/REPOSITORY is required")
    try:
        print(
            json.dumps(verify(GithubApi(token), args.repository, args.branch), indent=2)
        )
    except RuntimeError as error:
        print(f"github gate verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

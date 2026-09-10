import httpx
import pytest

from careflow import consumer


@pytest.mark.parametrize(
    "status,code", [(403, "ENTITLEMENT_INACTIVE"), (429, "QUOTA_EXCEEDED")]
)
def test_admission_wait_acknowledges_without_fetching_or_processing(
    monkeypatch, status, code
):
    calls = []

    def handle(request):
        calls.append(request.url.path)
        return httpx.Response(status, json={"code": code})

    client = httpx.Client(transport=httpx.MockTransport(handle))
    monkeypatch.setenv("INTERNAL_TOKEN", "synthetic-internal-token")
    monkeypatch.setattr(consumer.httpx, "Client", lambda **kwargs: client)
    consumer.run_job("synthetic-job")
    assert calls == ["/internal/v1/jobs/synthetic-job/claim"]


def test_unrecognized_authentication_failure_is_not_acknowledged(monkeypatch):
    client = httpx.Client(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(403, json={"code": "INVALID_SERVICE_TOKEN"})
        )
    )
    monkeypatch.setenv("INTERNAL_TOKEN", "synthetic-internal-token")
    monkeypatch.setattr(consumer.httpx, "Client", lambda **kwargs: client)
    with pytest.raises(httpx.HTTPStatusError):
        consumer.run_job("synthetic-job")

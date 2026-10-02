"""v1.1 (Phase 7): request IDs, JSON logs, /metrics and the LLM circuit breaker."""

import json
import logging

import pytest
from fastapi.testclient import TestClient

from app.ai import (
    CircuitBreaker,
    CircuitOpenError,
    ModerationService,
    RuleModerator,
    SummaryService,
    TemplateSummarizer,
)
from app.main import create_app
from app.metrics import COUNTERS, Metrics
from app.observability import JsonFormatter, incoming_or_new_id
from tests.conftest import test_settings


def metric(client, name: str) -> int:
    body = client.get("/metrics").text
    return int(next(line.split()[1] for line in body.splitlines() if line.startswith(name + " ")))


# ---- request IDs and logs ----------------------------------------------------------------------
def test_a_safe_request_id_is_echoed_and_a_missing_one_is_minted(client):
    assert client.get("/health", headers={"X-Request-ID": "abc-123"}).headers["x-request-id"] == "abc-123"
    minted = client.get("/health").headers["x-request-id"]
    assert len(minted) == 36 and minted.count("-") == 4


@pytest.mark.parametrize("value", ["", "has space", "x" * 65, "evil\u00e9", "a;b"])
def test_unsafe_request_ids_are_replaced(value):
    assert incoming_or_new_id(value) != value


def test_log_lines_are_json_with_the_request_fields():
    record = logging.LogRecord("devblog.access", logging.INFO, __file__, 1, "request", None, None)
    record.fields = {"requestId": "r-1", "method": "GET", "path": "/health", "status": 200, "durationMs": 1.5}
    entry = json.loads(JsonFormatter().format(record))
    assert entry["msg"] == "request" and entry["level"] == "INFO" and entry["requestId"] == "r-1"
    assert entry["ts"].endswith("Z") and entry["status"] == 200


def test_the_access_log_carries_the_request_id(client, caplog):
    with caplog.at_level(logging.INFO, logger="devblog.access"):
        client.get("/api/v1/posts", headers={"X-Request-ID": "trace-me"})
    fields = [r.fields for r in caplog.records if r.name == "devblog.access"]
    assert fields[-1]["requestId"] == "trace-me" and fields[-1]["path"] == "/api/v1/posts"
    assert fields[-1]["status"] == 200 and fields[-1]["durationMs"] >= 0


# ---- /metrics -------------------------------------------------------------------------------------
def test_metrics_endpoint_lists_every_counter(client):
    r = client.get("/metrics")
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/plain")
    for name in COUNTERS:
        assert f"# TYPE {name} counter" in r.text and f"\n{name} 0" in r.text


def test_business_counters(api, client):
    author = api.user("AUTHOR")
    reader = api.user()
    client.post("/api/v1/auth/login", json={"email": "user1@devblog.test", "password": "wrong-password"})
    published = api.post(author)
    draft = api.post(author, status="draft", title="Draft")
    url = f"/api/v1/posts/{draft['slug']}"
    client.patch(url, json={"version": 1, "status": "published"}, headers=api.h(author))
    client.patch(url, json={"version": 2, "status": "draft"}, headers=api.h(author))
    client.patch(url, json={"version": 3, "status": "published"}, headers=api.h(author))  # re-publish: not new
    comments = f"/api/v1/posts/{published['slug']}/comments"
    client.post(comments, json={"body": "Nice"}, headers=api.h(reader))
    client.post(comments, json={"body": "click here"}, headers=api.h(reader))
    assert metric(client, "devblog_users_registered_total") == 2
    assert metric(client, "devblog_logins_failed_total") == 1
    assert metric(client, "devblog_posts_published_total") == 2
    assert metric(client, "devblog_comments_created_total") == 2
    assert metric(client, "devblog_comments_held_total") == 1
    assert metric(client, "devblog_ai_fallback_total") == 0


class Broken:
    def classify(self, text):
        raise TimeoutError("LLM timed out")

    def summarize(self, title, body):
        raise TimeoutError("LLM timed out")


def test_fallbacks_are_counted(store, clock):
    app = create_app(
        test_settings(),
        users=store,
        posts=store,
        comments=store,
        clock=clock,
        ai=(ModerationService(RuleModerator(), Broken()), SummaryService(TemplateSummarizer(), Broken())),
    )
    with TestClient(app) as client:
        from tests.conftest import Api

        api = Api(client, store)
        post = api.post(api.user("AUTHOR"))
        client.post(f"/api/v1/posts/{post['slug']}/comments", json={"body": "Nice"}, headers=api.h(api.user()))
        assert client.get(f"/api/v1/posts/{post['slug']}/summary").json()["fallback"] is True
        assert metric(client, "devblog_ai_fallback_total") == 2


def test_metrics_render_format():
    m = Metrics()
    m.inc("devblog_comments_held_total", 3)
    assert m.value("devblog_comments_held_total") == 3
    assert "devblog_comments_held_total 3\n" in m.render() and m.render().endswith("\n")


# ---- circuit breaker ------------------------------------------------------------------------------
class FakeLlm:
    def __init__(self):
        self.calls = 0
        self.fail = True

    def chat(self, system, user, max_tokens):
        self.calls += 1
        if self.fail:
            raise TimeoutError("timed out")
        return "ok"


class Seconds:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def make_breaker():
    llm, now, skipped = FakeLlm(), Seconds(), []
    return (
        CircuitBreaker(llm, threshold=3, cooldown_s=30, now=now, on_short_circuit=lambda: skipped.append(1)),
        llm,
        now,
        skipped,
    )


def call(breaker):
    return breaker.chat("s", "u", 10)


def test_breaker_opens_after_three_failures_in_a_row_and_skips_calls():
    breaker, llm, _now, skipped = make_breaker()
    for _ in range(3):
        with pytest.raises(TimeoutError):
            call(breaker)
    assert breaker.state == "open"
    with pytest.raises(CircuitOpenError):
        call(breaker)
    assert llm.calls == 3 and skipped == [1]


def test_a_success_resets_the_failure_count():
    breaker, llm, _now, _ = make_breaker()
    for fail in (True, True, False, True, True):
        llm.fail = fail
        with pytest.raises(TimeoutError) if fail else _nothing():
            call(breaker)
    assert breaker.state == "closed"


def test_half_open_trial_success_closes_and_failure_reopens():
    breaker, llm, now, _ = make_breaker()
    for _ in range(3):
        with pytest.raises(TimeoutError):
            call(breaker)
    now.t += 30
    assert breaker.state == "half-open"
    with pytest.raises(TimeoutError):
        call(breaker)  # the trial fails: open again for another 30 s
    assert breaker.state == "open" and llm.calls == 4
    now.t += 30
    llm.fail = False
    assert call(breaker) == "ok" and breaker.state == "closed"


def test_the_real_wiring_short_circuits_an_unreachable_llm(store, clock):
    metrics = Metrics()
    settings = test_settings(ai_provider="llm", llm_base_url="http://127.0.0.1:9/v1", llm_timeout_ms=500)
    app = create_app(settings, users=store, posts=store, comments=store, clock=clock, metrics=metrics)
    with TestClient(app) as client:
        from tests.conftest import Api

        api = Api(client, store)
        post = api.post(api.user("AUTHOR"))
        reader = api.user()
        for i in range(4):
            r = client.post(f"/api/v1/posts/{post['slug']}/comments", json={"body": f"c{i}"}, headers=api.h(reader))
            assert r.status_code == 201 and r.json()["moderation"]["fallback"] is True
        assert metrics.value("devblog_llm_short_circuits_total") == 1
        assert metrics.value("devblog_ai_fallback_total") == 4


class _nothing:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

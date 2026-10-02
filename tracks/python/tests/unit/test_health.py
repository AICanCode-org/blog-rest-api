"""Works from day one: the starter already serves /health (no business logic needed)."""

from fastapi.testclient import TestClient

from app.main import create_app
from app.repository import InMemoryStore
from tests.conftest import test_settings


def test_health_reports_the_database():
    store = InMemoryStore()
    with TestClient(create_app(test_settings(), users=store, posts=store, comments=store)) as client:
        assert client.get("/health").json() == {"status": "UP", "checks": {"database": "UP"}}
        store.healthy = False
        r = client.get("/health")
    assert r.status_code == 503
    assert r.json() == {"status": "DOWN", "checks": {"database": "DOWN"}}

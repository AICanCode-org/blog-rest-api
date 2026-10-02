from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.ai import ModerationService, RuleModerator, SummaryService, TemplateSummarizer
from app.config import Settings
from app.main import create_app
from app.repository import InMemoryStore

# ---------------------------------------------------------------------------
# PENDING tests describe behaviour you have not built yet, so they are skipped
# (reported as "s" by pytest). When you start a milestone, delete its line here,
# watch the tests fail (red), implement until they pass (green). See docs/phase-3.
# ---------------------------------------------------------------------------
PENDING: dict[str, str] = {
    "test_validation.py": "Phase 3 M1 - validation",
    "test_text.py": "Phase 3 M1 - slugs, excerpts, cursors",
    "test_security.py": "Phase 3 M2 - passwords and tokens",
    "test_auth.py": "Phase 3 M2 - AuthService",
    "test_api_auth.py": "Phase 3 M2 - auth endpoints",
    "test_posts.py": "Phase 3 M3 - PostService",
    "test_api_posts.py": "Phase 3 M3 - post endpoints",
    "test_feed.py": "Phase 3 M4 - feed, search, tags",
    "test_moderation.py": "Phase 3 M5 - AI moderation",
    "test_summaries.py": "Phase 3 M5 - AI summaries",
    "test_comments.py": "Phase 3 M5 - CommentService",
    "test_api_comments.py": "Phase 3 M5 - comment endpoints",
    "test_api_integration.py": "Phase 4 - integration tests",
}


def pytest_collection_modifyitems(config, items):
    for item in items:
        reason = PENDING.get(item.path.name)
        if reason:
            item.add_marker(pytest.mark.skip(reason=f"PENDING ({reason}) - see tests/conftest.py"))


TEST_SECRET = "test-secret-that-is-at-least-32-bytes-long"


class FakeClock:
    """A clock you can move. Tests should never depend on the real time."""

    def __init__(self, start: datetime | None = None):
        self.now = start or datetime(2026, 10, 2, 9, 30, tzinfo=UTC)

    def __call__(self) -> datetime:
        return self.now

    def advance(self, **kwargs):
        self.now = self.now + timedelta(**kwargs)


def test_settings(**overrides) -> Settings:
    base = Settings.from_env()
    values = {**base.__dict__, "jwt_secret": TEST_SECRET, "bcrypt_cost": 4, "admin_email": None,
              "admin_password": None, "ai_provider": "offline", **overrides}  # fmt: skip
    return Settings(**values)


test_settings.__test__ = False  # not a test, despite the name


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def store() -> InMemoryStore:
    return InMemoryStore()


@pytest.fixture
def client(store, clock):
    """The real FastAPI app on in-memory adapters (no database, no network)."""
    app = create_app(
        test_settings(admin_email="admin@devblog.test", admin_password="admin-password"),
        users=store,
        posts=store,
        comments=store,
        clock=clock,
        ai=(ModerationService(RuleModerator()), SummaryService(TemplateSummarizer())),
    )
    with TestClient(app) as c:
        yield c


class Api:
    """Small helpers so API tests read like the user stories."""

    def __init__(self, client: TestClient, store: InMemoryStore):
        self.client = client
        self.store = store
        self._n = 0

    def user(self, role: str = "READER", name: str | None = None) -> dict:
        """Register + (optionally promote directly in the store) + log in. Returns the login JSON."""
        self._n += 1
        email = f"user{self._n}@devblog.test"
        r = self.client.post(
            "/api/v1/auth/register",
            json={"email": email, "password": "password-123", "displayName": name or f"User {self._n}"},
        )
        assert r.status_code == 201, r.text
        if role != "READER":
            self.store.users[r.json()["id"]].role = role
        return self.login(email, "password-123")

    def admin(self) -> dict:
        return self.login("admin@devblog.test", "admin-password")

    def login(self, email: str, password: str) -> dict:
        r = self.client.post("/api/v1/auth/login", json={"email": email, "password": password})
        assert r.status_code == 200, r.text
        return r.json()

    @staticmethod
    def h(session: dict) -> dict:
        return {"Authorization": f"Bearer {session['accessToken']}"}

    def post(self, session: dict, **fields) -> dict:
        body = {"title": "Hello DevBlog", "body": "First post. It has two sentences.", "status": "published", **fields}
        r = self.client.post("/api/v1/posts", json=body, headers=self.h(session))
        assert r.status_code == 201, r.text
        return r.json()


@pytest.fixture
def api(client, store) -> Api:
    return Api(client, store)

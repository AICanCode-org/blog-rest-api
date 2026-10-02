"""M2: registration, login, refresh rotation and roles, at the service level (FR-1, FR-2, FR-3)."""

import pytest

from app.auth import AuthService
from app.errors import ApiError
from app.models import Principal
from app.repository import InMemoryStore
from app.security import PasswordHasher, TokenService
from tests.conftest import TEST_SECRET

REG = {"email": "Asha@DevBlog.test", "password": "password-123", "displayName": "Asha"}


@pytest.fixture
def auth(store, clock) -> AuthService:
    return AuthService(store, PasswordHasher(cost=4), TokenService(TEST_SECRET, clock), clock)


def status_of(fn, *args):
    with pytest.raises(ApiError) as exc:
        fn(*args)
    return exc.value.status, exc.value.slug


def test_register_stores_a_bcrypt_hash_and_a_lower_case_email(auth: AuthService, store: InMemoryStore):
    user = auth.register(REG)
    assert user.email == "asha@devblog.test" and user.role == "READER" and user.display_name == "Asha"
    stored = store.users[user.id]
    assert stored.password_hash.startswith("$2") and "password-123" not in stored.password_hash


def test_register_ignores_a_role_in_the_body(auth: AuthService):
    # Mass assignment: copying request fields into the model would let anyone become ADMIN.
    assert auth.register({**REG, "role": "ADMIN"}).role == "READER"


def test_duplicate_email_is_409_case_insensitively(auth: AuthService):
    auth.register(REG)
    assert status_of(auth.register, {**REG, "email": "asha@devblog.TEST"}) == (409, "email-taken")


def test_register_validates(auth: AuthService):
    assert status_of(auth.register, {**REG, "password": "short"})[0] == 400
    assert status_of(auth.register, ["not", "an", "object"])[0] == 400


def test_login_returns_tokens(auth: AuthService):
    auth.register(REG)
    pair = auth.login({"email": " ASHA@devblog.test", "password": "password-123"})
    assert pair.user.email == "asha@devblog.test"
    assert auth.authenticate(f"Bearer {pair.access_token}").user_id == pair.user.id


def test_wrong_password_and_unknown_email_get_the_same_answer(auth: AuthService):
    auth.register(REG)
    wrong = status_of(auth.login, {"email": REG["email"], "password": "password-124"})
    unknown = status_of(auth.login, {"email": "nobody@devblog.test", "password": "password-123"})
    assert wrong == unknown == (401, "invalid-credentials")
    assert status_of(auth.login, {"email": REG["email"]})[0] == 400


def test_refresh_rotates_the_token(auth: AuthService):
    auth.register(REG)
    first = auth.login(REG)
    second = auth.refresh({"refreshToken": first.refresh_token})
    assert second.refresh_token != first.refresh_token
    assert auth.authenticate(f"Bearer {second.access_token}").user_id == first.user.id


def test_reusing_a_refresh_token_revokes_every_session(auth: AuthService):
    auth.register(REG)
    stolen = auth.login(REG)
    other_device = auth.login(REG)
    auth.refresh({"refreshToken": stolen.refresh_token})  # the legitimate rotation
    assert status_of(auth.refresh, {"refreshToken": stolen.refresh_token}) == (401, "invalid-refresh-token")
    # ...and the other device's still-unused token is now revoked too
    assert status_of(auth.refresh, {"refreshToken": other_device.refresh_token})[0] == 401


def test_refresh_token_expires_after_7_days(auth: AuthService, clock):
    auth.register(REG)
    pair = auth.login(REG)
    clock.advance(days=7)
    assert status_of(auth.refresh, {"refreshToken": pair.refresh_token}) == (401, "invalid-refresh-token")


def test_refresh_rejects_unknown_and_missing_tokens(auth: AuthService):
    assert status_of(auth.refresh, {"refreshToken": "made-up"}) == (401, "invalid-refresh-token")
    assert status_of(auth.refresh, {})[0] == 400


def test_refresh_picks_up_a_new_role(auth: AuthService, store: InMemoryStore):
    user = auth.register(REG)
    pair = auth.login(REG)
    store.users[user.id].role = "AUTHOR"
    assert auth.authenticate(f"Bearer {pair.access_token}").role == "READER"  # stateless: old claim
    renewed = auth.refresh({"refreshToken": pair.refresh_token})
    assert auth.authenticate(f"Bearer {renewed.access_token}").role == "AUTHOR"


def test_logout_revokes_and_is_idempotent(auth: AuthService):
    auth.register(REG)
    pair = auth.login(REG)
    auth.logout({"refreshToken": pair.refresh_token})
    auth.logout({"refreshToken": pair.refresh_token})
    auth.logout({"refreshToken": "never-existed"})
    assert status_of(auth.refresh, {"refreshToken": pair.refresh_token})[0] == 401


def test_authorization_header_rules(auth: AuthService):
    assert auth.optional(None) is None
    for bad in ["", "Bearer", "Bearer ", "Basic abc", "Token xyz", "Bearer not-a-jwt"]:
        assert status_of(auth.optional, bad) == (401, "unauthorized")
    assert status_of(auth.authenticate, None) == (401, "unauthorized")


def test_only_admins_change_roles(auth: AuthService):
    target = auth.register(REG)
    author = Principal(99, "AUTHOR")
    admin = Principal(100, "ADMIN")
    assert status_of(auth.change_role, author, target.id, {"role": "ADMIN"}) == (403, "forbidden")
    assert auth.change_role(admin, target.id, {"role": "AUTHOR"}).role == "AUTHOR"
    assert status_of(auth.change_role, admin, 12345, {"role": "AUTHOR"}) == (404, "not-found")
    assert status_of(auth.change_role, admin, target.id, {"role": "GOD"})[0] == 400
    assert status_of(auth.change_role, admin, admin.user_id, {"role": "READER"})[0] == 400


def test_me_returns_the_current_user(auth: AuthService):
    user = auth.register(REG)
    assert auth.me(Principal(user.id, "READER")).email == "asha@devblog.test"
    assert status_of(auth.me, Principal(999, "READER"))[0] == 401


def test_ensure_admin_creates_or_promotes(auth: AuthService):
    created = auth.ensure_admin("Root@DevBlog.test", "admin-password")
    assert created.role == "ADMIN" and created.email == "root@devblog.test"
    assert auth.ensure_admin("root@devblog.test", "ignored-password").id == created.id
    reader = auth.register(REG)
    assert auth.ensure_admin(REG["email"], "whatever-123").id == reader.id
    assert auth.me(Principal(reader.id, "READER")).role == "ADMIN"

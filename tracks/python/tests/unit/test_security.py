"""M2: passwords and tokens (FR-1, FR-2, NFR-3)."""

import base64
import json

import jwt
import pytest

from app.errors import ApiError
from app.models import User
from app.security import PasswordHasher, TokenService, hash_refresh_token, new_refresh_token, resolve_secret
from tests.conftest import TEST_SECRET

USER = User(7, "asha@devblog.test", "x", "Asha", "AUTHOR", None)  # type: ignore[arg-type]


def test_passwords_are_hashed_with_bcrypt_and_verified():
    hasher = PasswordHasher(cost=4)
    hashed = hasher.hash("correct horse")
    assert hashed.startswith("$2") and "correct horse" not in hashed
    assert hasher.verify("correct horse", hashed)
    assert not hasher.verify("wrong horse", hashed)
    assert hasher.hash("correct horse") != hashed  # a new salt every time


def test_unknown_user_still_runs_bcrypt_and_fails():
    assert PasswordHasher(cost=4).verify("anything", None) is False


def test_bcrypt_cost_is_bounded():
    with pytest.raises(ValueError):
        PasswordHasher(cost=3)


def test_access_token_claims(clock):
    tokens = TokenService(TEST_SECRET, clock)
    claims = jwt.decode(
        tokens.issue_access_token(USER), TEST_SECRET, algorithms=["HS256"], options={"verify_exp": False}
    )
    assert claims["sub"] == "7" and claims["role"] == "AUTHOR" and claims["iss"] == "devblog"
    assert claims["exp"] - claims["iat"] == 900
    assert claims["iat"] == int(clock().timestamp())


def test_verify_returns_the_principal(clock):
    tokens = TokenService(TEST_SECRET, clock)
    principal = tokens.verify_access_token(tokens.issue_access_token(USER))
    assert (principal.user_id, principal.role) == (7, "AUTHOR")


def test_expired_token_is_rejected(clock):
    tokens = TokenService(TEST_SECRET, clock)
    token = tokens.issue_access_token(USER)
    clock.advance(minutes=15)
    with pytest.raises(ApiError) as exc:
        tokens.verify_access_token(token)
    assert exc.value.status == 401


def _forge(header: dict, claims: dict, signature: str = "") -> str:
    enc = lambda d: base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip("=")  # noqa: E731
    return f"{enc(header)}.{enc(claims)}.{signature}"


@pytest.mark.parametrize(
    "make",
    [
        # "alg": "none" - the classic: an unsigned token that a careless verifier accepts
        lambda c: _forge({"alg": "none", "typ": "JWT"}, {"sub": "1", "role": "ADMIN", "iss": "devblog", "exp": 9e9}),
        # signed with another secret
        lambda c: jwt.encode({"sub": "1", "role": "ADMIN", "iss": "devblog", "exp": 9_999_999_999}, "x" * 40),
        # wrong issuer
        lambda c: jwt.encode({"sub": "1", "role": "ADMIN", "iss": "evil", "exp": 9_999_999_999}, TEST_SECRET),
        # unknown role
        lambda c: jwt.encode({"sub": "1", "role": "ROOT", "iss": "devblog", "exp": 9_999_999_999}, TEST_SECRET),
        # no expiry
        lambda c: jwt.encode({"sub": "1", "role": "ADMIN", "iss": "devblog"}, TEST_SECRET),
        lambda c: "not.a.jwt",
    ],
)
def test_forged_or_broken_tokens_are_rejected(clock, make):
    with pytest.raises(ApiError) as exc:
        TokenService(TEST_SECRET, clock).verify_access_token(make(clock))
    assert exc.value.status == 401 and exc.value.slug == "unauthorized"


def test_tampered_payload_is_rejected(clock):
    tokens = TokenService(TEST_SECRET, clock)
    header, _, signature = tokens.issue_access_token(USER).split(".")
    evil = _forge({"alg": "HS256", "typ": "JWT"}, {"sub": "7", "role": "ADMIN", "iss": "devblog", "exp": 9e9})
    with pytest.raises(ApiError):
        tokens.verify_access_token(f"{header}.{evil.split('.')[1]}.{signature}")


def test_refresh_tokens_are_random_and_only_the_hash_is_kept():
    token, digest = new_refresh_token()
    other, _ = new_refresh_token()
    assert token != other and len(token) >= 43
    assert digest == hash_refresh_token(token) and len(digest) == 64 and token not in digest


def test_jwt_secret_rules():
    assert len(resolve_secret(None)) >= 32  # random, dev only
    assert resolve_secret("s" * 32) == "s" * 32
    with pytest.raises(ValueError):
        resolve_secret("too-short")

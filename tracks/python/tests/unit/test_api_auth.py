"""M2: the auth endpoints over HTTP - status codes, headers and JSON shapes from spec/openapi.yaml."""

PROBLEM = "application/problem+json"


def test_register_login_me(client):
    r = client.post(
        "/api/v1/auth/register",
        json={"email": "Asha@DevBlog.test", "password": "password-123", "displayName": "Asha", "role": "ADMIN"},
    )
    assert r.status_code == 201
    assert r.json() == {
        "id": r.json()["id"],
        "email": "asha@devblog.test",
        "displayName": "Asha",
        "role": "READER",
        "createdAt": "2026-10-02T09:30:00.000Z",
    }
    login = client.post("/api/v1/auth/login", json={"email": "asha@devblog.test", "password": "password-123"}).json()
    assert login["tokenType"] == "Bearer" and login["expiresIn"] == 900 and login["user"]["role"] == "READER"
    assert login["accessToken"].count(".") == 2 and login["refreshToken"]
    me = client.get("/api/v1/me", headers={"Authorization": f"Bearer {login['accessToken']}"})
    assert me.status_code == 200 and me.json()["email"] == "asha@devblog.test"


def test_errors_are_problem_json(client):
    r = client.post("/api/v1/auth/register", json={"email": "nope", "password": "password-123", "displayName": "A"})
    assert r.status_code == 400 and r.headers["content-type"].startswith(PROBLEM)
    assert r.json()["type"] == "https://devblog.example/problems/validation-error"
    assert r.json()["detail"] == "email must be a valid e-mail address"


def test_bodies_that_are_not_json_objects_are_400(client):
    assert (
        client.post("/api/v1/auth/login", content="not json", headers={"Content-Type": "application/json"}).status_code
        == 400
    )
    assert client.post("/api/v1/auth/login", json=["a", "b"]).status_code == 400


def test_duplicate_email_is_409(api, client):
    api.user()
    r = client.post(
        "/api/v1/auth/register", json={"email": "USER1@devblog.test", "password": "password-123", "displayName": "X"}
    )
    assert r.status_code == 409 and r.json()["type"].endswith("/email-taken")


def test_bad_login_is_401_invalid_credentials(api, client):
    api.user()
    r = client.post("/api/v1/auth/login", json={"email": "user1@devblog.test", "password": "wrong-password"})
    assert r.status_code == 401 and r.json()["type"].endswith("/invalid-credentials")


def test_me_without_or_with_a_bad_token_is_401_with_www_authenticate(client):
    for headers in [{}, {"Authorization": "Bearer nope"}, {"Authorization": "Basic dXNlcjpwYXNz"}]:
        r = client.get("/api/v1/me", headers=headers)
        assert r.status_code == 401 and r.headers["www-authenticate"] == "Bearer"
        assert r.json()["type"].endswith("/unauthorized")


def test_expired_access_token_is_401(api, client, clock):
    session = api.user()
    clock.advance(minutes=16)
    assert client.get("/api/v1/me", headers=api.h(session)).status_code == 401


def test_refresh_and_logout(api, client):
    session = api.user()
    renewed = client.post("/api/v1/auth/refresh", json={"refreshToken": session["refreshToken"]})
    assert renewed.status_code == 200 and renewed.json()["refreshToken"] != session["refreshToken"]
    reused = client.post("/api/v1/auth/refresh", json={"refreshToken": session["refreshToken"]})
    assert reused.status_code == 401 and reused.json()["type"].endswith("/invalid-refresh-token")
    assert client.post("/api/v1/auth/logout", json={"refreshToken": "whatever"}).status_code == 204
    assert client.post("/api/v1/auth/logout", json={}).status_code == 400


def test_admin_promotes_a_reader_who_then_refreshes_into_the_new_role(api, client):
    reader = api.user()
    admin = api.admin()
    uid = reader["user"]["id"]
    assert (
        client.patch(f"/api/v1/admin/users/{uid}/role", json={"role": "AUTHOR"}, headers=api.h(reader)).status_code
        == 403
    )
    r = client.patch(f"/api/v1/admin/users/{uid}/role", json={"role": "AUTHOR"}, headers=api.h(admin))
    assert r.status_code == 200 and r.json()["role"] == "AUTHOR"
    renewed = client.post("/api/v1/auth/refresh", json={"refreshToken": reader["refreshToken"]}).json()
    assert renewed["user"]["role"] == "AUTHOR"
    assert (
        client.patch("/api/v1/admin/users/abc/role", json={"role": "AUTHOR"}, headers=api.h(admin)).status_code == 404
    )
    assert (
        client.patch("/api/v1/admin/users/999/role", json={"role": "AUTHOR"}, headers=api.h(admin)).status_code == 404
    )


def test_unknown_routes_are_problem_json_404(client):
    r = client.get("/api/v1/nope")
    assert r.status_code == 404 and r.headers["content-type"].startswith(PROBLEM)
    assert r.json()["type"].endswith("/not-found")

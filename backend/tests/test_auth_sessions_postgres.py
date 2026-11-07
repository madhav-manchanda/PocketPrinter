from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from backend.app.database.session import SessionLocal, engine, get_db
from backend.app.main import app
from backend.app.models.user import User


pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql",
    reason="Refresh-token rotation/reuse behavior is integration-tested on PostgreSQL",
)


def test_refresh_rotation_and_reuse_revoke_the_entire_session_family() -> None:
    email = f"{uuid.uuid4().hex}@pocketprint.example.com"

    def override_get_db():
        with SessionLocal() as db:
            yield db

    app.dependency_overrides[get_db] = override_get_db
    try:
        with TestClient(app, base_url="http://localhost") as client:
            registered = client.post(
                "/api/auth/register",
                json={
                    "email": email,
                    "password": "StrongPassword2026!",
                    "full_name": "CI Session Test",
                },
            )
            assert registered.status_code == 201, registered.text
            initial = registered.json()

            rotated = client.post(
                "/api/auth/refresh",
                json={"refresh_token": initial["refresh_token"]},
            )
            assert rotated.status_code == 200, rotated.text
            replacement = rotated.json()
            assert replacement["refresh_token"] != initial["refresh_token"]

            reused = client.post(
                "/api/auth/refresh",
                json={"refresh_token": initial["refresh_token"]},
            )
            assert reused.status_code == 401

            # Reusing a rotated token revokes the whole family, including the
            # replacement token and already-issued access tokens.
            replacement_reuse = client.post(
                "/api/auth/refresh",
                json={"refresh_token": replacement["refresh_token"]},
            )
            assert replacement_reuse.status_code == 401
            me = client.get(
                "/api/auth/me",
                headers={"Authorization": f"Bearer {replacement['access_token']}"},
            )
            assert me.status_code == 401
    finally:
        app.dependency_overrides.pop(get_db, None)
        with SessionLocal() as db:
            db.query(User).filter(User.email == email).delete(synchronize_session=False)
            db.commit()

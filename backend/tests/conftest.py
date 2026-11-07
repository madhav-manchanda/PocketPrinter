"""Shared pytest fixtures for the backend test suite.

Tests run against SQLite by default so they need no external services. Tests that
must prove PostgreSQL row-locking semantics are explicitly skipped unless
DATABASE_URL points at PostgreSQL.
"""

from __future__ import annotations

import os
import tempfile
from pathlib import Path

# Configure an isolated test environment before importing the application so the
# engine, storage root, and settings all point at throwaway locations.
_TEST_STORAGE = Path(tempfile.mkdtemp(prefix="pocketprint-test-storage-"))
os.environ.setdefault("APP_ENV", "test")
os.environ.setdefault("SECRET_KEY", "test-secret-key-please-change-0123456789abcdef")
os.environ.setdefault("STORAGE_ROOT", str(_TEST_STORAGE))
os.environ.setdefault("DATABASE_URL", "sqlite:///./_pytest_pocketprint.db")
os.environ.setdefault("MIN_FREE_STORAGE_BYTES", "0")

import pytest  # noqa: E402

from backend.app.core.config import settings  # noqa: E402
from backend.app.database.session import Base, engine  # noqa: E402


@pytest.fixture(scope="session", autouse=True)
def _prepare_database():
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)


@pytest.fixture()
def db_session():
    from backend.app.database.session import SessionLocal

    with SessionLocal() as session:
        yield session


@pytest.fixture()
def client():
    from fastapi.testclient import TestClient

    from backend.app.main import app

    with TestClient(app, base_url="http://localhost") as test_client:
        yield test_client


@pytest.fixture()
def printer_factory(client):
    """Helper fixture to register a printer and connect a client session."""
    def _create(device_id: str = "printer-test-1", password: str = "PrinterSecret2026!"):
        reg_res = client.post(
            "/api/devices/register",
            json={
                "deviceId": device_id,
                "deviceName": f"Printer {device_id}",
                "devicePassword": password,
                "printerModel": "HP Ink Tank 310 series",
                "vidPid": "03F0:1053",
                "appVersion": "1.0.0",
            },
        )
        assert reg_res.status_code == 200, reg_res.text
        device_token = reg_res.json()["deviceToken"]

        conn_res = client.post(
            "/api/connect",
            json={"password": password, "deviceId": device_id},
        )
        assert conn_res.status_code == 200, conn_res.text
        client_token = conn_res.json()["clientToken"]

        return {
            "deviceId": device_id,
            "deviceToken": device_token,
            "clientToken": client_token,
            "password": password,
            "deviceHeaders": {"X-Device-Token": device_token},
            "clientHeaders": {"Authorization": f"Bearer {client_token}"},
        }

    return _create

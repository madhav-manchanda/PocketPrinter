from __future__ import annotations

from datetime import timedelta

import pytest
from pydantic import ValidationError

from backend.app.core.config import Settings
from backend.app.core.security import (
    generate_pairing_code,
    get_password_hash,
    token_hash,
    utcnow,
    validate_password,
    verify_password,
)
from backend.app.services.job_state import can_transition, canonical_agent_state


def test_password_hash_is_not_reversible_and_verifies() -> None:
    password = "PocketPrint-Test-Password-2026"
    encoded = get_password_hash(password)
    assert encoded != password
    assert verify_password(password, encoded)
    assert not verify_password("wrong-password", encoded)


def test_password_policy_rejects_weak_passwords() -> None:
    with pytest.raises(ValueError):
        validate_password("short")
    with pytest.raises(ValueError):
        validate_password("alllowercase123")
    validate_password("StrongPassword2026")


def test_pairing_code_has_human_enterable_format_and_is_hashed() -> None:
    code = generate_pairing_code()
    assert len(code) == 19
    assert code.count("-") == 3
    assert token_hash(code) != code
    assert token_hash(code) == token_hash(code)


def test_production_settings_fail_closed_without_secrets_and_allowlists() -> None:
    with pytest.raises(ValidationError):
        Settings(
            APP_ENV="production",
            SECRET_KEY="development-only-secret-change-me",
            DATABASE_URL="sqlite:///not-production.db",
            CORS_ALLOWED_ORIGINS="*",
            TRUSTED_HOSTS="*",
        )


def test_production_settings_accept_explicit_configuration() -> None:
    settings = Settings(
        APP_ENV="production",
        SECRET_KEY="x" * 64,
        DATABASE_URL="postgresql+psycopg://user:pass@localhost/pocketprint",
        CORS_ALLOWED_ORIGINS="https://print.example.com",
        TRUSTED_HOSTS="print.example.com",
    )
    assert settings.cors_origins == ["https://print.example.com"]
    assert settings.trusted_hosts == ["print.example.com"]


def test_android_status_mapping_and_safe_state_transitions() -> None:
    assert canonical_agent_state("TRANSMITTING_USB") == "PRINTING"
    assert canonical_agent_state("NEEDS_RECOVERY") == "NEEDS_RECONCILIATION"
    assert can_transition("QUEUED", "ASSIGNED")
    assert not can_transition("COMPLETED", "QUEUED")
    assert not can_transition("PRINTING", "QUEUED")


def test_cancellation_and_retry_transitions_require_explicit_recovery() -> None:
    assert can_transition("ASSIGNED", "CANCEL_REQUESTED")
    assert can_transition("PRINTING", "CANCEL_REQUESTED")
    assert can_transition("CANCEL_REQUESTED", "UNCERTAIN")
    assert can_transition("CANCEL_REQUESTED", "CANCELLED")
    assert can_transition("FAILED", "RETRY_PENDING")
    assert can_transition("RETRY_PENDING", "QUEUED")
    assert not can_transition("FAILED", "CANCELLED")
    assert not can_transition("CANCELLED", "RETRY_PENDING")
    assert not can_transition("UNCERTAIN", "QUEUED")


def test_agent_uncertain_statuses_are_not_silently_retried() -> None:
    assert canonical_agent_state("TRANSFER_ERROR") == "UNCERTAIN"
    assert canonical_agent_state("DISCONNECTED") == "UNCERTAIN"
    assert canonical_agent_state("NEEDS_RECOVERY") == "NEEDS_RECONCILIATION"

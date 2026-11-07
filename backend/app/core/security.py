from __future__ import annotations

import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerifyMismatchError
from jose import JWTError, jwt

from backend.app.core.config import settings


password_hasher = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=4)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def ensure_aware(value: datetime) -> datetime:
    """Normalize a database timestamp to aware UTC.

    PostgreSQL returns tz-aware datetimes for ``DateTime(timezone=True)``, but
    SQLite (used by tests and local development) returns naive ones. All
    comparisons in the codebase go through this helper so both engines behave
    identically.
    """
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def get_password_hash(password: str) -> str:
    return password_hasher.hash(password)


def verify_password(plain_password: str, hashed_password: str) -> bool:
    if not hashed_password:
        return False
    try:
        return password_hasher.verify(hashed_password, plain_password)
    except (VerifyMismatchError, InvalidHashError, ValueError):
        return False


def password_needs_rehash(hashed_password: str) -> bool:
    try:
        return password_hasher.check_needs_rehash(hashed_password)
    except (InvalidHashError, ValueError):
        return True


def validate_password(password: str) -> None:
    """Enforce the shared user/administrator password policy."""
    if len(password) < 12 or len(password) > 128:
        raise ValueError("Password must contain 12 to 128 characters")
    if not any(character.islower() for character in password):
        raise ValueError("Password must contain a lower case character")
    if not any(character.isupper() for character in password):
        raise ValueError("Password must contain an upper case character")
    if not any(character.isdigit() for character in password):
        raise ValueError("Password must contain a number")


def create_access_token(subject: str | Any, session_id: str | None = None) -> str:
    now = utcnow()
    payload: dict[str, Any] = {
        "sub": str(subject),
        "type": "access",
        "iat": now,
        "exp": now + timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES),
        "jti": secrets.token_urlsafe(16),
    }
    if session_id:
        payload["sid"] = session_id
    return jwt.encode(payload, settings.SECRET_KEY, algorithm=settings.ALGORITHM)


def decode_access_token(token: str) -> dict | None:
    try:
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=[settings.ALGORITHM])
    except JWTError:
        return None
    return payload if payload.get("type") == "access" else None


def create_ws_ticket(subject: str, audience: str) -> str:
    now = utcnow()
    return jwt.encode(
        {
            "sub": str(subject),
            "aud": audience,
            "type": "ws",
            "iat": now,
            "exp": now + timedelta(seconds=settings.WS_TICKET_EXPIRE_SECONDS),
            "jti": secrets.token_urlsafe(16),
        },
        settings.SECRET_KEY,
        algorithm=settings.ALGORITHM,
    )


def decode_ws_ticket(token: str, audience: str) -> dict | None:
    try:
        payload = jwt.decode(
            token,
            settings.SECRET_KEY,
            algorithms=[settings.ALGORITHM],
            audience=audience,
        )
    except JWTError:
        return None
    return payload if payload.get("type") == "ws" else None


def generate_opaque_token(prefix: str = "") -> str:
    return prefix + secrets.token_urlsafe(48)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def generate_pairing_code() -> str:
    # 16 hex characters in four groups: 64 bits of entropy, still enterable by a human.
    return "-".join(secrets.token_hex(2).upper() for _ in range(4))


def generate_device_token() -> str:
    return generate_opaque_token("pp_node_")

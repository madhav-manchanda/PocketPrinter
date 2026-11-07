from __future__ import annotations

import hashlib
import uuid
from datetime import timedelta

from fastapi import HTTPException
from sqlalchemy import delete, func
from sqlalchemy.orm import Session

from backend.app.core.config import settings
from backend.app.core.security import (
    create_access_token,
    ensure_aware,
    generate_opaque_token,
    token_hash,
    utcnow,
)
from backend.app.models.user import LoginAttempt, User, UserSession


def normalized_email(email: str) -> str:
    return email.strip().lower()


def identifier_hash(email: str) -> str:
    return hashlib.sha256(normalized_email(email).encode("utf-8")).hexdigest()


def enforce_login_rate_limit(db: Session, email: str, ip_address: str) -> None:
    cutoff = utcnow() - timedelta(seconds=settings.LOGIN_ATTEMPT_WINDOW_SECONDS)
    failures = db.query(func.count(LoginAttempt.id)).filter(
        LoginAttempt.created_at >= cutoff,
        LoginAttempt.succeeded.is_(False),
        (LoginAttempt.identifier_hash == identifier_hash(email))
        | (LoginAttempt.ip_address == ip_address),
    ).scalar() or 0
    if failures >= settings.LOGIN_MAX_ATTEMPTS:
        raise HTTPException(status_code=429, detail="Too many login attempts; try again later")


def record_login_attempt(db: Session, email: str, ip_address: str, succeeded: bool) -> None:
    db.add(
        LoginAttempt(
            identifier_hash=identifier_hash(email),
            ip_address=ip_address,
            succeeded=succeeded,
        )
    )
    # Opportunistic retention: drop attempts older than two days.
    db.execute(delete(LoginAttempt).where(LoginAttempt.created_at < utcnow() - timedelta(days=2)))


def issue_session(
    db: Session,
    user: User,
    ip_address: str | None,
    user_agent: str | None,
    family_id: str | None = None,
) -> tuple[str, str, UserSession]:
    refresh_token = generate_opaque_token("pp_refresh_")
    session = UserSession(
        user_id=user.id,
        family_id=family_id or str(uuid.uuid4()),
        refresh_token_hash=token_hash(refresh_token),
        ip_address=ip_address,
        user_agent=(user_agent or "")[:500] or None,
        expires_at=utcnow() + timedelta(days=settings.REFRESH_TOKEN_EXPIRE_DAYS),
    )
    db.add(session)
    db.flush()
    return create_access_token(user.id, session.id), refresh_token, session


def rotate_refresh_token(
    db: Session,
    refresh_token: str,
    ip_address: str | None,
    user_agent: str | None,
) -> tuple[str, str, UserSession]:
    now = utcnow()
    session = (
        db.query(UserSession)
        .filter(UserSession.refresh_token_hash == token_hash(refresh_token))
        .with_for_update()
        .first()
    )
    if not session or session.revoked_at is not None or ensure_aware(session.expires_at) <= now:
        if session:
            # Reuse of a rotated/revoked token revokes the whole family.
            db.query(UserSession).filter(UserSession.family_id == session.family_id).update(
                {UserSession.revoked_at: now}, synchronize_session=False
            )
        raise HTTPException(status_code=401, detail="Invalid or reused refresh token")
    user = db.query(User).filter(User.id == session.user_id, User.is_active.is_(True)).first()
    if not user:
        raise HTTPException(status_code=401, detail="User account is unavailable")
    session.revoked_at = now
    session.last_used_at = now
    access, new_refresh, replacement = issue_session(
        db, user, ip_address, user_agent, family_id=session.family_id
    )
    session.replaced_by_id = replacement.id
    return access, new_refresh, replacement


def revoke_session(db: Session, refresh_token: str) -> None:
    session = db.query(UserSession).filter(
        UserSession.refresh_token_hash == token_hash(refresh_token)
    ).first()
    if session and session.revoked_at is None:
        session.revoked_at = utcnow()

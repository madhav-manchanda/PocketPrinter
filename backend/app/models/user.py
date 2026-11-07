from __future__ import annotations

import uuid

from sqlalchemy import Boolean, Column, DateTime, ForeignKey, Index, String
from sqlalchemy.orm import relationship

from backend.app.core.security import utcnow
from backend.app.database.session import Base


class User(Base):
    __tablename__ = "users"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    # Uniqueness is enforced by the named unique index below, matching the migration.
    email = Column(String(320), nullable=False)
    hashed_password = Column(String(512), nullable=False)
    full_name = Column(String(200), nullable=True)
    is_active = Column(Boolean, nullable=False, default=True)
    is_admin = Column(Boolean, nullable=False, default=False)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)
    updated_at = Column(DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow)

    devices = relationship("Device", back_populates="owner")
    jobs = relationship("PrintJob", back_populates="user")
    documents = relationship("Document", back_populates="owner")
    sessions = relationship("UserSession", back_populates="user", cascade="all, delete-orphan")
    pairing_sessions = relationship("PairingSession", back_populates="user", cascade="all, delete-orphan")

    __table_args__ = (Index("ix_users_email", "email", unique=True),)


class UserSession(Base):
    __tablename__ = "user_sessions"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id = Column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    family_id = Column(String(36), nullable=False)
    refresh_token_hash = Column(String(64), nullable=False, unique=True)
    user_agent = Column(String(500), nullable=True)
    ip_address = Column(String(64), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    last_used_at = Column(DateTime(timezone=True), nullable=True)
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    replaced_by_id = Column(String(36), ForeignKey("user_sessions.id"), nullable=True)

    user = relationship("User", back_populates="sessions")

    __table_args__ = (
        Index("ix_user_sessions_family_id", "family_id"),
        Index("ix_user_sessions_user_active", "user_id", "revoked_at"),
    )


class LoginAttempt(Base):
    __tablename__ = "login_attempts"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    identifier_hash = Column(String(64), nullable=False)
    ip_address = Column(String(64), nullable=False)
    succeeded = Column(Boolean, nullable=False, default=False)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)

    __table_args__ = (
        Index("ix_login_attempts_identifier_hash", "identifier_hash"),
        Index("ix_login_attempts_ip_address", "ip_address"),
        Index("ix_login_attempts_created_at", "created_at"),
    )

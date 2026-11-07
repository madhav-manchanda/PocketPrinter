from __future__ import annotations

import uuid

from sqlalchemy import Boolean, CheckConstraint, Column, DateTime, ForeignKey, Index, Integer, String
from sqlalchemy.orm import relationship

from backend.app.core.security import utcnow
from backend.app.database.session import Base


class Device(Base):
    __tablename__ = "devices"

    id = Column(String(128), primary_key=True)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="RESTRICT"), nullable=True)
    device_name = Column(String(200), nullable=False, default="PocketPrint Node")
    # Argon2id password hash for user-side and printer-side pairing
    password_hash = Column(String(512), nullable=True)
    # Legacy column retained for a non-destructive migration; always NULL now.
    device_token = Column(String(64), nullable=True)
    status = Column(String(32), nullable=False, default="OFFLINE")
    is_online = Column(Boolean, nullable=False, default=False)
    is_revoked = Column(Boolean, nullable=False, default=False)
    is_disabled = Column(Boolean, nullable=False, default=False)
    last_heartbeat = Column(DateTime(timezone=True), nullable=True)
    last_connected_at = Column(DateTime(timezone=True), nullable=True)
    app_version = Column(String(64), nullable=True)
    printer_model = Column(String(200), nullable=False, default="HP Ink Tank 310 series")
    vid_pid = Column(String(32), nullable=False, default="03F0:1053")
    active_profile = Column(String(200), nullable=False, default="HPLIP Pcl3Gui2 Default")
    current_job_id = Column(String(36), ForeignKey("print_jobs.id", use_alter=True, name="fk_devices_current_job_id_print_jobs"), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)
    updated_at = Column(DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow)

    owner = relationship("User", back_populates="devices")
    jobs = relationship("PrintJob", back_populates="device", foreign_keys="PrintJob.device_id")
    documents = relationship("Document", back_populates="device", cascade="all, delete-orphan")
    credentials = relationship("DeviceCredential", back_populates="device", cascade="all, delete-orphan")
    leases = relationship("DeviceLease", back_populates="device", cascade="all, delete-orphan")
    client_sessions = relationship("ClientSession", back_populates="device", cascade="all, delete-orphan")

    __table_args__ = (
        CheckConstraint(
            "status IN ('PENDING_PAIRING','ONLINE','OFFLINE','REVOKED','DISABLED')",
            name="ck_devices_status",
        ),
        Index("ix_devices_user_id", "user_id"),
        Index("ix_devices_status", "status"),
    )


class ClientSession(Base):
    __tablename__ = "client_sessions"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    device_id = Column(String(128), ForeignKey("devices.id", ondelete="CASCADE"), nullable=False)
    token_hash = Column(String(64), nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    last_used_at = Column(DateTime(timezone=True), nullable=True)
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    client_ip = Column(String(64), nullable=True)
    user_agent = Column(String(500), nullable=True)

    device = relationship("Device", back_populates="client_sessions")

    __table_args__ = (
        Index("ix_client_sessions_device_id", "device_id"),
        Index("ix_client_sessions_token_hash", "token_hash", unique=True),
        Index("ix_client_sessions_expires_at", "expires_at"),
    )


class DeviceCredential(Base):
    __tablename__ = "device_credentials"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    device_id = Column(String(128), ForeignKey("devices.id", ondelete="CASCADE"), nullable=False)
    token_hash = Column(String(64), nullable=False)
    generation = Column(Integer, nullable=False, default=1)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)
    last_used_at = Column(DateTime(timezone=True), nullable=True)
    expires_at = Column(DateTime(timezone=True), nullable=True)
    revoked_at = Column(DateTime(timezone=True), nullable=True)

    device = relationship("Device", back_populates="credentials")

    __table_args__ = (
        Index("ix_device_credentials_device_id", "device_id"),
        Index("ix_device_credentials_token_hash", "token_hash", unique=True),
    )


class PairingSession(Base):
    __tablename__ = "pairing_sessions"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id = Column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    code_hash = Column(String(64), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    used_at = Column(DateTime(timezone=True), nullable=True)
    attempts = Column(Integer, nullable=False, default=0)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)
    paired_device_id = Column(String(128), ForeignKey("devices.id"), nullable=True)

    user = relationship("User", back_populates="pairing_sessions")

    __table_args__ = (
        Index("ix_pairing_sessions_user_id", "user_id"),
        Index("ix_pairing_sessions_code_hash", "code_hash", unique=True),
        Index("ix_pairing_sessions_expires_at", "expires_at"),
    )

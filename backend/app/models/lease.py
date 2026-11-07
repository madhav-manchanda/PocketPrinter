from __future__ import annotations

import uuid

from sqlalchemy import (
    CheckConstraint,
    Column,
    DateTime,
    ForeignKey,
    Index,
    String,
    text,
)
from sqlalchemy.orm import relationship

from backend.app.core.security import utcnow
from backend.app.database.session import Base


class DeviceLease(Base):
    __tablename__ = "device_leases"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    job_id = Column(String(36), ForeignKey("print_jobs.id", ondelete="CASCADE"), nullable=False)
    device_id = Column(String(128), ForeignKey("devices.id", ondelete="CASCADE"), nullable=False)
    token_hash = Column(String(64), nullable=False, unique=True)
    generation = Column(String(36), nullable=False, default=lambda: str(uuid.uuid4()))
    state = Column(String(32), nullable=False, default="ACTIVE")
    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    last_renewed_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)
    released_at = Column(DateTime(timezone=True), nullable=True)

    job = relationship("PrintJob", back_populates="leases")
    device = relationship("Device", back_populates="leases")

    __table_args__ = (
        CheckConstraint("state IN ('ACTIVE','RELEASED','EXPIRED')", name="ck_device_leases_state"),
        Index("ix_device_leases_expires_at", "expires_at"),
        Index(
            "uq_active_lease_device",
            "device_id",
            unique=True,
            postgresql_where=text("state = 'ACTIVE'"),
            sqlite_where=text("state = 'ACTIVE'"),
        ),
        Index(
            "uq_active_lease_job",
            "job_id",
            unique=True,
            postgresql_where=text("state = 'ACTIVE'"),
            sqlite_where=text("state = 'ACTIVE'"),
        ),
    )

from __future__ import annotations

import uuid

from sqlalchemy import (
    JSON,
    Column,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import relationship

from backend.app.core.security import utcnow
from backend.app.database.session import Base


class JobPage(Base):
    __tablename__ = "job_pages"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    job_id = Column(String(36), ForeignKey("print_jobs.id", ondelete="CASCADE"), nullable=False)
    attempt_number = Column(Integer, nullable=False, default=1)
    document_page_number = Column(Integer, nullable=False)
    scheduled_page_number = Column(Integer, nullable=False)
    copy_number = Column(Integer, nullable=False, default=1)
    state = Column(String(40), nullable=False, default="PENDING")
    idempotency_key = Column(String(200), nullable=False)
    started_at = Column(DateTime(timezone=True), nullable=True)
    transmission_started_at = Column(DateTime(timezone=True), nullable=True)
    transmission_completed_at = Column(DateTime(timezone=True), nullable=True)
    last_update_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)
    error_code = Column(String(100), nullable=True)
    error_detail = Column(String(2000), nullable=True)

    job = relationship("PrintJob", back_populates="pages")

    __table_args__ = (
        UniqueConstraint("job_id", "idempotency_key", name="uq_job_page_idempotency"),
        UniqueConstraint(
            "job_id", "attempt_number", "scheduled_page_number", "copy_number",
            name="uq_job_page_schedule",
        ),
        Index("ix_job_pages_job_id", "job_id"),
    )


class JobEvent(Base):
    __tablename__ = "job_events"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    job_id = Column(String(36), ForeignKey("print_jobs.id", ondelete="CASCADE"), nullable=False)
    page_id = Column(String(36), ForeignKey("job_pages.id", ondelete="SET NULL"), nullable=True)
    attempt_number = Column(Integer, nullable=False, default=1)
    event_type = Column(String(80), nullable=False)
    # Legacy-compatible "status" field kept in sync with event_type consumers.
    status = Column(String(80), nullable=False)
    message = Column(String(1000), nullable=False)
    idempotency_key = Column(String(200), nullable=True)
    event_data = Column(JSON, nullable=False, default=dict)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)

    job = relationship("PrintJob", back_populates="events")

    __table_args__ = (
        UniqueConstraint("job_id", "idempotency_key", name="uq_job_event_idempotency"),
        Index("ix_job_events_job_id", "job_id"),
        Index("ix_job_events_page_id", "page_id"),
        Index("ix_job_events_event_type", "event_type"),
        Index("ix_job_events_created_at", "created_at"),
        Index("ix_job_events_job_created", "job_id", "created_at"),
    )

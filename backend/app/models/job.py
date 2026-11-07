from __future__ import annotations

import uuid

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    Column,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
)
from sqlalchemy.orm import relationship

from backend.app.core.security import utcnow
from backend.app.database.session import Base


JOB_STATES = (
    "QUEUED", "ASSIGNED", "DOWNLOADING", "READY", "PRINTING",
    "TRANSMISSION_COMPLETED", "COMPLETED", "CANCEL_REQUESTED", "CANCELLED",
    "FAILED", "RETRY_PENDING", "UNCERTAIN", "NEEDS_RECONCILIATION",
)


class PrintJob(Base):
    __tablename__ = "print_jobs"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id = Column(String(36), ForeignKey("users.id", ondelete="RESTRICT"), nullable=True)
    device_id = Column(String(128), ForeignKey("devices.id", ondelete="RESTRICT"), nullable=False)
    document_id = Column(String(36), ForeignKey("documents.id", ondelete="RESTRICT"), nullable=True)

    # Legacy metadata columns retained so an existing database migrates without data loss.
    filename = Column(String(500), nullable=False)
    original_path = Column(String(1000), nullable=True)
    converted_pdf_path = Column(String(1000), nullable=True)
    file_size = Column(Integer, nullable=False, default=0)
    mime_type = Column(String(200), nullable=False, default="application/pdf")
    page_count = Column(Integer, nullable=True)

    page_range = Column(String(200), nullable=False, default="")
    color_mode = Column(String(40), nullable=False, default="MONOCHROME_1BIT")
    paper_size = Column(String(40), nullable=False, default="A4")
    orientation = Column(String(40), nullable=False, default="PORTRAIT")
    copies = Column(Integer, nullable=False, default=1)
    scaling = Column(String(40), nullable=False, default="FIT_TO_PAGE")
    dpi = Column(Integer, nullable=False, default=300)
    quality = Column(String(40), nullable=False, default="NORMAL")
    margins = Column(String(500), nullable=False, default='{"top":4,"bottom":4,"left":4,"right":4,"unit":"mm"}')
    collate = Column(Boolean, nullable=False, default=True)
    pages_per_sheet = Column(Integer, nullable=False, default=1)
    print_options = Column(String(2000), nullable=False, default="{}")

    status = Column(String(40), nullable=False, default="QUEUED")
    progress_percent = Column(Integer, nullable=False, default=0)
    bytes_transferred = Column(Integer, nullable=False, default=0)
    scheduled_items = Column(Integer, nullable=False, default=0)
    completed_items = Column(Integer, nullable=False, default=0)
    current_attempt = Column(Integer, nullable=False, default=1)
    failure_reason = Column(String(2000), nullable=True)
    cancellation_requested_at = Column(DateTime(timezone=True), nullable=True)
    physical_output_confirmed = Column(Boolean, nullable=False, default=False)

    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)
    assigned_at = Column(DateTime(timezone=True), nullable=True)
    started_at = Column(DateTime(timezone=True), nullable=True)
    transmission_completed_at = Column(DateTime(timezone=True), nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    updated_at = Column(DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow)

    user = relationship("User", back_populates="jobs")
    device = relationship("Device", back_populates="jobs", foreign_keys=[device_id])
    document = relationship("Document", back_populates="jobs")
    events = relationship("JobEvent", back_populates="job", cascade="all, delete-orphan")
    attempts = relationship("JobAttempt", back_populates="job", cascade="all, delete-orphan")
    pages = relationship("JobPage", back_populates="job", cascade="all, delete-orphan")
    leases = relationship("DeviceLease", back_populates="job", cascade="all, delete-orphan")

    __table_args__ = (
        CheckConstraint("copies BETWEEN 1 AND 99", name="ck_print_jobs_copies"),
        CheckConstraint("progress_percent BETWEEN 0 AND 100", name="ck_print_jobs_progress"),
        CheckConstraint(
            "status IN (%s)" % ",".join("'%s'" % state for state in JOB_STATES),
            name="ck_print_jobs_status",
        ),
        Index("ix_print_jobs_user_id", "user_id"),
        Index("ix_print_jobs_device_id", "device_id"),
        Index("ix_print_jobs_document_id", "document_id"),
        Index("ix_print_jobs_status", "status"),
        Index("ix_print_jobs_created_at", "created_at"),
        Index("ix_print_jobs_device_status_created", "device_id", "status", "created_at"),
    )


class JobAttempt(Base):
    __tablename__ = "job_attempts"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    job_id = Column(String(36), ForeignKey("print_jobs.id", ondelete="CASCADE"), nullable=False)
    attempt_number = Column(Integer, nullable=False)
    state = Column(String(40), nullable=False, default="QUEUED")
    started_at = Column(DateTime(timezone=True), nullable=True)
    ended_at = Column(DateTime(timezone=True), nullable=True)
    outcome_detail = Column(String(2000), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)

    job = relationship("PrintJob", back_populates="attempts")

    __table_args__ = (
        Index("ix_job_attempts_job_id", "job_id"),
        Index("uq_job_attempt_number", "job_id", "attempt_number", unique=True),
    )

from __future__ import annotations

from datetime import datetime

from fastapi import HTTPException
from sqlalchemy.orm import Session

from backend.app.core.security import utcnow
from backend.app.models.event import JobEvent
from backend.app.models.job import PrintJob


# Central, declarative job state machine. Every status change must pass through
# transition_job so no endpoint can write an arbitrary status.
VALID_TRANSITIONS: dict[str, set[str]] = {
    "QUEUED": {"ASSIGNED", "CANCELLED"},
    "ASSIGNED": {"DOWNLOADING", "READY", "CANCEL_REQUESTED", "QUEUED", "FAILED", "CANCELLED"},
    "DOWNLOADING": {
        "QUEUED", "READY", "PRINTING", "TRANSMISSION_COMPLETED", "CANCEL_REQUESTED", "FAILED",
        "UNCERTAIN", "NEEDS_RECONCILIATION", "CANCELLED",
    },
    "READY": {
        "PRINTING", "TRANSMISSION_COMPLETED", "CANCEL_REQUESTED", "FAILED", "QUEUED",
        "UNCERTAIN", "NEEDS_RECONCILIATION", "CANCELLED",
    },
    "PRINTING": {
        "TRANSMISSION_COMPLETED", "CANCEL_REQUESTED", "FAILED",
        "UNCERTAIN", "NEEDS_RECONCILIATION",
    },
    "TRANSMISSION_COMPLETED": {"COMPLETED", "UNCERTAIN", "NEEDS_RECONCILIATION", "FAILED"},
    "CANCEL_REQUESTED": {"CANCELLED", "UNCERTAIN", "NEEDS_RECONCILIATION", "FAILED"},
    "FAILED": {"RETRY_PENDING", "NEEDS_RECONCILIATION"},
    "RETRY_PENDING": {"QUEUED", "CANCELLED"},
    "UNCERTAIN": {"NEEDS_RECONCILIATION", "RETRY_PENDING", "COMPLETED", "CANCELLED", "FAILED"},
    "NEEDS_RECONCILIATION": {"RETRY_PENDING", "COMPLETED", "CANCELLED", "UNCERTAIN", "FAILED"},
    "COMPLETED": set(),
    "CANCELLED": set(),
}


# The Android printer app reports its own transport-oriented status names. These
# are mapped onto canonical states so the server never trusts a client status.
ANDROID_STATUS_MAP = {
    "QUEUED": "ASSIGNED",
    "DOWNLOADING": "DOWNLOADING",
    "VALIDATING": "DOWNLOADING",
    "CONVERTING": "DOWNLOADING",
    "PREPARING_DOCUMENT": "READY",
    "RENDERING_BITMAP": "PRINTING",
    "ENCODING_RASTER": "PRINTING",
    "READY": "READY",
    "TRANSMITTING_USB": "PRINTING",
    "FINALIZING": "PRINTING",
    "PRINTER_PROCESSING": "TRANSMISSION_COMPLETED",
    "TRANSFER_RECOVERING": "PRINTING",
    "TRANSFER_ERROR": "UNCERTAIN",
    "DISCONNECTED": "UNCERTAIN",
    "NEEDS_RECOVERY": "NEEDS_RECONCILIATION",
    "NEEDS_ATTENTION": "NEEDS_RECONCILIATION",
    "FAILED": "FAILED",
    "CANCELLED": "CANCELLED",
    "COMPLETED_SUCCESS": "TRANSMISSION_COMPLETED",
}


def canonical_agent_state(value: str) -> str:
    normalized = value.strip().upper()
    return ANDROID_STATUS_MAP.get(normalized, normalized)


def can_transition(current: str, target: str) -> bool:
    return current == target or target in VALID_TRANSITIONS.get(current, set())


def transition_job(
    db: Session,
    job: PrintJob,
    target: str,
    event_type: str,
    message: str,
    *,
    idempotency_key: str | None = None,
    event_data: dict | None = None,
    now: datetime | None = None,
) -> JobEvent:
    target = target.upper()
    if idempotency_key:
        existing = db.query(JobEvent).filter(
            JobEvent.job_id == job.id,
            JobEvent.idempotency_key == idempotency_key,
        ).first()
        if existing:
            return existing
    if not can_transition(job.status, target):
        raise HTTPException(status_code=409, detail=f"Invalid job transition {job.status} -> {target}")
    timestamp = now or utcnow()
    job.status = target
    job.updated_at = timestamp
    if target == "ASSIGNED" and job.assigned_at is None:
        job.assigned_at = timestamp
    if target == "PRINTING" and job.started_at is None:
        job.started_at = timestamp
    if target == "TRANSMISSION_COMPLETED":
        job.transmission_completed_at = timestamp
    if target in {"COMPLETED", "FAILED", "CANCELLED", "UNCERTAIN", "NEEDS_RECONCILIATION"}:
        job.completed_at = timestamp
    event = JobEvent(
        job_id=job.id,
        attempt_number=job.current_attempt,
        event_type=event_type,
        status=target,
        message=message[:1000],
        idempotency_key=idempotency_key,
        event_data=event_data or {},
        created_at=timestamp,
    )
    db.add(event)
    return event

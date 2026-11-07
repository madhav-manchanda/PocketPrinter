from __future__ import annotations

from datetime import timedelta

from fastapi import HTTPException
from sqlalchemy.orm import Session

from backend.app.core.config import settings
from backend.app.core.security import ensure_aware, generate_opaque_token, token_hash, utcnow
from backend.app.models.device import Device
from backend.app.models.job import JobAttempt, PrintJob
from backend.app.models.lease import DeviceLease
from backend.app.services.job_state import transition_job


# States in which a device is considered to own an active print item.
ACTIVE_JOB_STATES = {
    "ASSIGNED", "DOWNLOADING", "READY", "PRINTING",
    "TRANSMISSION_COMPLETED", "CANCEL_REQUESTED",
}


def expire_device_lease(db: Session, device: Device) -> None:
    """Expire an old lease without blindly replaying uncertain print work."""
    now = utcnow()
    lease = (
        db.query(DeviceLease)
        .filter(DeviceLease.device_id == device.id, DeviceLease.state == "ACTIVE")
        .with_for_update()
        .first()
    )
    if not lease or ensure_aware(lease.expires_at) > now:
        return
    job = lease.job
    lease.state = "EXPIRED"
    lease.released_at = now
    device.current_job_id = None
    if job.status in {"ASSIGNED", "DOWNLOADING", "READY"}:
        # Safe: printing never began, so the job may return to the queue.
        transition_job(db, job, "QUEUED", "LEASE_EXPIRED_SAFE", "Lease expired before printing began")
    elif job.status in {"UNCERTAIN", "NEEDS_RECONCILIATION"}:
        # Already awaiting reconciliation; do not attempt invalid transition.
        from backend.app.models.event import JobEvent
        db.add(JobEvent(
            job_id=job.id,
            attempt_number=job.current_attempt,
            event_type="LEASE_EXPIRED_AWAITING_RECONCILIATION",
            status=job.status,
            message="Device lease expired while job was awaiting operator reconciliation",
        ))
    elif job.status not in {"COMPLETED", "CANCELLED", "FAILED"}:
        # Unsafe: the printer may already have received data, so never auto-retry.
        transition_job(
            db,
            job,
            "UNCERTAIN",
            "LEASE_EXPIRED_UNCERTAIN",
            "Agent lease expired after execution could have reached the printer",
        )


def claim_next_job(db: Session, device: Device) -> tuple[PrintJob, str, DeviceLease] | None:
    """Atomically claim the oldest queued job for a device.

    Uses a row lock plus the unique partial index on active leases, so two
    concurrent polls can never claim the same job or double-assign a device.
    """
    # Serialize claims per device. Locking only candidate job rows is not
    # sufficient: two pollers can skip-lock two different queued jobs and
    # race to create competing active leases for the same printer.
    device = (
        db.query(Device)
        .filter(Device.id == device.id)
        .with_for_update()
        .populate_existing()
        .one()
    )
    expire_device_lease(db, device)

    active = db.query(DeviceLease).filter(
        DeviceLease.device_id == device.id,
        DeviceLease.state == "ACTIVE",
        DeviceLease.expires_at > utcnow(),
    ).first()
    if active:
        return None

    query = db.query(PrintJob).filter(
        PrintJob.device_id == device.id,
        PrintJob.status == "QUEUED",
    ).order_by(PrintJob.created_at.asc(), PrintJob.id.asc())
    if db.bind is not None and db.bind.dialect.name == "postgresql":
        query = query.with_for_update(skip_locked=True)
    else:
        query = query.with_for_update()
    job = query.first()
    if not job:
        return None

    transition_job(db, job, "ASSIGNED", "JOB_ASSIGNED", "Job atomically assigned to device")
    attempt = db.query(JobAttempt).filter(
        JobAttempt.job_id == job.id,
        JobAttempt.attempt_number == job.current_attempt,
    ).first()
    if not attempt:
        db.add(JobAttempt(job_id=job.id, attempt_number=job.current_attempt, state="ASSIGNED"))

    raw_token = generate_opaque_token("pp_lease_")
    lease = DeviceLease(
        job_id=job.id,
        device_id=device.id,
        token_hash=token_hash(raw_token),
        expires_at=utcnow() + timedelta(seconds=settings.JOB_LEASE_SECONDS),
    )
    db.add(lease)
    device.current_job_id = job.id
    db.flush()
    return job, raw_token, lease


def require_active_lease(
    db: Session,
    job: PrintJob,
    device: Device,
    lease_token: str | None = None,
) -> DeviceLease:
    lease = db.query(DeviceLease).filter(
        DeviceLease.job_id == job.id,
        DeviceLease.device_id == device.id,
        DeviceLease.state == "ACTIVE",
    ).first()
    if not lease or ensure_aware(lease.expires_at) <= utcnow():
        raise HTTPException(status_code=409, detail="Active job lease is missing or expired")
    if lease_token and lease.token_hash != token_hash(lease_token):
        raise HTTPException(status_code=401, detail="Invalid lease token")
    return lease


def renew_lease(lease: DeviceLease) -> None:
    now = utcnow()
    lease.last_renewed_at = now
    lease.expires_at = now + timedelta(seconds=settings.JOB_LEASE_SECONDS)


def release_lease(device: Device, lease: DeviceLease) -> None:
    lease.state = "RELEASED"
    lease.released_at = utcnow()
    device.current_job_id = None


def find_job_lease(
    db: Session,
    job: PrintJob,
    device: Device,
    lease_token: str | None = None,
) -> DeviceLease | None:
    query = (
        db.query(DeviceLease)
        .filter(DeviceLease.job_id == job.id, DeviceLease.device_id == device.id)
        .order_by(DeviceLease.created_at.desc())
    )
    lease = query.first()
    if lease and lease_token:
        if lease.token_hash != token_hash(lease_token):
            return None
    return lease


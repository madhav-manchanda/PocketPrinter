from __future__ import annotations

from fastapi import APIRouter, Depends, Header, HTTPException, Response
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from backend.app.core.security import utcnow
from backend.app.database.session import get_db
from backend.app.models.device import Device
from backend.app.models.event import JobEvent, JobPage
from backend.app.models.job import PrintJob
from backend.app.services.device_service import authenticate_device
from backend.app.services.job_state import canonical_agent_state, transition_job
from backend.app.services.queue_service import (
    claim_next_job,
    find_job_lease,
    release_lease,
    renew_lease,
    require_active_lease,
)
from backend.app.services.storage import document_storage


router = APIRouter(prefix="/api/device/jobs", tags=["Agent Operations"])


def get_authorized_device(
    x_device_token: str | None = Header(default=None),
    db: Session = Depends(get_db),
) -> Device:
    if not x_device_token:
        raise HTTPException(status_code=401, detail="Missing X-Device-Token authorization header")
    return authenticate_device(db, x_device_token)


class ProgressReportRequest(BaseModel):
    status: str = Field(min_length=2, max_length=80)
    progressPercent: int = Field(default=0, ge=0, le=100)
    message: str | None = Field(default=None, max_length=1000)
    idempotencyKey: str | None = Field(default=None, max_length=200)


class CompleteJobRequest(BaseModel):
    bytesTransferred: int = Field(ge=0)
    completedAt: int | None = None
    idempotencyKey: str | None = Field(default=None, max_length=200)


class FailJobRequest(BaseModel):
    failureReason: str = Field(min_length=1, max_length=2000)
    failedAt: int | None = None
    uncertain: bool = False
    idempotencyKey: str | None = Field(default=None, max_length=200)


class PageEventRequest(BaseModel):
    eventId: str = Field(min_length=8, max_length=200)
    eventType: str = Field(min_length=2, max_length=80)
    documentPageNumber: int = Field(ge=1)
    scheduledPageNumber: int = Field(ge=1)
    copyNumber: int = Field(default=1, ge=1, le=99)
    pageState: str = Field(min_length=2, max_length=80)
    message: str | None = Field(default=None, max_length=1000)
    errorCode: str | None = Field(default=None, max_length=100)


class CancelAckRequest(BaseModel):
    uncertain: bool = False
    message: str | None = Field(default=None, max_length=1000)
    idempotencyKey: str | None = Field(default=None, max_length=200)


def assigned_job(db: Session, device: Device, job_id: str, lock: bool = False) -> PrintJob:
    query = db.query(PrintJob).filter(PrintJob.id == job_id, PrintJob.device_id == device.id)
    if lock:
        query = query.with_for_update()
    job = query.first()
    if not job:
        raise HTTPException(status_code=404, detail="Job not found for this device")
    return job


@router.get("/next")
def get_next_job(
    response: Response,
    device: Device = Depends(get_authorized_device),
    db: Session = Depends(get_db),
):
    claimed = claim_next_job(db, device)
    if not claimed:
        db.commit()
        response.status_code = 204
        return None
    job, raw_lease_token, lease = claimed
    transition_job(db, job, "DOWNLOADING", "DOCUMENT_DOWNLOAD_AVAILABLE", "Original document ready for download")
    db.commit()
    return {
        "jobId": job.id,
        "deviceId": job.device_id,
        "filename": job.filename,
        "fileSize": job.file_size,
        "sha256": job.document.sha256 if job.document else None,
        "mimeType": job.mime_type,
        "pageCount": job.page_count or 1,
        "pageRange": job.page_range,
        "colorMode": job.color_mode,
        "paperSize": job.paper_size,
        "orientation": job.orientation,
        "copies": job.copies,
        "scaling": job.scaling,
        "dpi": getattr(job, "dpi", 300) or 300,
        "quality": getattr(job, "quality", "NORMAL") or "NORMAL",
        "margins": getattr(job, "margins", '{"top":4,"bottom":4,"left":4,"right":4,"unit":"mm"}'),
        "collate": getattr(job, "collate", True),
        "pagesPerSheet": getattr(job, "pages_per_sheet", 1) or 1,
        "printOptions": getattr(job, "print_options", "{}"),
        "status": job.status,
        "createdAt": int(job.created_at.timestamp() * 1000),
        "leaseToken": raw_lease_token,
        "leaseGeneration": lease.generation,
        "leaseExpiresAt": lease.expires_at.isoformat(),
        "apiVersion": 1,
    }


@router.get("/{job_id}/download")
def download_job_file(
    job_id: str,
    x_lease_token: str = Header(...),
    device: Device = Depends(get_authorized_device),
    db: Session = Depends(get_db),
):
    job = assigned_job(db, device, job_id)
    lease = require_active_lease(db, job, device, x_lease_token)
    renew_lease(lease)
    if not job.document:
        raise HTTPException(status_code=503, detail="Job has no migrated document record")
    path = document_storage.path_for(job.document.storage_key)
    if not path.is_file() or path.stat().st_size != job.document.size_bytes:
        raise HTTPException(status_code=503, detail="Stored original document failed integrity checks")
    db.add(JobEvent(
        job_id=job.id,
        attempt_number=job.current_attempt,
        event_type="DOCUMENT_DOWNLOAD_STARTED",
        status=job.status,
        message="Agent started original-document download",
    ))
    db.commit()
    return FileResponse(
        path,
        media_type=job.document.media_type,
        filename=job.document.original_filename,
        headers={"X-Content-SHA256": job.document.sha256, "X-Document-Original": "true"},
    )


@router.post("/{job_id}/lease/renew")
def renew_job_lease(
    job_id: str,
    x_lease_token: str = Header(...),
    device: Device = Depends(get_authorized_device),
    db: Session = Depends(get_db),
):
    job = assigned_job(db, device, job_id)
    lease = require_active_lease(db, job, device, x_lease_token)
    renew_lease(lease)
    db.commit()
    return {
        "status": "ACK",
        "leaseExpiresAt": lease.expires_at.isoformat(),
        "cancelRequested": job.status == "CANCEL_REQUESTED",
    }


@router.post("/{job_id}/progress")
def report_job_progress(
    job_id: str,
    req: ProgressReportRequest,
    x_lease_token: str = Header(...),
    device: Device = Depends(get_authorized_device),
    db: Session = Depends(get_db),
):
    job = assigned_job(db, device, job_id, lock=True)
    if job.status in {"COMPLETED", "CANCELLED", "FAILED", "UNCERTAIN"}:
        return {"status": "ACK", "canonicalState": job.status, "cancelRequested": False, "terminal": True}
    lease = require_active_lease(db, job, device, x_lease_token)
    renew_lease(lease)
    target = canonical_agent_state(req.status)
    # Cancellation is an owner intent, not a state the agent may overwrite
    # with a late READY/PRINTING progress packet. Keep the lease alive and
    # ask the agent to stop; the cancel acknowledgement resolves the outcome.
    if job.status != "CANCEL_REQUESTED":
        transition_job(
            db, job, target, "AGENT_PROGRESS", req.message or f"Agent progress {req.progressPercent}%",
            idempotency_key=req.idempotencyKey,
            event_data={"agentStatus": req.status, "progressPercent": req.progressPercent},
        )
    else:
        transition_job(
            db, job, "CANCEL_REQUESTED", "AGENT_PROGRESS_DURING_CANCEL",
            req.message or "Agent progress received while cancellation is pending",
            idempotency_key=req.idempotencyKey,
            event_data={"agentStatus": req.status, "progressPercent": req.progressPercent},
        )
    job.progress_percent = max(job.progress_percent, req.progressPercent) if job.status == "CANCEL_REQUESTED" else req.progressPercent
    db.commit()
    return {"status": "ACK", "canonicalState": job.status, "cancelRequested": job.status == "CANCEL_REQUESTED"}


@router.post("/{job_id}/pages/events")
def report_page_event(
    job_id: str,
    req: PageEventRequest,
    x_lease_token: str = Header(...),
    device: Device = Depends(get_authorized_device),
    db: Session = Depends(get_db),
):
    job = assigned_job(db, device, job_id, lock=True)
    if job.status in {"COMPLETED", "CANCELLED", "FAILED", "UNCERTAIN"}:
        return {
            "status": "ACK",
            "duplicate": True,
            "terminal": True,
            "message": f"Job is already in terminal state {job.status}",
        }
    lease = require_active_lease(db, job, device, x_lease_token)
    renew_lease(lease)
    existing = db.query(JobEvent).filter(
        JobEvent.job_id == job.id,
        JobEvent.idempotency_key == req.eventId,
    ).first()
    if existing:
        return {"status": "ACK", "duplicate": True, "eventId": existing.id}
    page = db.query(JobPage).filter(
        JobPage.job_id == job.id,
        JobPage.attempt_number == job.current_attempt,
        JobPage.scheduled_page_number == req.scheduledPageNumber,
        JobPage.copy_number == req.copyNumber,
    ).first()
    if not page:
        page = JobPage(
            job_id=job.id,
            attempt_number=job.current_attempt,
            document_page_number=req.documentPageNumber,
            scheduled_page_number=req.scheduledPageNumber,
            copy_number=req.copyNumber,
            idempotency_key=f"page-{job.current_attempt}-{req.scheduledPageNumber}-{req.copyNumber}",
        )
        db.add(page)
        db.flush()
    page.state = req.pageState.upper()
    page.last_update_at = utcnow()
    page.error_code = req.errorCode
    page.error_detail = req.message if req.errorCode else None
    db.add(JobEvent(
        job_id=job.id,
        page_id=page.id,
        attempt_number=job.current_attempt,
        event_type=req.eventType.upper(),
        status=page.state,
        message=req.message or req.eventType,
        idempotency_key=req.eventId,
        event_data={
            "documentPage": req.documentPageNumber,
            "scheduledPage": req.scheduledPageNumber,
            "copy": req.copyNumber,
        },
    ))
    job.scheduled_items = max(job.scheduled_items, req.scheduledPageNumber)
    job.completed_items = db.query(JobPage).filter(
        JobPage.job_id == job.id,
        JobPage.attempt_number == job.current_attempt,
        JobPage.state.in_(["TRANSMISSION_COMPLETED", "COMPLETED"]),
    ).count()
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        return {"status": "ACK", "duplicate": True}
    return {"status": "ACK", "duplicate": False, "pageId": page.id}


@router.post("/{job_id}/complete")
def complete_job(
    job_id: str,
    req: CompleteJobRequest,
    x_lease_token: str = Header(...),
    device: Device = Depends(get_authorized_device),
    db: Session = Depends(get_db),
):
    job = assigned_job(db, device, job_id, lock=True)
    latest_lease = find_job_lease(db, job, device, x_lease_token)
    if not latest_lease:
        raise HTTPException(status_code=401, detail="Invalid lease token")
    if latest_lease.state != "ACTIVE":
        if job.status in {"COMPLETED", "TRANSMISSION_COMPLETED", "UNCERTAIN"}:
            return {
                "status": job.status,
                "jobId": job.id,
                "physicalOutputConfirmed": job.physical_output_confirmed,
                "duplicate": True,
            }
        raise HTTPException(status_code=409, detail="Active job lease is missing or expired")

    lease = latest_lease
    job.bytes_transferred = req.bytesTransferred
    release_lease(device, lease)
    if job.status == "CANCEL_REQUESTED":
        # A late "complete" can race with a cancellation request. Bytes may
        # have reached the printer, so never report a clean cancellation or
        # silently mark the physical output complete.
        transition_job(
            db, job, "UNCERTAIN", "CANCEL_RACED_WITH_COMPLETION",
            "Agent completed transmission while cancellation was pending; operator reconciliation is required",
            idempotency_key=req.idempotencyKey,
            event_data={"bytesTransferred": req.bytesTransferred, "physicalConfirmed": False},
        )
        job.physical_output_confirmed = False
        db.commit()
        return {"status": "UNCERTAIN", "jobId": job.id, "physicalOutputConfirmed": False, "duplicate": False}
    if job.status != "TRANSMISSION_COMPLETED":
        transition_job(
            db, job, "TRANSMISSION_COMPLETED", "JOB_TRANSMISSION_COMPLETED",
            "Agent confirmed all scheduled payload bytes were delivered",
            idempotency_key=req.idempotencyKey,
            event_data={"bytesTransferred": req.bytesTransferred, "physicalConfirmed": False},
        )
    transition_job(
        db, job, "COMPLETED", "JOB_COMPLETED",
        "Agent completed the job protocol; physical paper output was not independently confirmed",
    )
    job.progress_percent = 100
    job.physical_output_confirmed = False
    db.commit()
    return {"status": "COMPLETED", "jobId": job.id, "physicalOutputConfirmed": False, "duplicate": False}


@router.post("/{job_id}/fail")
def fail_job(
    job_id: str,
    req: FailJobRequest,
    x_lease_token: str = Header(...),
    device: Device = Depends(get_authorized_device),
    db: Session = Depends(get_db),
):
    job = assigned_job(db, device, job_id, lock=True)
    latest_lease = find_job_lease(db, job, device, x_lease_token)
    if not latest_lease:
        raise HTTPException(status_code=401, detail="Invalid lease token")
    if latest_lease.state != "ACTIVE":
        if job.status in {"FAILED", "UNCERTAIN", "CANCELLED", "NEEDS_RECONCILIATION"}:
            return {"status": job.status, "jobId": job.id, "duplicate": True}
        raise HTTPException(status_code=409, detail="Active job lease is missing or expired")

    lease = latest_lease
    if job.status == "CANCEL_REQUESTED":
        target = "UNCERTAIN" if req.uncertain or job.completed_items > 0 else "CANCELLED"
    else:
        target = "UNCERTAIN" if req.uncertain or job.completed_items > 0 else "FAILED"
    transition_job(
        db, job, target,
        "JOB_UNCERTAIN" if target == "UNCERTAIN" else (
            "CANCEL_ACKNOWLEDGED" if target == "CANCELLED" else "JOB_FAILED"
        ),
        req.failureReason,
        idempotency_key=req.idempotencyKey,
    )
    job.failure_reason = req.failureReason
    release_lease(device, lease)
    db.commit()
    return {"status": job.status, "jobId": job.id, "duplicate": False}


@router.post("/{job_id}/cancel/ack")
def acknowledge_cancel(
    job_id: str,
    uncertain: bool = False,
    req: CancelAckRequest | None = None,
    x_lease_token: str = Header(...),
    device: Device = Depends(get_authorized_device),
    db: Session = Depends(get_db),
):
    job = assigned_job(db, device, job_id, lock=True)
    is_uncertain = uncertain or (req.uncertain if req else False)
    idempotency_key = req.idempotencyKey if req else None
    reason = (req.message if req and req.message else None) or "Agent acknowledged cancellation"

    latest_lease = find_job_lease(db, job, device, x_lease_token)
    if not latest_lease:
        raise HTTPException(status_code=401, detail="Invalid lease token")
    if latest_lease.state != "ACTIVE":
        if job.status in {"CANCELLED", "UNCERTAIN"}:
            return {"status": job.status, "jobId": job.id, "duplicate": True}
        raise HTTPException(status_code=409, detail="Active job lease is missing or expired")

    lease = latest_lease
    if job.status != "CANCEL_REQUESTED":
        job.cancellation_requested_at = utcnow()

    target = "UNCERTAIN" if is_uncertain or job.completed_items > 0 else "CANCELLED"
    transition_job(
        db, job, target, "CANCEL_ACKNOWLEDGED", reason,
        idempotency_key=idempotency_key,
    )
    release_lease(device, lease)
    db.commit()
    return {"status": job.status, "jobId": job.id, "duplicate": False}

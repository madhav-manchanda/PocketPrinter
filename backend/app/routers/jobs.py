from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field
from pypdf import PdfReader
from sqlalchemy.orm import Session

from backend.app.core.config import settings
from backend.app.core.security import create_ws_ticket, utcnow
from backend.app.database.session import get_db
from backend.app.models.device import Device
from backend.app.models.document import Document
from backend.app.models.event import JobEvent
from backend.app.models.job import JobAttempt, PrintJob
from backend.app.routers.connect import get_current_client_device
from backend.app.services.device_manager import device_manager
from backend.app.services.job_state import transition_job
from backend.app.services.storage import document_storage


router = APIRouter(prefix="/api/jobs", tags=["Print Jobs"])


class ReconcileRequest(BaseModel):
    outcome: str = Field(pattern="^(printed|not_printed|cancel)$")
    note: str | None = Field(default=None, max_length=1000)


def owned_job(db: Session, device_id: str, job_id: str, lock: bool = False) -> PrintJob:
    query = db.query(PrintJob).filter(PrintJob.id == job_id, PrintJob.device_id == device_id)
    if lock:
        query = query.with_for_update()
    job = query.first()
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    return job


def serialize_job(job: PrintJob) -> dict:
    return {
        "id": job.id,
        "filename": job.filename,
        "documentId": job.document_id,
        "deviceId": job.device_id,
        "deviceName": job.device.device_name if job.device else None,
        "fileSize": job.file_size,
        "sha256": job.document.sha256 if job.document else None,
        "mimeType": job.mime_type,
        "pageCount": job.page_count,
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
        "progressPercent": job.progress_percent,
        "scheduledItems": job.scheduled_items,
        "completedItems": job.completed_items,
        "attempt": job.current_attempt,
        "bytesTransferred": job.bytes_transferred,
        "physicalOutputConfirmed": job.physical_output_confirmed,
        "failureReason": job.failure_reason,
        "createdAt": job.created_at.isoformat(),
        "assignedAt": job.assigned_at.isoformat() if job.assigned_at else None,
        "transmissionCompletedAt": (
            job.transmission_completed_at.isoformat() if job.transmission_completed_at else None
        ),
        "completedAt": job.completed_at.isoformat() if job.completed_at else None,
    }


async def persist_document(upload: UploadFile, device: Device, db: Session) -> Document:
    stored = await document_storage.store_upload(upload, device.id)
    page_count = None
    try:
        if stored.extension == "pdf":
            try:
                reader = PdfReader(str(stored.path), strict=True)
                if reader.is_encrypted:
                    raise ValueError("Encrypted PDFs are not supported")
                page_count = len(reader.pages)
            except Exception as exc:
                raise HTTPException(
                    status_code=400,
                    detail="Uploaded PDF is invalid, encrypted, or unreadable",
                ) from exc
            if page_count < 1:
                raise HTTPException(status_code=400, detail="PDF contains no pages")
        document = Document(
            device_id=device.id,
            user_id=device.user_id,
            original_filename=stored.original_filename,
            storage_key=stored.storage_key,
            media_type=stored.media_type,
            extension=stored.extension,
            size_bytes=stored.size_bytes,
            sha256=stored.sha256,
            page_count=page_count,
        )
        db.add(document)
        db.flush()
        return document
    except Exception:
        document_storage.delete(stored.storage_key)
        raise


@router.post("", status_code=201)
async def create_print_job(
    file: UploadFile = File(...),
    deviceId: str | None = Form(None),
    colorMode: str = Form("MONOCHROME_1BIT"),
    paperSize: str = Form("A4"),
    orientation: str = Form("PORTRAIT"),
    copies: int = Form(1, ge=1, le=99),
    pageRange: str = Form("", max_length=200),
    scaling: str = Form("FIT_TO_PAGE"),
    dpi: int = Form(300),
    quality: str = Form("NORMAL"),
    margins: str = Form('{"top":4,"bottom":4,"left":4,"right":4,"unit":"mm"}'),
    collate: bool = Form(True),
    pagesPerSheet: int = Form(1),
    printOptions: str = Form("{}"),
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    if deviceId and deviceId != device.id:
        raise HTTPException(status_code=403, detail="Client is not paired with this device")
    if device.is_revoked or device.is_disabled:
        raise HTTPException(status_code=403, detail="Target printer device is revoked or disabled")

    document: Document | None = None
    try:
        document = await persist_document(file, device, db)
        job = PrintJob(
            device_id=device.id,
            user_id=device.user_id,
            document_id=document.id,
            filename=document.original_filename,
            original_path=document.storage_key,
            converted_pdf_path=None,
            file_size=document.size_bytes,
            mime_type=document.media_type,
            page_count=document.page_count,
            page_range=pageRange.strip(),
            color_mode=colorMode.upper(),
            paper_size=paperSize.upper(),
            orientation=orientation.upper(),
            copies=copies,
            scaling=scaling.upper(),
            dpi=dpi,
            quality=quality.upper(),
            margins=margins,
            collate=collate,
            pages_per_sheet=pagesPerSheet,
            print_options=printOptions,
            status="QUEUED",
        )
        db.add(job)
        db.flush()
        db.add(JobAttempt(job_id=job.id, attempt_number=1, state="QUEUED"))
        db.add(JobEvent(
            job_id=job.id,
            event_type="JOB_QUEUED",
            status="QUEUED",
            message="Original document stored and job queued",
            event_data={"sha256": document.sha256, "sizeBytes": document.size_bytes},
        ))
        db.commit()
    except Exception:
        db.rollback()
        if document:
            document_storage.delete(document.storage_key)
        raise
    await device_manager.notify_new_job(device.id, job.id)
    return {
        "jobId": job.id,
        "status": job.status,
        "filename": job.filename,
        "pageCount": job.page_count,
        "sha256": document.sha256,
        "createdAt": job.created_at.isoformat(),
    }


@router.get("")
def list_jobs(
    limit: int = 50,
    offset: int = 0,
    status_filter: str | None = None,
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    limit = min(max(limit, 1), 100)
    offset = max(offset, 0)
    query = db.query(PrintJob).filter(PrintJob.device_id == device.id)
    if status_filter:
        query = query.filter(PrintJob.status == status_filter.upper())
    jobs = query.order_by(PrintJob.created_at.desc(), PrintJob.id.desc()).offset(offset).limit(limit).all()
    return {"items": [serialize_job(job) for job in jobs], "limit": limit, "offset": offset}


@router.get("/{job_id}")
def get_job_detail(
    job_id: str,
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    job = owned_job(db, device.id, job_id)
    result = serialize_job(job)
    result["events"] = [
        {"id": event.id, "type": event.event_type, "status": event.status,
         "message": event.message, "data": event.event_data,
         "timestamp": event.created_at.isoformat()}
        for event in sorted(job.events, key=lambda value: value.created_at)
    ]
    result["pages"] = [
        {"id": page.id, "documentPage": page.document_page_number,
         "scheduledPage": page.scheduled_page_number, "copy": page.copy_number,
         "state": page.state, "attempt": page.attempt_number,
         "updatedAt": page.last_update_at.isoformat(), "error": page.error_detail}
        for page in sorted(job.pages, key=lambda value: (value.attempt_number, value.scheduled_page_number))
    ]
    return result


@router.post("/{job_id}/ws-ticket")
def job_ws_ticket(
    job_id: str,
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    owned_job(db, device.id, job_id)
    return {"ticket": create_ws_ticket(device.id, f"device:{device.id}"), "expiresIn": settings.WS_TICKET_EXPIRE_SECONDS}


@router.post("/{job_id}/cancel")
def cancel_job(
    job_id: str,
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    job = owned_job(db, device.id, job_id, lock=True)
    if job.status in {"QUEUED", "RETRY_PENDING"}:
        transition_job(db, job, "CANCELLED", "JOB_CANCELLED", "Queued job cancelled by client")
    elif job.status in {"ASSIGNED", "DOWNLOADING", "READY", "PRINTING", "TRANSMISSION_COMPLETED"}:
        job.cancellation_requested_at = utcnow()
        transition_job(
            db, job, "CANCEL_REQUESTED", "CANCEL_REQUESTED",
            "Cancellation requested; the agent must acknowledge before the outcome is final",
        )
    elif job.status == "CANCEL_REQUESTED":
        pass
    else:
        raise HTTPException(
            status_code=409,
            detail=f"Job in {job.status} state must be reconciled or retried through its dedicated action",
        )
    db.commit()
    return {"status": job.status, "jobId": job.id}


@router.post("/{job_id}/retry")
async def retry_job(
    job_id: str,
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    job = owned_job(db, device.id, job_id, lock=True)
    if job.status not in {"FAILED", "RETRY_PENDING"}:
        raise HTTPException(status_code=409, detail="Uncertain jobs require reconciliation before retry")
    if job.status != "RETRY_PENDING":
        transition_job(db, job, "RETRY_PENDING", "RETRY_AUTHORIZED", "Client authorized a new attempt")
    job.current_attempt += 1
    job.progress_percent = 0
    job.completed_items = 0
    job.bytes_transferred = 0
    job.failure_reason = None
    db.add(JobAttempt(job_id=job.id, attempt_number=job.current_attempt, state="QUEUED"))
    transition_job(db, job, "QUEUED", "JOB_REQUEUED", "New attempt queued")
    db.commit()
    await device_manager.notify_new_job(job.device_id, job.id)
    return {"status": job.status, "jobId": job.id, "attempt": job.current_attempt}


@router.post("/{job_id}/reconcile")
def reconcile_job(
    job_id: str,
    req: ReconcileRequest,
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    job = owned_job(db, device.id, job_id, lock=True)
    if job.status not in {"UNCERTAIN", "NEEDS_RECONCILIATION"}:
        raise HTTPException(status_code=409, detail="Job does not require reconciliation")
    note = req.note or "No operator note"
    if req.outcome == "printed":
        transition_job(db, job, "COMPLETED", "JOB_RECONCILED_PRINTED", note)
        job.physical_output_confirmed = True
    elif req.outcome == "not_printed":
        transition_job(db, job, "RETRY_PENDING", "JOB_RECONCILED_NOT_PRINTED", note)
    else:
        transition_job(db, job, "CANCELLED", "JOB_RECONCILED_CANCELLED", note)
    db.commit()
    return {"jobId": job.id, "status": job.status, "physicalOutputConfirmed": job.physical_output_confirmed}

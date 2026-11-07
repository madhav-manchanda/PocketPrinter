from __future__ import annotations

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from backend.app.core.security import utcnow
from backend.app.database.session import get_db
from backend.app.models.device import Device
from backend.app.models.document import Document
from backend.app.models.job import PrintJob
from backend.app.routers.connect import get_current_client_device
from backend.app.services.storage import document_storage


router = APIRouter(prefix="/api/documents", tags=["Documents"])


def _serialize(document: Document) -> dict:
    return {
        "id": document.id,
        "filename": document.original_filename,
        "mediaType": document.media_type,
        "extension": document.extension,
        "sizeBytes": document.size_bytes,
        "sha256": document.sha256,
        "pageCount": document.page_count,
        "createdAt": document.created_at.isoformat(),
    }


async def persist_document(upload: UploadFile, device: Device, db: Session) -> Document:
    stored = await document_storage.store_upload(upload, device.id)
    try:
        document = Document(
            device_id=device.id,
            user_id=device.user_id,
            original_filename=stored.original_filename,
            storage_key=stored.storage_key,
            media_type=stored.media_type,
            extension=stored.extension,
            size_bytes=stored.size_bytes,
            sha256=stored.sha256,
        )
        db.add(document)
        db.flush()
        return document
    except Exception:
        document_storage.delete(stored.storage_key)
        raise


@router.post("", status_code=201)
async def upload_document(
    file: UploadFile = File(...),
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    document = await persist_document(file, device, db)
    db.commit()
    return _serialize(document)


@router.get("")
def list_documents(
    limit: int = 50,
    offset: int = 0,
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    limit = min(max(limit, 1), 100)
    offset = max(offset, 0)
    documents = db.query(Document).filter(
        Document.device_id == device.id,
        Document.deleted_at.is_(None),
    ).order_by(Document.created_at.desc()).offset(offset).limit(limit).all()
    return {"items": [_serialize(document) for document in documents], "limit": limit, "offset": offset}


@router.get("/{document_id}")
def get_document(
    document_id: str,
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    document = db.query(Document).filter(
        Document.id == document_id,
        Document.device_id == device.id,
        Document.deleted_at.is_(None),
    ).first()
    if not document:
        raise HTTPException(status_code=404, detail="Document not found")
    return _serialize(document)


@router.get("/{document_id}/download")
def download_document(
    document_id: str,
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    document = db.query(Document).filter(
        Document.id == document_id,
        Document.device_id == device.id,
        Document.deleted_at.is_(None),
    ).first()
    if not document:
        raise HTTPException(status_code=404, detail="Document not found")
    path = document_storage.path_for(document.storage_key)
    if not path.is_file() or path.stat().st_size != document.size_bytes:
        raise HTTPException(status_code=503, detail="Stored document integrity check failed")
    return FileResponse(
        path,
        media_type=document.media_type,
        filename=document.original_filename,
        headers={"X-Content-SHA256": document.sha256},
    )


@router.delete("/{document_id}", status_code=204)
def delete_document(
    document_id: str,
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    document = (
        db.query(Document)
        .filter(
            Document.id == document_id,
            Document.device_id == device.id,
            Document.deleted_at.is_(None),
        )
        .with_for_update()
        .first()
    )
    if not document:
        raise HTTPException(status_code=404, detail="Document not found")
    active = db.query(PrintJob).filter(
        PrintJob.document_id == document.id,
        PrintJob.status.notin_(["COMPLETED", "CANCELLED", "FAILED"]),
    ).first()
    if active:
        raise HTTPException(status_code=409, detail="Document is referenced by an active or recoverable job")
    document.deleted_at = utcnow()
    db.commit()
    document_storage.delete(document.storage_key)

from __future__ import annotations

import hashlib
import os
import shutil
import uuid
from dataclasses import dataclass
from pathlib import Path

import aiofiles
from fastapi import HTTPException, UploadFile

from backend.app.core.config import settings


# First bytes that must appear for a given extension. Office formats are ZIP
# containers (PK\x03\x04); PDF/PNG/JPEG/BMP/TIFF have distinct magic numbers.
SIGNATURES: dict[str, tuple[bytes, ...]] = {
    "pdf": (b"%PDF-",),
    "png": (b"\x89PNG\r\n\x1a\n",),
    "jpg": (b"\xff\xd8\xff",),
    "jpeg": (b"\xff\xd8\xff",),
    "bmp": (b"BM",),
    "webp": (b"RIFF",),
    "tif": (b"II*\x00", b"MM\x00*"),
    "tiff": (b"II*\x00", b"MM\x00*"),
    "docx": (b"PK\x03\x04",),
    "xlsx": (b"PK\x03\x04",),
    "pptx": (b"PK\x03\x04",),
}


@dataclass(frozen=True)
class StoredUpload:
    storage_key: str
    path: Path
    size_bytes: int
    sha256: str
    extension: str
    media_type: str
    original_filename: str


class DocumentStorage:
    """Streamed, integrity-checked storage for the user's original documents."""

    def __init__(self) -> None:
        settings.ensure_storage()

    async def store_upload(self, upload: UploadFile, user_id: str) -> StoredUpload:
        filename = Path(upload.filename or "document").name
        extension = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
        if extension not in settings.ALLOWED_EXTENSIONS:
            raise HTTPException(status_code=400, detail=f"Unsupported file extension '.{extension}'")

        free_space = shutil.disk_usage(settings.STORAGE_ROOT).free
        if free_space < settings.MIN_FREE_STORAGE_BYTES:
            raise HTTPException(status_code=507, detail="Document storage is below its free-space threshold")

        object_id = str(uuid.uuid4())
        storage_key = f"{user_id}/{object_id}.{extension}"
        final_path = self.path_for(storage_key)
        final_path.parent.mkdir(parents=True, exist_ok=True)
        temp_path = settings.temp_dir / f"{object_id}.part"

        digest = hashlib.sha256()
        total = 0
        header = bytearray()
        try:
            async with aiofiles.open(temp_path, "xb") as output:
                while chunk := await upload.read(settings.UPLOAD_CHUNK_SIZE):
                    total += len(chunk)
                    if total > settings.MAX_UPLOAD_SIZE:
                        raise HTTPException(status_code=413, detail="Upload exceeds configured maximum size")
                    if len(header) < 32:
                        header.extend(chunk[: 32 - len(header)])
                    digest.update(chunk)
                    await output.write(chunk)
                await output.flush()
            if total == 0:
                raise HTTPException(status_code=400, detail="Uploaded document is empty")
            self._validate_signature(extension, bytes(header))
            os.replace(temp_path, final_path)
        except Exception:
            temp_path.unlink(missing_ok=True)
            raise

        return StoredUpload(
            storage_key=storage_key,
            path=final_path,
            size_bytes=total,
            sha256=digest.hexdigest(),
            extension=extension,
            media_type=upload.content_type or "application/octet-stream",
            original_filename=filename,
        )

    def path_for(self, storage_key: str) -> Path:
        candidate = (settings.documents_dir / storage_key).resolve()
        root = settings.documents_dir.resolve()
        if root not in candidate.parents:
            raise HTTPException(status_code=400, detail="Invalid storage reference")
        return candidate

    def delete(self, storage_key: str) -> None:
        self.path_for(storage_key).unlink(missing_ok=True)

    @staticmethod
    def _validate_signature(extension: str, header: bytes) -> None:
        signatures = SIGNATURES.get(extension)
        if signatures and not any(header.startswith(signature) for signature in signatures):
            raise HTTPException(status_code=400, detail="File signature does not match its extension")
        if extension == "webp" and (len(header) < 12 or header[8:12] != b"WEBP"):
            raise HTTPException(status_code=400, detail="Invalid WebP signature")
        if extension in {"txt", "csv", "rtf"} and b"\x00" in header:
            raise HTTPException(status_code=400, detail="Text upload contains binary null bytes")


document_storage = DocumentStorage()

from __future__ import annotations

import uuid

from sqlalchemy import Boolean, Column, DateTime, ForeignKey, Index, Integer, String
from sqlalchemy.orm import relationship

from backend.app.core.security import utcnow
from backend.app.database.session import Base


class Document(Base):
    __tablename__ = "documents"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    device_id = Column(String(128), ForeignKey("devices.id", ondelete="CASCADE"), nullable=True)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="RESTRICT"), nullable=True)
    original_filename = Column(String(500), nullable=False)
    storage_key = Column(String(200), nullable=False, unique=True)
    media_type = Column(String(200), nullable=False)
    extension = Column(String(20), nullable=False)
    size_bytes = Column(Integer, nullable=False)
    sha256 = Column(String(64), nullable=False)
    page_count = Column(Integer, nullable=True)
    is_complete = Column(Boolean, nullable=False, default=True)
    deleted_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)

    owner = relationship("User", back_populates="documents")
    device = relationship("Device", back_populates="documents")
    jobs = relationship("PrintJob", back_populates="document")

    __table_args__ = (
        Index("ix_documents_device_id", "device_id"),
        Index("ix_documents_user_id", "user_id"),
        Index("ix_documents_sha256", "sha256"),
    )

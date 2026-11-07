from __future__ import annotations

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from backend.app.core.config import settings
from backend.app.core.security import create_ws_ticket, ensure_aware, utcnow
from backend.app.database.session import get_db
from backend.app.models.device import Device
from backend.app.models.job import PrintJob
from backend.app.services.device_service import (
    authenticate_client_session,
    create_client_session,
    revoke_client_session,
)

router = APIRouter(tags=["Client Connection"])


class ConnectRequest(BaseModel):
    password: str = Field(min_length=1, max_length=256)
    deviceId: str | None = Field(default=None, max_length=128)


def get_current_client_device(
    authorization: str | None = Header(default=None),
    x_client_token: str | None = Header(default=None),
    db: Session = Depends(get_db),
) -> Device:
    token = x_client_token
    if not token and authorization:
        if authorization.startswith("Bearer "):
            token = authorization[7:].strip()
        else:
            token = authorization.strip()

    if not token:
        raise HTTPException(status_code=401, detail="Missing client authorization token")

    # If legacy user JWT was sent, handle backward compatibility
    if token.startswith("ey"):
        from backend.app.core.security import decode_access_token
        from backend.app.models.user import User
        payload = decode_access_token(token)
        if payload and payload.get("sub"):
            user = db.query(User).filter(User.id == payload["sub"]).first()
            if user:
                device = db.query(Device).filter(Device.user_id == user.id).first()
                if device:
                    return device

    return authenticate_client_session(db, token)


@router.post("/api/connect")
def connect_client(
    req: ConnectRequest,
    request: Request,
    db: Session = Depends(get_db),
):
    """Authenticates a user-side client with the printer password.

    No email or account is required. The password matches the printer configured
    on the printer-side Android app. Returns a revocable client token.
    """
    client_ip = request.client.host if request.client else None
    user_agent = request.headers.get("user-agent")

    device, client_token, session = create_client_session(
        db=db,
        password=req.password,
        device_id=req.deviceId,
        client_ip=client_ip,
        user_agent=user_agent,
    )

    now = utcnow()
    is_online = bool(
        device.last_heartbeat and
        (now - ensure_aware(device.last_heartbeat)).total_seconds() < settings.DEVICE_ONLINE_TIMEOUT_SECONDS and
        not device.is_revoked and not device.is_disabled
    )

    return {
        "status": "CONNECTED",
        "clientToken": client_token,
        "expiresAt": session.expires_at.isoformat(),
        "device": {
            "id": device.id,
            "name": device.device_name,
            "printerModel": device.printer_model,
            "vidPid": device.vid_pid,
            "status": "ONLINE" if is_online else "OFFLINE",
            "isOnline": is_online,
            "lastHeartbeat": device.last_heartbeat.isoformat() if device.last_heartbeat else None,
        },
    }


@router.get("/api/connect/device")
def get_connected_device_status(
    device: Device = Depends(get_current_client_device),
    db: Session = Depends(get_db),
):
    now = utcnow()
    is_online = bool(
        device.last_heartbeat and
        (now - ensure_aware(device.last_heartbeat)).total_seconds() < settings.DEVICE_ONLINE_TIMEOUT_SECONDS and
        not device.is_revoked and not device.is_disabled
    )
    pending_count = db.query(PrintJob).filter(
        PrintJob.device_id == device.id,
        PrintJob.status.in_(["QUEUED", "ASSIGNED", "DOWNLOADING", "READY"]),
    ).count()

    return {
        "id": device.id,
        "name": device.device_name,
        "printerModel": device.printer_model,
        "vidPid": device.vid_pid,
        "status": "ONLINE" if is_online else "OFFLINE",
        "isOnline": is_online,
        "lastHeartbeat": device.last_heartbeat.isoformat() if device.last_heartbeat else None,
        "pendingJobsCount": pending_count,
    }


@router.post("/api/connect/ws-ticket")
def client_ws_ticket(
    device: Device = Depends(get_current_client_device),
):
    """Issues a short-lived scoped ticket for real-time WebSocket device event streaming."""
    return {
        "ticket": create_ws_ticket(device.id, f"device:{device.id}"),
        "deviceId": device.id,
        "expiresIn": settings.WS_TICKET_EXPIRE_SECONDS,
    }


@router.post("/api/disconnect")
def disconnect_client(
    authorization: str | None = Header(default=None),
    x_client_token: str | None = Header(default=None),
    db: Session = Depends(get_db),
):
    token = x_client_token or authorization
    if token:
        revoke_client_session(db, token)
    return {"status": "DISCONNECTED"}

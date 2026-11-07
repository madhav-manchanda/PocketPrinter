from __future__ import annotations

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from backend.app.core.config import settings
from backend.app.core.security import create_ws_ticket, ensure_aware, get_password_hash, token_hash, utcnow, verify_password
from backend.app.database.session import get_db
from backend.app.models.device import Device, PairingSession
from backend.app.models.job import PrintJob
from backend.app.services.device_service import (
    authenticate_device,
    consume_pairing_code,
    create_pairing_session,
    issue_device_credential,
    pair_or_register_device,
    revoke_device_credentials,
)

router = APIRouter(prefix="/api/devices", tags=["Devices"])


class PairDeviceRequest(BaseModel):
    deviceId: str = Field(min_length=4, max_length=128)
    deviceName: str = Field(default="PocketPrint Node", max_length=200)
    devicePassword: str = Field(min_length=4, max_length=256)
    printerModel: str = Field(default="HP Ink Tank 310 series", max_length=200)
    vidPid: str = Field(default="03F0:1053", max_length=32)
    appVersion: str = Field(default="1.0.0", max_length=64)


class PasswordChangeRequest(BaseModel):
    oldPassword: str = Field(min_length=1, max_length=256)
    newPassword: str = Field(min_length=6, max_length=256)


class HeartbeatRequest(BaseModel):
    isPrinterConnected: bool
    printerStatus: str = Field(default="ONLINE", max_length=64)
    activeProfile: str = Field(default="HPLIP Pcl3Gui2 Default", max_length=200)
    timestamp: int | None = None


@router.post("/pairing-sessions", status_code=201)
def new_pairing_session(authorization: str | None = Header(default=None), db: Session = Depends(get_db)):
    session, code = create_pairing_session(db)
    db.commit()
    return {
        "pairingSessionId": session.id,
        "pairingCode": code,
        "expiresAt": session.expires_at.isoformat(),
        "singleUse": True,
    }


@router.post("/pair")
@router.post("/register")
def pair_device(req: PairDeviceRequest, db: Session = Depends(get_db)):
    # 1. Check if an active one-time pairing code was used (legacy fallback)
    pairing = db.query(PairingSession).filter(
        PairingSession.code_hash == token_hash(req.devicePassword),
        PairingSession.used_at.is_(None),
    ).first()

    if pairing:
        device = db.query(Device).filter(Device.id == req.deviceId).with_for_update().first()
        if device:
            if device.is_revoked or device.is_disabled:
                raise HTTPException(status_code=403, detail="Device is revoked or disabled")
            if device.user_id and pairing.user_id and pairing.user_id != device.user_id:
                raise HTTPException(status_code=403, detail="Device ownership transfer requires an authorized operation")
            consume_pairing_code(db, req.devicePassword, device.id)
        else:
            consume_pairing_code(db, req.devicePassword, req.deviceId)
            device = Device(
                id=req.deviceId,
                user_id=pairing.user_id,
                device_name=req.deviceName,
                printer_model=req.printerModel,
                vid_pid=req.vidPid,
                app_version=req.appVersion,
                status="ONLINE",
                is_online=True,
                last_heartbeat=utcnow(),
                last_connected_at=utcnow(),
            )
            db.add(device)
            db.flush()

        device.device_name = req.deviceName
        device.printer_model = req.printerModel
        device.vid_pid = req.vidPid
        device.app_version = req.appVersion
        device.status = "ONLINE"
        device.is_online = True
        device.last_heartbeat = utcnow()
        device.last_connected_at = utcnow()
        token = issue_device_credential(db, device)
        db.commit()
        return {"status": "REGISTERED", "deviceToken": token, "deviceId": device.id}

    # 2. Modern direct printer password pairing/registration
    device, token = pair_or_register_device(
        db=db,
        device_id=req.deviceId,
        device_name=req.deviceName,
        device_password=req.devicePassword,
        printer_model=req.printerModel,
        vid_pid=req.vidPid,
        app_version=req.appVersion,
    )
    return {"status": "REGISTERED", "deviceToken": token, "deviceId": device.id}


@router.post("/{device_id}/password/change")
def change_printer_password(
    device_id: str,
    req: PasswordChangeRequest,
    x_device_token: str | None = Header(default=None),
    db: Session = Depends(get_db),
):
    device = db.query(Device).filter(Device.id == device_id).with_for_update().first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    if x_device_token:
        auth_dev = authenticate_device(db, x_device_token)
        if auth_dev.id != device.id:
            raise HTTPException(status_code=403, detail="Credential does not belong to this device")
    else:
        if not device.password_hash or not verify_password(req.oldPassword, device.password_hash):
            raise HTTPException(status_code=401, detail="Current printer password is incorrect")

    device.password_hash = get_password_hash(req.newPassword)
    # Invalidate existing user-side sessions on password change
    now = utcnow()
    for s in device.client_sessions:
        if s.revoked_at is None:
            s.revoked_at = now
    db.commit()
    return {"status": "PASSWORD_CHANGED", "deviceId": device.id}


@router.post("/{device_id}/heartbeat")
def heartbeat(
    device_id: str,
    req: HeartbeatRequest,
    x_device_token: str | None = Header(default=None),
    db: Session = Depends(get_db),
):
    if not x_device_token:
        raise HTTPException(status_code=401, detail="Missing X-Device-Token header")
    device = authenticate_device(db, x_device_token)
    if device.id != device_id:
        raise HTTPException(status_code=403, detail="Credential does not belong to this device")
    now = utcnow()
    device.is_online = True
    device.status = "ONLINE"
    device.last_heartbeat = now
    device.last_connected_at = now
    device.active_profile = req.activeProfile
    pending_count = db.query(PrintJob).filter(
        PrintJob.device_id == device.id,
        PrintJob.status.in_(["QUEUED", "ASSIGNED", "DOWNLOADING", "READY"]),
    ).count()
    db.commit()
    return {"status": "ACK", "pendingJobsCount": pending_count, "serverTime": now.isoformat()}


@router.get("")
def list_devices(
    authorization: str | None = Header(default=None),
    x_client_token: str | None = Header(default=None),
    db: Session = Depends(get_db),
):
    now = utcnow()
    token = x_client_token or (authorization[7:].strip() if authorization and authorization.startswith("Bearer ") else authorization)

    query = db.query(Device).filter(Device.is_revoked.is_(False))
    # If legacy user JWT is present, filter by user
    if token and token.startswith("ey"):
        from backend.app.core.security import decode_access_token
        payload = decode_access_token(token)
        if payload and payload.get("sub"):
            query = query.filter(Device.user_id == payload["sub"])

    devices = query.order_by(Device.created_at.desc()).all()
    return [
        {
            "id": device.id,
            "name": device.device_name,
            "status": device.status,
            "isOnline": bool(
                device.last_heartbeat and
                (now - ensure_aware(device.last_heartbeat)).total_seconds()
                < settings.DEVICE_ONLINE_TIMEOUT_SECONDS and
                not device.is_revoked and not device.is_disabled
            ),
            "lastHeartbeat": device.last_heartbeat.isoformat() if device.last_heartbeat else None,
            "printerModel": device.printer_model,
            "vidPid": device.vid_pid,
            "activeProfile": device.active_profile,
            "appVersion": device.app_version,
            "isRevoked": device.is_revoked,
            "currentJobId": device.current_job_id,
            "createdAt": device.created_at.isoformat(),
        }
        for device in devices
    ]


@router.get("/{device_id}")
def get_device(device_id: str, db: Session = Depends(get_db)):
    device = db.query(Device).filter(Device.id == device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    now = utcnow()
    is_online = bool(
        device.last_heartbeat and
        (now - ensure_aware(device.last_heartbeat)).total_seconds()
        < settings.DEVICE_ONLINE_TIMEOUT_SECONDS and
        not device.is_revoked and not device.is_disabled
    )
    return {
        "id": device.id,
        "name": device.device_name,
        "status": device.status,
        "isOnline": is_online,
        "lastHeartbeat": device.last_heartbeat.isoformat() if device.last_heartbeat else None,
        "printerModel": device.printer_model,
        "vidPid": device.vid_pid,
        "currentJobId": device.current_job_id,
    }


@router.post("/{device_id}/revoke")
def revoke_device(
    device_id: str,
    x_device_token: str | None = Header(default=None),
    authorization: str | None = Header(default=None),
    x_client_token: str | None = Header(default=None),
    db: Session = Depends(get_db),
):
    device = db.query(Device).filter(Device.id == device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    token = x_client_token or (authorization[7:].strip() if authorization and authorization.startswith("Bearer ") else authorization)
    if token and token.startswith("ey"):
        from backend.app.core.security import decode_access_token
        payload = decode_access_token(token)
        if payload and payload.get("sub"):
            if device.user_id and device.user_id != payload["sub"]:
                raise HTTPException(status_code=404, detail="Device not found")

    revoke_device_credentials(device)
    db.commit()
    return {"status": "REVOKED", "deviceId": device.id}


@router.post("/{device_id}/ws-ticket")
def device_ws_ticket(
    device_id: str,
    x_device_token: str | None = Header(default=None),
    db: Session = Depends(get_db),
):
    if not x_device_token:
        raise HTTPException(status_code=401, detail="Missing X-Device-Token header")
    device = authenticate_device(db, x_device_token)
    if device.id != device_id:
        raise HTTPException(status_code=403, detail="Credential does not belong to this device")
    return {"ticket": create_ws_ticket(device.id, f"device:{device.id}"), "expiresIn": settings.WS_TICKET_EXPIRE_SECONDS}

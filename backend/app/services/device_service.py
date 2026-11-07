from __future__ import annotations

from datetime import timedelta

from fastapi import HTTPException
from sqlalchemy.orm import Session

from backend.app.core.config import settings
from backend.app.core.security import (
    ensure_aware,
    generate_device_token,
    generate_opaque_token,
    generate_pairing_code,
    get_password_hash,
    token_hash,
    utcnow,
    verify_password,
)
from backend.app.models.device import ClientSession, Device, DeviceCredential, PairingSession


def pair_or_register_device(
    db: Session,
    device_id: str,
    device_name: str,
    device_password: str,
    printer_model: str,
    vid_pid: str,
    app_version: str = "1.0.0",
) -> tuple[Device, str]:
    """Registers or re-authenticates a printer node using its printer password.

    Argon2id hashes the password. No user account is required.
    """
    if len(device_password) < 6:
        raise HTTPException(status_code=400, detail="Printer password must be at least 6 characters")

    device = db.query(Device).filter(Device.id == device_id).with_for_update().first()
    if device:
        if device.is_revoked or device.is_disabled or device.status in {"REVOKED", "DISABLED"}:
            raise HTTPException(status_code=403, detail="Device is revoked or disabled")
        # Mobile app is the authority for the printer password; sync the backend hash to match it
        device.password_hash = get_password_hash(device_password)
    else:
        device = Device(
            id=device_id,
            device_name=device_name,
            password_hash=get_password_hash(device_password),
            printer_model=printer_model,
            vid_pid=vid_pid,
            app_version=app_version,
            status="ONLINE",
            is_online=True,
            last_heartbeat=utcnow(),
            last_connected_at=utcnow(),
        )
        db.add(device)
        db.flush()

    # Supersede older offline nodes with the exact same device name to avoid ghost sessions
    db.query(Device).filter(
        Device.id != device.id,
        Device.device_name == device_name,
        Device.is_revoked.is_(False),
    ).update({"is_disabled": True, "is_online": False, "status": "DISABLED"}, synchronize_session=False)

    device.device_name = device_name
    device.printer_model = printer_model
    device.vid_pid = vid_pid
    device.app_version = app_version
    device.status = "ONLINE"
    device.is_online = True
    device.last_heartbeat = utcnow()
    device.last_connected_at = utcnow()

    token = issue_device_credential(db, device)
    db.commit()
    return device, token


def create_client_session(
    db: Session,
    password: str,
    device_id: str | None = None,
    client_ip: str | None = None,
    user_agent: str | None = None,
) -> tuple[Device, str, ClientSession]:
    """Authenticates a user-side client with the printer password.

    Identifies the target printer by password verification. Returns an authenticated
    revocable client session token scoped strictly to that device.
    """
    if not password:
        raise HTTPException(status_code=400, detail="Printer password is required")

    target_device: Device | None = None
    if device_id:
        target_device = db.query(Device).filter(
            Device.id == device_id,
            Device.is_revoked.is_(False),
            Device.is_disabled.is_(False),
        ).first()
        if not target_device or not target_device.password_hash:
            raise HTTPException(status_code=401, detail="Invalid printer password or device not found")
        if not verify_password(password, target_device.password_hash):
            raise HTTPException(status_code=401, detail="Invalid printer password")
    else:
        # Match password against configured active devices, prioritizing active online nodes with freshest heartbeats
        candidates = db.query(Device).filter(
            Device.is_revoked.is_(False),
            Device.is_disabled.is_(False),
            Device.password_hash.isnot(None),
        ).order_by(
            Device.is_online.desc(),
            Device.last_heartbeat.desc().nullslast(),
            Device.created_at.desc(),
        ).all()
        for cand in candidates:
            if cand.password_hash and verify_password(password, cand.password_hash):
                target_device = cand
                break
        if not target_device:
            raise HTTPException(status_code=401, detail="Invalid printer password")

    raw_token = generate_opaque_token("pp_client_")
    now = utcnow()
    session = ClientSession(
        device_id=target_device.id,
        token_hash=token_hash(raw_token),
        created_at=now,
        expires_at=now + timedelta(days=settings.REFRESH_TOKEN_EXPIRE_DAYS),
        client_ip=client_ip,
        user_agent=user_agent,
    )
    db.add(session)
    db.commit()
    return target_device, raw_token, session


def authenticate_client_session(db: Session, token: str) -> Device:
    """Authenticates a client token from Authorization header or cookie.

    Guarantees device-scoped access control without user accounts.
    """
    if not token:
        raise HTTPException(status_code=401, detail="Missing client authorization token")
    clean_token = token
    if token.startswith("Bearer "):
        clean_token = token[7:].strip()

    session = db.query(ClientSession).filter(
        ClientSession.token_hash == token_hash(clean_token),
        ClientSession.revoked_at.is_(None),
    ).first()
    if not session or ensure_aware(session.expires_at) <= utcnow():
        raise HTTPException(status_code=401, detail="Client session is missing, expired, or revoked")

    device = session.device
    if not device or device.is_revoked:
        raise HTTPException(status_code=403, detail="Associated printer device is revoked")

    if device.is_disabled or device.status in {"REVOKED", "DISABLED"}:
        active_replacement = db.query(Device).filter(
            Device.device_name == device.device_name,
            Device.is_revoked.is_(False),
            Device.is_disabled.is_(False),
        ).order_by(
            Device.is_online.desc(),
            Device.last_heartbeat.desc().nullslast(),
            Device.created_at.desc(),
        ).first()
        if active_replacement:
            session.device_id = active_replacement.id
            db.commit()
            return active_replacement
        raise HTTPException(status_code=403, detail="Associated printer device is revoked or disabled")

    session.last_used_at = utcnow()
    return device


def revoke_client_session(db: Session, token: str) -> None:
    if not token:
        return
    clean_token = token[7:].strip() if token.startswith("Bearer ") else token
    session = db.query(ClientSession).filter(ClientSession.token_hash == token_hash(clean_token)).first()
    if session and session.revoked_at is None:
        session.revoked_at = utcnow()
        db.commit()


def create_pairing_session(db: Session, user_id: str | None = None) -> tuple[PairingSession, str]:
    code = generate_pairing_code()
    session = PairingSession(
        user_id=user_id or "legacy_system",
        code_hash=token_hash(code),
        expires_at=utcnow() + timedelta(seconds=settings.PAIRING_CODE_TTL_SECONDS),
    )
    db.add(session)
    db.flush()
    return session, code


def consume_pairing_code(db: Session, code: str, device_id: str) -> PairingSession:
    now = utcnow()
    session = (
        db.query(PairingSession)
        .filter(PairingSession.code_hash == token_hash(code))
        .with_for_update()
        .first()
    )
    if not session or session.used_at is not None or ensure_aware(session.expires_at) <= now:
        raise HTTPException(status_code=401, detail="Invalid or expired pairing code")
    if session.attempts >= settings.PAIRING_MAX_ATTEMPTS:
        raise HTTPException(status_code=429, detail="Pairing attempt limit exceeded")
    session.attempts += 1
    session.used_at = now
    session.paired_device_id = device_id
    return session


def issue_device_credential(db: Session, device: Device) -> str:
    now = utcnow()
    for credential in device.credentials:
        if credential.revoked_at is None:
            credential.revoked_at = now
    token = generate_device_token()
    generation = max((credential.generation for credential in device.credentials), default=0) + 1
    db.add(
        DeviceCredential(
            device_id=device.id,
            token_hash=token_hash(token),
            generation=generation,
        )
    )
    # The legacy plaintext column must never hold a live credential.
    device.device_token = None
    return token


def authenticate_device(db: Session, token: str) -> Device:
    if not token:
        raise HTTPException(status_code=401, detail="Missing device credential")
    credential = db.query(DeviceCredential).filter(
        DeviceCredential.token_hash == token_hash(token),
        DeviceCredential.revoked_at.is_(None),
    ).first()
    if not credential:
        raise HTTPException(status_code=401, detail="Invalid or revoked device credential")
    device = credential.device
    if device is None or device.is_revoked or device.is_disabled or device.status in {"REVOKED", "DISABLED"}:
        raise HTTPException(status_code=403, detail="Device is revoked or disabled")
    credential.last_used_at = utcnow()
    return device


def revoke_device_credentials(device: Device) -> None:
    now = utcnow()
    for credential in device.credentials:
        if credential.revoked_at is None:
            credential.revoked_at = now
    for session in device.client_sessions:
        if session.revoked_at is None:
            session.revoked_at = now
    device.device_token = None
    device.is_revoked = True
    device.is_online = False
    device.status = "REVOKED"

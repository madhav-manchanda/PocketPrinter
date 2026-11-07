from __future__ import annotations

from datetime import timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.security import OAuth2PasswordBearer
from pydantic import BaseModel, EmailStr, Field
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from backend.app.core.security import (
    decode_access_token,
    ensure_aware,
    get_password_hash,
    password_needs_rehash,
    utcnow,
    validate_password,
    verify_password,
)
from backend.app.database.session import get_db
from backend.app.models.user import User, UserSession
from backend.app.services.auth_service import (
    enforce_login_rate_limit,
    issue_session,
    normalized_email,
    record_login_attempt,
    revoke_session,
    rotate_refresh_token,
)


router = APIRouter(prefix="/api/auth", tags=["Authentication"])
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/auth/login")


class UserRegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=12, max_length=128)
    full_name: str | None = Field(default=None, max_length=200)


class UserLoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=128)


class RefreshRequest(BaseModel):
    refresh_token: str = Field(min_length=32, max_length=512)


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int
    user_id: str
    email: str


def client_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def get_current_user(
    token: str = Depends(oauth2_scheme),
    db: Session = Depends(get_db),
) -> User:
    payload = decode_access_token(token)
    if not payload or not payload.get("sub"):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired authentication token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    session_id = payload.get("sid")
    if session_id:
        session = db.query(UserSession).filter(UserSession.id == session_id).first()
        if not session or session.revoked_at or ensure_aware(session.expires_at) <= utcnow():
            raise HTTPException(status_code=401, detail="Session is expired or revoked")
    user = db.query(User).filter(User.id == payload["sub"], User.is_active.is_(True)).first()
    if not user:
        raise HTTPException(status_code=401, detail="User account is unavailable")
    return user


def token_response(user: User, access: str, refresh: str) -> TokenResponse:
    from backend.app.core.config import settings
    return TokenResponse(
        access_token=access,
        refresh_token=refresh,
        expires_in=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        user_id=user.id,
        email=user.email,
    )


@router.post("/register", response_model=TokenResponse, status_code=201)
def register(req: UserRegisterRequest, request: Request, db: Session = Depends(get_db)):
    email = normalized_email(str(req.email))
    try:
        validate_password(req.password)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if db.query(User).filter(User.email == email).first():
        raise HTTPException(status_code=409, detail="An account with this email already exists")
    user = User(email=email, hashed_password=get_password_hash(req.password), full_name=req.full_name)
    db.add(user)
    try:
        db.flush()
        access, refresh, _ = issue_session(db, user, client_ip(request), request.headers.get("user-agent"))
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(status_code=409, detail="An account with this email already exists") from exc
    return token_response(user, access, refresh)


@router.post("/login", response_model=TokenResponse)
def login(req: UserLoginRequest, request: Request, db: Session = Depends(get_db)):
    email = normalized_email(str(req.email))
    ip = client_ip(request)
    enforce_login_rate_limit(db, email, ip)
    user = db.query(User).filter(User.email == email).first()
    valid = bool(user and user.is_active and verify_password(req.password, user.hashed_password))
    record_login_attempt(db, email, ip, valid)
    if not valid:
        db.commit()
        raise HTTPException(status_code=401, detail="Invalid email or password")
    if password_needs_rehash(user.hashed_password):
        user.hashed_password = get_password_hash(req.password)
    access, refresh, _ = issue_session(db, user, ip, request.headers.get("user-agent"))
    db.commit()
    return token_response(user, access, refresh)


@router.post("/refresh", response_model=TokenResponse)
def refresh(req: RefreshRequest, request: Request, db: Session = Depends(get_db)):
    try:
        access, new_refresh, session = rotate_refresh_token(
            db, req.refresh_token, client_ip(request), request.headers.get("user-agent")
        )
    except HTTPException:
        # Reuse detection revokes every refresh token in the session family.
        # Persist that security action before returning the 401 response.
        db.commit()
        raise
    user = db.query(User).filter(User.id == session.user_id).one()
    db.commit()
    return token_response(user, access, new_refresh)


@router.post("/logout", status_code=204)
def logout(req: RefreshRequest, db: Session = Depends(get_db)):
    revoke_session(db, req.refresh_token)
    db.commit()


@router.get("/sessions")
def list_sessions(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    sessions = db.query(UserSession).filter(UserSession.user_id == user.id).order_by(
        UserSession.created_at.desc()
    ).all()
    return [
        {
            "id": item.id,
            "createdAt": item.created_at.isoformat(),
            "lastUsedAt": item.last_used_at.isoformat() if item.last_used_at else None,
            "expiresAt": item.expires_at.isoformat(),
            "revoked": item.revoked_at is not None,
            "userAgent": item.user_agent,
        }
        for item in sessions
    ]


@router.delete("/sessions/{session_id}", status_code=204)
def revoke_user_session(
    session_id: str,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    session = db.query(UserSession).filter(
        UserSession.id == session_id,
        UserSession.user_id == user.id,
    ).first()
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    session.revoked_at = utcnow()
    db.commit()


@router.get("/me")
def get_me(user: User = Depends(get_current_user)):
    return {
        "id": user.id,
        "email": user.email,
        "fullName": user.full_name,
        "isAdmin": user.is_admin,
        "createdAt": user.created_at.isoformat(),
    }

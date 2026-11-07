from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import text
from starlette.middleware.trustedhost import TrustedHostMiddleware

from backend.app.core.config import Settings, settings
from backend.app.core.security import get_password_hash, validate_password
from backend.app.database.session import Base, SessionLocal, engine
from backend.app.models import (  # noqa: F401  (register all tables)
    ClientSession,
    Device,
    DeviceCredential,
    DeviceLease,
    Document,
    JobAttempt,
    JobEvent,
    JobPage,
    LoginAttempt,
    PairingSession,
    PrintJob,
    User,
    UserSession,
)
from backend.app.routers import agent, auth, connect, devices, documents, jobs, websocket
from backend.app.services.storage import document_storage  # noqa: F401  (initializes storage)

logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("pocketprint")


def bootstrap_admin_user() -> None:
    """Create the configured administrator exactly once if configured."""
    if not settings.ADMIN_BOOTSTRAP_EMAIL or not settings.ADMIN_BOOTSTRAP_PASSWORD:
        return
    email = settings.ADMIN_BOOTSTRAP_EMAIL.strip().lower()
    validate_password(settings.ADMIN_BOOTSTRAP_PASSWORD)
    with SessionLocal() as db:
        existing = db.query(User).filter(User.email == email).first()
        if existing:
            if not existing.is_admin:
                existing.is_admin = True
                db.commit()
            return
        db.add(
            User(
                email=email,
                hashed_password=get_password_hash(settings.ADMIN_BOOTSTRAP_PASSWORD),
                full_name="Administrator",
                is_admin=True,
            )
        )
        db.commit()
        logger.info("Bootstrapped administrator account")


def ensure_print_job_columns() -> None:
    """Ensure optional advanced print columns exist in SQLite print_jobs table."""
    if not settings.DATABASE_URL.startswith("sqlite"):
        return
    columns_to_add = [
        ("dpi", "INTEGER DEFAULT 300"),
        ("quality", "VARCHAR(40) DEFAULT 'NORMAL'"),
        ("margins", "VARCHAR(500) DEFAULT '{\"top\":4,\"bottom\":4,\"left\":4,\"right\":4,\"unit\":\"mm\"}'"),
        ("collate", "BOOLEAN DEFAULT 1"),
        ("pages_per_sheet", "INTEGER DEFAULT 1"),
        ("print_options", "VARCHAR(2000) DEFAULT '{}'"),
    ]
    try:
        raw_conn = engine.raw_connection()
        cursor = raw_conn.cursor()
        cursor.execute("PRAGMA table_info(print_jobs)")
        existing = {row[1] for row in cursor.fetchall()}
        for col_name, col_def in columns_to_add:
            if col_name not in existing:
                cursor.execute(f'ALTER TABLE print_jobs ADD COLUMN "{col_name}" {col_def}')
                logger.info("Migrated print_jobs table: added column %s", col_name)
        raw_conn.commit()
        raw_conn.close()
    except Exception as exc:
        logger.warning("Auto-migration check skipped or failed: %s", exc)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.ensure_storage()
    ensure_print_job_columns()
    bootstrap_admin_user()
    yield


app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.APP_VERSION,
    docs_url="/api/docs",
    redoc_url="/api/redoc",
    openapi_url="/api/openapi.json",
    lifespan=lifespan,
)

def build_cors_config(app_settings: Settings) -> tuple[list[str], str | None]:
    """Return the exact CORS allowlist used by the application.

    Local/test environments may reflect any origin. Production must use an
    explicit allowlist (plus an optional operator-provided regex) and must
    never combine "*" with credentials.
    """
    if app_settings.APP_ENV != "production" or "*" in app_settings.cors_origins:
        return [], r".*"
    return app_settings.cors_origins, app_settings.CORS_ALLOWED_ORIGIN_REGEX.strip() or None


allowed_hosts = ["*"] if settings.APP_ENV != "production" or "*" in settings.trusted_hosts else settings.trusted_hosts
app.add_middleware(TrustedHostMiddleware, allowed_hosts=allowed_hosts)

# Add CORS last so it is the outermost middleware and can attach headers to
# errors produced by the host validation and route handlers.
cors_origins, origin_regex = build_cors_config(settings)
app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins,
    allow_origin_regex=origin_regex,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=[
        "Accept",
        "Authorization",
        "Content-Type",
        "X-Client-Token",
        "X-Device-Token",
        "X-Lease-Token",
    ],
    expose_headers=["X-Content-SHA256"],
)

app.include_router(connect.router)
app.include_router(devices.router)
app.include_router(documents.router)
app.include_router(jobs.router)
app.include_router(agent.router)
app.include_router(websocket.router)
app.include_router(auth.router)


@app.get("/health")
def health_check():
    return {"status": "HEALTHY", "service": "PocketPrint Cloud", "version": settings.APP_VERSION, "apiVersion": 1}


@app.get("/ready")
def readiness_check():
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
    except Exception as exc:  # pragma: no cover - operational path
        return JSONResponse(status_code=503, content={"status": "NOT_READY", "detail": str(exc)[:200]})
    return {"status": "READY"}


dist_dir = Path(__file__).resolve().parents[2] / "frontend" / "dist"
if not dist_dir.exists():
    dist_dir = Path(__file__).resolve().parents[2] / "frontend" / "pocketprint" / "dist"
web_dir = Path(__file__).resolve().parents[2] / "web"

if web_dir.exists():
    app.mount("/static", StaticFiles(directory=str(web_dir)), name="static")

    @app.get("/")
    def serve_dashboard():
        index_file = web_dir / "index.html"
        if index_file.exists():
            return FileResponse(str(index_file))
        return {"status": "ONLINE", "message": "PocketPrint Cloud Printing API"}

    @app.get("/console")
    def serve_console():
        return FileResponse(str(web_dir / "index.html"))

if (dist_dir / "index.html").exists():
    app.mount("/assets", StaticFiles(directory=str(dist_dir / "assets")), name="react-assets")

    @app.get("/react")
    def serve_react_dashboard():
        return FileResponse(str(dist_dir / "index.html"))

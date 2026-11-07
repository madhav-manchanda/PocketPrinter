from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import AliasChoices, Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


BASE_DIR = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    PROJECT_NAME: str = "PocketPrint Backend"
    APP_ENV: Literal["development", "test", "production"] = "development"
    API_PREFIX: str = "/api"
    SECRET_KEY: str = Field(
        default="development-only-secret-change-me",
        validation_alias=AliasChoices("POCKETPRINT_SECRET", "SECRET_KEY"),
    )
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = Field(default=15, ge=5, le=60)
    REFRESH_TOKEN_EXPIRE_DAYS: int = Field(default=30, ge=1, le=180)
    WS_TICKET_EXPIRE_SECONDS: int = Field(default=60, ge=15, le=300)

    DATABASE_URL: str = f"sqlite:///{BASE_DIR / 'pocketprint.db'}"
    DATABASE_POOL_SIZE: int = Field(default=10, ge=1, le=100)
    DATABASE_MAX_OVERFLOW: int = Field(default=20, ge=0, le=200)
    SQLITE_BUSY_TIMEOUT_MS: int = Field(default=30000, ge=1000, le=120000)

    STORAGE_ROOT: Path = BASE_DIR / "storage"
    MAX_UPLOAD_SIZE: int = Field(default=50 * 1024 * 1024, ge=1024)
    UPLOAD_CHUNK_SIZE: int = Field(default=1024 * 1024, ge=64 * 1024, le=8 * 1024 * 1024)
    DOCUMENT_RETENTION_DAYS: int = Field(default=90, ge=1)
    MIN_FREE_STORAGE_BYTES: int = Field(default=1024 * 1024 * 1024, ge=0)

    CORS_ALLOWED_ORIGINS: str = "http://localhost:3000,http://localhost:5173"
    CORS_ALLOWED_ORIGIN_REGEX: str = ""
    TRUSTED_HOSTS: str = "localhost,127.0.0.1"
    TRUST_PROXY_HEADERS: bool = False

    DEVICE_ONLINE_TIMEOUT_SECONDS: int = Field(default=90, ge=15, le=3600)
    JOB_LEASE_SECONDS: int = Field(default=120, ge=30, le=3600)
    LOGIN_ATTEMPT_WINDOW_SECONDS: int = Field(default=900, ge=60)
    LOGIN_MAX_ATTEMPTS: int = Field(default=8, ge=1, le=100)
    PAIRING_CODE_TTL_SECONDS: int = Field(default=600, ge=60, le=3600)
    PAIRING_MAX_ATTEMPTS: int = Field(default=10, ge=1, le=100)

    ADMIN_BOOTSTRAP_EMAIL: str | None = None
    ADMIN_BOOTSTRAP_PASSWORD: str | None = None

    LOG_LEVEL: str = "INFO"
    APP_VERSION: str = "1.0.0"

    ALLOWED_EXTENSIONS: set[str] = {
        "pdf", "png", "jpg", "jpeg", "bmp", "webp", "tif", "tiff",
        "txt", "csv", "docx", "xlsx", "pptx", "rtf",
    }

    @property
    def documents_dir(self) -> Path:
        return self.STORAGE_ROOT / "documents"

    @property
    def temp_dir(self) -> Path:
        return self.STORAGE_ROOT / ".tmp"

    @property
    def cors_origins(self) -> list[str]:
        return [value.strip() for value in self.CORS_ALLOWED_ORIGINS.split(",") if value.strip()]

    @property
    def trusted_hosts(self) -> list[str]:
        return [value.strip() for value in self.TRUSTED_HOSTS.split(",") if value.strip()]

    def ensure_storage(self) -> None:
        self.documents_dir.mkdir(parents=True, exist_ok=True)
        self.temp_dir.mkdir(parents=True, exist_ok=True)

    @model_validator(mode="after")
    def validate_production(self) -> "Settings":
        if self.APP_ENV == "production":
            if len(self.SECRET_KEY) < 32 or self.SECRET_KEY.startswith("development-"):
                raise ValueError("Production SECRET_KEY must be a random value of at least 32 characters")
            if not self.DATABASE_URL.startswith(
                ("sqlite:///", "postgresql://", "postgresql+psycopg://")
            ):
                raise ValueError("Production DATABASE_URL must use SQLite or PostgreSQL")
            if self.DATABASE_URL.startswith("sqlite:///") and not self.DATABASE_URL.startswith(
                "sqlite:////"
            ):
                raise ValueError(
                    "Production SQLite DATABASE_URL must use an absolute path (sqlite:////...)"
                )
            if not self.STORAGE_ROOT.is_absolute():
                raise ValueError("Production STORAGE_ROOT must be an absolute path")
            if not self.cors_origins or "*" in self.cors_origins:
                raise ValueError("Production CORS_ALLOWED_ORIGINS must be an explicit allowlist")
            if not self.trusted_hosts or "*" in self.trusted_hosts:
                raise ValueError("Production TRUSTED_HOSTS must be an explicit host allowlist")
            if bool(self.ADMIN_BOOTSTRAP_EMAIL) != bool(self.ADMIN_BOOTSTRAP_PASSWORD):
                raise ValueError("Both ADMIN_BOOTSTRAP_EMAIL and ADMIN_BOOTSTRAP_PASSWORD are required")
        return self


settings = Settings()

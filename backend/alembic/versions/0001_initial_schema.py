"""Create the initial PocketPrint schema with explicit, reviewable DDL.

Revision ID: 0001_initial_schema
Revises:
Create Date: 2026-11-05
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "0001_initial_schema"
down_revision = None
branch_labels = None
depends_on = None

JOB_STATES = (
    "QUEUED", "ASSIGNED", "DOWNLOADING", "READY", "PRINTING",
    "TRANSMISSION_COMPLETED", "COMPLETED", "CANCEL_REQUESTED", "CANCELLED",
    "FAILED", "RETRY_PENDING", "UNCERTAIN", "NEEDS_RECONCILIATION",
)


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("email", sa.String(320), nullable=False),
        sa.Column("hashed_password", sa.String(512), nullable=False),
        sa.Column("full_name", sa.String(200)),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("is_admin", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_users_email", "users", ["email"], unique=True)

    op.create_table(
        "user_sessions",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("family_id", sa.String(36), nullable=False),
        sa.Column("refresh_token_hash", sa.String(64), nullable=False, unique=True),
        sa.Column("user_agent", sa.String(500)),
        sa.Column("ip_address", sa.String(64)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_used_at", sa.DateTime(timezone=True)),
        sa.Column("revoked_at", sa.DateTime(timezone=True)),
        sa.Column("replaced_by_id", sa.String(36), sa.ForeignKey("user_sessions.id")),
    )
    op.create_index("ix_user_sessions_family_id", "user_sessions", ["family_id"])
    op.create_index("ix_user_sessions_user_active", "user_sessions", ["user_id", "revoked_at"])

    op.create_table(
        "login_attempts",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("identifier_hash", sa.String(64), nullable=False),
        sa.Column("ip_address", sa.String(64), nullable=False),
        sa.Column("succeeded", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_login_attempts_identifier_hash", "login_attempts", ["identifier_hash"])
    op.create_index("ix_login_attempts_ip_address", "login_attempts", ["ip_address"])
    op.create_index("ix_login_attempts_created_at", "login_attempts", ["created_at"])

    op.create_table(
        "documents",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="RESTRICT"), nullable=False),
        sa.Column("original_filename", sa.String(500), nullable=False),
        sa.Column("storage_key", sa.String(200), nullable=False, unique=True),
        sa.Column("media_type", sa.String(200), nullable=False),
        sa.Column("extension", sa.String(20), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False),
        sa.Column("sha256", sa.String(64), nullable=False),
        sa.Column("page_count", sa.Integer()),
        sa.Column("is_complete", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("deleted_at", sa.DateTime(timezone=True)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_documents_user_id", "documents", ["user_id"])
    op.create_index("ix_documents_sha256", "documents", ["sha256"])

    op.create_table(
        "devices",
        sa.Column("id", sa.String(128), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="RESTRICT"), nullable=False),
        sa.Column("device_name", sa.String(200), nullable=False, server_default="PocketPrint Node"),
        sa.Column("password_hash", sa.String(512)),
        sa.Column("device_token", sa.String(64)),
        sa.Column("status", sa.String(32), nullable=False, server_default="OFFLINE"),
        sa.Column("is_online", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("is_revoked", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("is_disabled", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("last_heartbeat", sa.DateTime(timezone=True)),
        sa.Column("last_connected_at", sa.DateTime(timezone=True)),
        sa.Column("app_version", sa.String(64)),
        sa.Column("printer_model", sa.String(200), nullable=False, server_default="HP Ink Tank 310 series"),
        sa.Column("vid_pid", sa.String(32), nullable=False, server_default="03F0:1053"),
        sa.Column("active_profile", sa.String(200), nullable=False, server_default="HPLIP Pcl3Gui2 Default"),
        sa.Column("current_job_id", sa.String(36), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("status IN ('PENDING_PAIRING','ONLINE','OFFLINE','REVOKED','DISABLED')", name="ck_devices_status"),
    )
    op.create_index("ix_devices_user_id", "devices", ["user_id"])
    op.create_index("ix_devices_status", "devices", ["status"])

    op.create_table(
        "print_jobs",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="RESTRICT"), nullable=False),
        sa.Column("device_id", sa.String(128), sa.ForeignKey("devices.id", ondelete="RESTRICT"), nullable=False),
        sa.Column("document_id", sa.String(36), sa.ForeignKey("documents.id", ondelete="RESTRICT")),
        sa.Column("filename", sa.String(500), nullable=False),
        sa.Column("original_path", sa.String(1000)),
        sa.Column("converted_pdf_path", sa.String(1000)),
        sa.Column("file_size", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("mime_type", sa.String(200), nullable=False, server_default="application/pdf"),
        sa.Column("page_count", sa.Integer()),
        sa.Column("page_range", sa.String(200), nullable=False, server_default=""),
        sa.Column("color_mode", sa.String(40), nullable=False, server_default="MONOCHROME_1BIT"),
        sa.Column("paper_size", sa.String(40), nullable=False, server_default="A4"),
        sa.Column("orientation", sa.String(40), nullable=False, server_default="PORTRAIT"),
        sa.Column("copies", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("scaling", sa.String(40), nullable=False, server_default="FIT_TO_PAGE"),
        sa.Column("status", sa.String(40), nullable=False, server_default="QUEUED"),
        sa.Column("progress_percent", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("bytes_transferred", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("scheduled_items", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("completed_items", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("current_attempt", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("failure_reason", sa.String(2000)),
        sa.Column("cancellation_requested_at", sa.DateTime(timezone=True)),
        sa.Column("physical_output_confirmed", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("assigned_at", sa.DateTime(timezone=True)),
        sa.Column("started_at", sa.DateTime(timezone=True)),
        sa.Column("transmission_completed_at", sa.DateTime(timezone=True)),
        sa.Column("completed_at", sa.DateTime(timezone=True)),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("copies BETWEEN 1 AND 99", name="ck_print_jobs_copies"),
        sa.CheckConstraint("progress_percent BETWEEN 0 AND 100", name="ck_print_jobs_progress"),
        sa.CheckConstraint("status IN (%s)" % ",".join("'%s'" % state for state in JOB_STATES), name="ck_print_jobs_status"),
    )
    op.create_index("ix_print_jobs_user_id", "print_jobs", ["user_id"])
    op.create_index("ix_print_jobs_device_id", "print_jobs", ["device_id"])
    op.create_index("ix_print_jobs_document_id", "print_jobs", ["document_id"])
    op.create_index("ix_print_jobs_status", "print_jobs", ["status"])
    op.create_index("ix_print_jobs_created_at", "print_jobs", ["created_at"])
    op.create_index("ix_print_jobs_device_status_created", "print_jobs", ["device_id", "status", "created_at"])
    if op.get_bind().dialect.name == "sqlite":
        with op.batch_alter_table("devices") as batch:
            batch.create_foreign_key(
                "fk_devices_current_job_id_print_jobs", "print_jobs",
                ["current_job_id"], ["id"],
            )
    else:
        op.create_foreign_key(
            "fk_devices_current_job_id_print_jobs", "devices", "print_jobs",
            ["current_job_id"], ["id"],
        )

    op.create_table(
        "device_credentials",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("device_id", sa.String(128), sa.ForeignKey("devices.id", ondelete="CASCADE"), nullable=False),
        sa.Column("token_hash", sa.String(64), nullable=False),
        sa.Column("generation", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_used_at", sa.DateTime(timezone=True)),
        sa.Column("expires_at", sa.DateTime(timezone=True)),
        sa.Column("revoked_at", sa.DateTime(timezone=True)),
    )
    op.create_index("ix_device_credentials_device_id", "device_credentials", ["device_id"])
    op.create_index("ix_device_credentials_token_hash", "device_credentials", ["token_hash"], unique=True)

    op.create_table(
        "pairing_sessions",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("code_hash", sa.String(64), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True)),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("paired_device_id", sa.String(128), sa.ForeignKey("devices.id")),
    )
    op.create_index("ix_pairing_sessions_user_id", "pairing_sessions", ["user_id"])
    op.create_index("ix_pairing_sessions_code_hash", "pairing_sessions", ["code_hash"], unique=True)
    op.create_index("ix_pairing_sessions_expires_at", "pairing_sessions", ["expires_at"])

    op.create_table(
        "job_attempts",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("job_id", sa.String(36), sa.ForeignKey("print_jobs.id", ondelete="CASCADE"), nullable=False),
        sa.Column("attempt_number", sa.Integer(), nullable=False),
        sa.Column("state", sa.String(40), nullable=False, server_default="QUEUED"),
        sa.Column("started_at", sa.DateTime(timezone=True)),
        sa.Column("ended_at", sa.DateTime(timezone=True)),
        sa.Column("outcome_detail", sa.String(2000)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_job_attempts_job_id", "job_attempts", ["job_id"])
    op.create_index("uq_job_attempt_number", "job_attempts", ["job_id", "attempt_number"], unique=True)

    op.create_table(
        "job_pages",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("job_id", sa.String(36), sa.ForeignKey("print_jobs.id", ondelete="CASCADE"), nullable=False),
        sa.Column("attempt_number", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("document_page_number", sa.Integer(), nullable=False),
        sa.Column("scheduled_page_number", sa.Integer(), nullable=False),
        sa.Column("copy_number", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("state", sa.String(40), nullable=False, server_default="PENDING"),
        sa.Column("idempotency_key", sa.String(200), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True)),
        sa.Column("transmission_started_at", sa.DateTime(timezone=True)),
        sa.Column("transmission_completed_at", sa.DateTime(timezone=True)),
        sa.Column("last_update_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("error_code", sa.String(100)),
        sa.Column("error_detail", sa.String(2000)),
        sa.UniqueConstraint("job_id", "idempotency_key", name="uq_job_page_idempotency"),
        sa.UniqueConstraint("job_id", "attempt_number", "scheduled_page_number", "copy_number", name="uq_job_page_schedule"),
    )
    op.create_index("ix_job_pages_job_id", "job_pages", ["job_id"])

    op.create_table(
        "job_events",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("job_id", sa.String(36), sa.ForeignKey("print_jobs.id", ondelete="CASCADE"), nullable=False),
        sa.Column("page_id", sa.String(36), sa.ForeignKey("job_pages.id", ondelete="SET NULL")),
        sa.Column("attempt_number", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("event_type", sa.String(80), nullable=False),
        sa.Column("status", sa.String(80), nullable=False),
        sa.Column("message", sa.String(1000), nullable=False),
        sa.Column("idempotency_key", sa.String(200)),
        sa.Column("event_data", sa.JSON(), nullable=False, server_default=sa.text("'{}'")),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("job_id", "idempotency_key", name="uq_job_event_idempotency"),
    )
    op.create_index("ix_job_events_job_id", "job_events", ["job_id"])
    op.create_index("ix_job_events_page_id", "job_events", ["page_id"])
    op.create_index("ix_job_events_event_type", "job_events", ["event_type"])
    op.create_index("ix_job_events_created_at", "job_events", ["created_at"])
    op.create_index("ix_job_events_job_created", "job_events", ["job_id", "created_at"])

    op.create_table(
        "device_leases",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("job_id", sa.String(36), sa.ForeignKey("print_jobs.id", ondelete="CASCADE"), nullable=False),
        sa.Column("device_id", sa.String(128), sa.ForeignKey("devices.id", ondelete="CASCADE"), nullable=False),
        sa.Column("token_hash", sa.String(64), nullable=False, unique=True),
        sa.Column("generation", sa.String(36), nullable=False),
        sa.Column("state", sa.String(32), nullable=False, server_default="ACTIVE"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_renewed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("released_at", sa.DateTime(timezone=True)),
        sa.CheckConstraint("state IN ('ACTIVE','RELEASED','EXPIRED')", name="ck_device_leases_state"),
    )
    op.create_index("ix_device_leases_expires_at", "device_leases", ["expires_at"])
    op.create_index(
        "uq_active_lease_device", "device_leases", ["device_id"], unique=True,
        postgresql_where=sa.text("state = 'ACTIVE'"),
        sqlite_where=sa.text("state = 'ACTIVE'"),
    )
    op.create_index(
        "uq_active_lease_job", "device_leases", ["job_id"], unique=True,
        postgresql_where=sa.text("state = 'ACTIVE'"),
        sqlite_where=sa.text("state = 'ACTIVE'"),
    )


def downgrade() -> None:
    # Explicit reverse dependency order; this is intended for an empty initial
    # install only. Production data should be backed up before any downgrade.
    op.drop_table("device_leases")
    op.drop_table("job_events")
    op.drop_table("job_pages")
    op.drop_table("job_attempts")
    op.drop_table("pairing_sessions")
    op.drop_table("device_credentials")
    if op.get_bind().dialect.name == "sqlite":
        with op.batch_alter_table("devices") as batch:
            batch.drop_constraint("fk_devices_current_job_id_print_jobs", type_="foreignkey")
    else:
        op.drop_constraint("fk_devices_current_job_id_print_jobs", "devices", type_="foreignkey")
    op.drop_table("print_jobs")
    op.drop_table("devices")
    op.drop_table("documents")
    op.drop_table("login_attempts")
    op.drop_table("user_sessions")
    op.drop_table("users")

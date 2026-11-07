"""Device-centric authentication schema migration.

Replaces user-account requirements with direct device-scoped authentication.
Adds client_sessions table and device_id to documents; makes user_id nullable.

Revision ID: 0002_device_centric_auth
Revises: 0001_initial_schema
Create Date: 2026-11-05
"""
from alembic import op
import sqlalchemy as sa

revision = "0002_device_centric_auth"
down_revision = "0001_initial_schema"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # 1. Create client_sessions table for user-side password-authenticated sessions
    op.create_table(
        "client_sessions",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("device_id", sa.String(128), sa.ForeignKey("devices.id", ondelete="CASCADE"), nullable=False),
        sa.Column("token_hash", sa.String(64), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_used_at", sa.DateTime(timezone=True)),
        sa.Column("revoked_at", sa.DateTime(timezone=True)),
        sa.Column("client_ip", sa.String(64)),
        sa.Column("user_agent", sa.String(500)),
    )
    op.create_index("ix_client_sessions_device_id", "client_sessions", ["device_id"])
    op.create_index("ix_client_sessions_token_hash", "client_sessions", ["token_hash"], unique=True)
    op.create_index("ix_client_sessions_expires_at", "client_sessions", ["expires_at"])

    # 2. Add device_id to documents and make user_id nullable across models
    dialect = op.get_bind().dialect.name

    if dialect == "sqlite":
        with op.batch_alter_table("documents") as batch:
            batch.add_column(
                sa.Column(
                    "device_id",
                    sa.String(128),
                    sa.ForeignKey("devices.id", ondelete="CASCADE", name="fk_documents_device_id_devices"),
                    nullable=True,
                )
            )
            batch.create_index("ix_documents_device_id", ["device_id"])
            batch.alter_column("user_id", existing_type=sa.String(36), nullable=True)

        with op.batch_alter_table("devices") as batch:
            batch.alter_column("user_id", existing_type=sa.String(36), nullable=True)

        with op.batch_alter_table("print_jobs") as batch:
            batch.alter_column("user_id", existing_type=sa.String(36), nullable=True)
    else:
        op.add_column(
            "documents",
            sa.Column(
                "device_id",
                sa.String(128),
                sa.ForeignKey("devices.id", ondelete="CASCADE", name="fk_documents_device_id_devices"),
                nullable=True,
            ),
        )
        op.create_index("ix_documents_device_id", "documents", ["device_id"])
        op.alter_column("documents", "user_id", existing_type=sa.String(36), nullable=True)
        op.alter_column("devices", "user_id", existing_type=sa.String(36), nullable=True)
        op.alter_column("print_jobs", "user_id", existing_type=sa.String(36), nullable=True)


def downgrade() -> None:
    dialect = op.get_bind().dialect.name
    if dialect == "sqlite":
        with op.batch_alter_table("documents") as batch:
            batch.drop_index("ix_documents_device_id")
            batch.drop_column("device_id")
            batch.alter_column("user_id", existing_type=sa.String(36), nullable=False)

        with op.batch_alter_table("devices") as batch:
            batch.alter_column("user_id", existing_type=sa.String(36), nullable=False)

        with op.batch_alter_table("print_jobs") as batch:
            batch.alter_column("user_id", existing_type=sa.String(36), nullable=False)
    else:
        op.drop_index("ix_documents_device_id", table_name="documents")
        op.drop_column("documents", "device_id")
        op.alter_column("documents", "user_id", existing_type=sa.String(36), nullable=False)
        op.alter_column("devices", "user_id", existing_type=sa.String(36), nullable=False)
        op.alter_column("print_jobs", "user_id", existing_type=sa.String(36), nullable=False)

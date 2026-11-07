from __future__ import annotations

import threading
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest
from sqlalchemy.orm import sessionmaker

from backend.app.database.session import engine
from backend.app.models.device import Device
from backend.app.models.job import PrintJob
from backend.app.models.lease import DeviceLease
from backend.app.models.user import User
from backend.app.services.queue_service import claim_next_job


pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql",
    reason="Row-lock concurrency guarantees are verified against PostgreSQL",
)


def test_concurrent_polls_never_claim_two_jobs_for_one_device() -> None:
    """Two simultaneous agent polls must result in at most one active lease."""
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    suffix = uuid.uuid4().hex
    user_id = str(uuid.uuid4())
    device_id = f"test-device-{suffix}"
    job_ids = [str(uuid.uuid4()), str(uuid.uuid4())]

    with Session() as db:
        user = User(
            id=user_id,
            email=f"{suffix}@pocketprint.example.com",
            hashed_password="not-used-in-this-test",
        )
        device = Device(id=device_id, user_id=user_id, device_name="Concurrency test")
        db.add_all([user, device])
        db.flush()
        for job_id in job_ids:
            db.add(PrintJob(
                id=job_id,
                user_id=user_id,
                device_id=device_id,
                filename=f"{job_id}.pdf",
                file_size=1,
                mime_type="application/pdf",
                status="QUEUED",
            ))
        db.commit()

    barrier = threading.Barrier(2)

    def poll() -> str | None:
        with Session() as db:
            device = db.query(Device).filter(Device.id == device_id).one()
            barrier.wait(timeout=10)
            claimed = claim_next_job(db, device)
            if claimed is None:
                db.commit()
                return None
            job, _, _lease = claimed
            db.commit()
            return job.id

    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: poll(), range(2)))
        claimed_ids = [value for value in results if value is not None]
        assert len(claimed_ids) == 1
        with Session() as db:
            active = db.query(DeviceLease).filter(
                DeviceLease.device_id == device_id,
                DeviceLease.state == "ACTIVE",
            ).all()
            assert len(active) == 1
            assert active[0].job_id == claimed_ids[0]
            assert db.query(PrintJob).filter(
                PrintJob.device_id == device_id,
                PrintJob.status == "QUEUED",
            ).count() == 1
    finally:
        with Session() as db:
            db.query(Device).filter(Device.id == device_id).update(
                {Device.current_job_id: None}, synchronize_session=False
            )
            db.query(DeviceLease).filter(DeviceLease.device_id == device_id).delete(synchronize_session=False)
            db.query(PrintJob).filter(PrintJob.device_id == device_id).delete(synchronize_session=False)
            db.query(Device).filter(Device.id == device_id).delete(synchronize_session=False)
            db.query(User).filter(User.id == user_id).delete(synchronize_session=False)
            db.commit()

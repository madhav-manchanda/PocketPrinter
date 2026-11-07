"""Tests for lease renewal, cancellation races, idempotency, and recovery lifecycles."""

from __future__ import annotations

import io
from datetime import timedelta
import pytest
from pypdf import PdfWriter
from sqlalchemy.orm import Session

from backend.app.core.security import utcnow
from backend.app.models.device import Device
from backend.app.models.job import PrintJob
from backend.app.models.lease import DeviceLease
from backend.app.services.queue_service import expire_device_lease
from backend.app.services.job_state import transition_job


def _valid_pdf_bytes() -> bytes:
    writer = PdfWriter()
    writer.add_blank_page(width=612, height=792)
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


def _create_and_claim_job(client, client_headers, device_token, device_id):
    queued = client.post(
        "/api/jobs",
        headers=client_headers,
        data={"deviceId": device_id, "copies": "1"},
        files={"file": ("doc.pdf", io.BytesIO(_valid_pdf_bytes()), "application/pdf")},
    )
    assert queued.status_code == 201, queued.text
    job_id = queued.json()["jobId"]

    claimed = client.get("/api/device/jobs/next", headers={"X-Device-Token": device_token})
    assert claimed.status_code == 200
    claim_data = claimed.json()
    assert claim_data["jobId"] == job_id
    lease_token = claim_data["leaseToken"]
    return job_id, lease_token


def test_lease_renewal_and_cancel_detection(client, printer_factory):
    dev_id = "dev-renew-test"
    p = printer_factory(device_id=dev_id, password="RenewPassword123!")
    job_id, lease_token = _create_and_claim_job(client, p["clientHeaders"], p["deviceToken"], dev_id)

    headers = {"X-Device-Token": p["deviceToken"], "X-Lease-Token": lease_token}

    # 1. Normal renewal
    renew = client.post(f"/api/device/jobs/{job_id}/lease/renew", headers=headers)
    assert renew.status_code == 200, renew.text
    data = renew.json()
    assert data["status"] == "ACK"
    assert data["cancelRequested"] is False
    assert "leaseExpiresAt" in data

    # 2. Client requests cancellation
    cancel = client.post(f"/api/jobs/{job_id}/cancel", headers=p["clientHeaders"])
    assert cancel.status_code == 200
    assert cancel.json()["status"] == "CANCEL_REQUESTED"

    # 3. Next lease renewal discovers cancelRequested == True
    renew_after_cancel = client.post(f"/api/device/jobs/{job_id}/lease/renew", headers=headers)
    assert renew_after_cancel.status_code == 200
    assert renew_after_cancel.json()["cancelRequested"] is True


def test_idempotent_complete_request(client, printer_factory):
    dev_id = "dev-idemp-comp"
    p = printer_factory(device_id=dev_id, password="IdempCompPass123!")
    job_id, lease_token = _create_and_claim_job(client, p["clientHeaders"], p["deviceToken"], dev_id)
    headers = {"X-Device-Token": p["deviceToken"], "X-Lease-Token": lease_token}

    # First complete
    c1 = client.post(
        f"/api/device/jobs/{job_id}/complete",
        headers=headers,
        json={"bytesTransferred": 5000, "idempotencyKey": "comp-key-1"},
    )
    assert c1.status_code == 200
    assert c1.json()["status"] == "COMPLETED"
    assert c1.json()["duplicate"] is False

    # Second complete (network retry with same lease token)
    c2 = client.post(
        f"/api/device/jobs/{job_id}/complete",
        headers=headers,
        json={"bytesTransferred": 5000, "idempotencyKey": "comp-key-1"},
    )
    assert c2.status_code == 200
    assert c2.json()["status"] == "COMPLETED"
    assert c2.json()["duplicate"] is True


def test_idempotent_fail_request(client, printer_factory):
    dev_id = "dev-idemp-fail"
    p = printer_factory(device_id=dev_id, password="IdempFailPass123!")
    job_id, lease_token = _create_and_claim_job(client, p["clientHeaders"], p["deviceToken"], dev_id)
    headers = {"X-Device-Token": p["deviceToken"], "X-Lease-Token": lease_token}

    f1 = client.post(
        f"/api/device/jobs/{job_id}/fail",
        headers=headers,
        json={"failureReason": "Printer out of paper", "uncertain": False, "idempotencyKey": "fail-key-1"},
    )
    assert f1.status_code == 200
    assert f1.json()["status"] == "FAILED"
    assert f1.json()["duplicate"] is False

    f2 = client.post(
        f"/api/device/jobs/{job_id}/fail",
        headers=headers,
        json={"failureReason": "Printer out of paper", "uncertain": False, "idempotencyKey": "fail-key-1"},
    )
    assert f2.status_code == 200
    assert f2.json()["status"] == "FAILED"
    assert f2.json()["duplicate"] is True


def test_cancel_acknowledgement_certain_and_uncertain(client, printer_factory):
    dev_id = "dev-cancel-ack"
    p = printer_factory(device_id=dev_id, password="CancelAckPass123!")

    # Sub-case 1: Certain cancel before USB transmission
    job_id1, lease1 = _create_and_claim_job(client, p["clientHeaders"], p["deviceToken"], dev_id)
    client.post(f"/api/jobs/{job_id1}/cancel", headers=p["clientHeaders"])
    ack1 = client.post(
        f"/api/device/jobs/{job_id1}/cancel/ack",
        headers={"X-Device-Token": p["deviceToken"], "X-Lease-Token": lease1},
        json={"uncertain": False, "message": "Stopped before USB data"},
    )
    assert ack1.status_code == 200
    assert ack1.json()["status"] == "CANCELLED"

    # Sub-case 2: Uncertain cancel after bytes reached printer
    job_id2, lease2 = _create_and_claim_job(client, p["clientHeaders"], p["deviceToken"], dev_id)
    client.post(f"/api/jobs/{job_id2}/cancel", headers=p["clientHeaders"])
    ack2 = client.post(
        f"/api/device/jobs/{job_id2}/cancel/ack",
        headers={"X-Device-Token": p["deviceToken"], "X-Lease-Token": lease2},
        json={"uncertain": True, "message": "Stopped mid-transmission"},
    )
    assert ack2.status_code == 200
    assert ack2.json()["status"] == "UNCERTAIN"


def test_lease_expiration_with_ready_and_reconciliation_states(db_session: Session, client, printer_factory):
    dev_id = "dev-lease-expire"
    p = printer_factory(device_id=dev_id, password="LeaseExpirePass123!")
    job_id, lease_token = _create_and_claim_job(client, p["clientHeaders"], p["deviceToken"], dev_id)

    device = db_session.query(Device).filter(Device.id == dev_id).first()
    job = db_session.query(PrintJob).filter(PrintJob.id == job_id).first()
    lease = db_session.query(DeviceLease).filter(DeviceLease.job_id == job_id).first()

    # Case 1: Job in READY state when lease expires -> returns safely to QUEUED
    transition_job(db_session, job, "READY", "PREFLIGHT_DONE", "Ready for print")
    lease.expires_at = utcnow() - timedelta(seconds=10)
    db_session.commit()

    expire_device_lease(db_session, device)
    db_session.commit()

    db_session.refresh(job)
    db_session.refresh(device)
    assert job.status == "QUEUED"
    assert device.current_job_id is None
    assert lease.state == "EXPIRED"

    # Case 2: Job in NEEDS_RECONCILIATION state when lease expires -> does not crash with 409
    dev_id2 = "dev-lease-expire-2"
    p2 = printer_factory(device_id=dev_id2, password="LeaseExpirePass456!")
    job_id2, lease_token2 = _create_and_claim_job(client, p2["clientHeaders"], p2["deviceToken"], dev_id2)
    device2 = db_session.query(Device).filter(Device.id == dev_id2).first()
    job2 = db_session.query(PrintJob).filter(PrintJob.id == job_id2).first()
    lease2 = db_session.query(DeviceLease).filter(DeviceLease.job_id == job_id2).first()

    transition_job(db_session, job2, "PRINTING", "PRINTING", "Transmitting")
    transition_job(db_session, job2, "NEEDS_RECONCILIATION", "ERROR", "USB stall")
    lease2.expires_at = utcnow() - timedelta(seconds=10)
    db_session.commit()

    expire_device_lease(db_session, device2)
    db_session.commit()

    db_session.refresh(job2)
    assert job2.status == "NEEDS_RECONCILIATION"
    assert lease2.state == "EXPIRED"
    assert device2.current_job_id is None


def test_late_page_event_after_terminal_job_returns_graceful_ack(client, printer_factory):
    dev_id = "dev-late-page-ack"
    p = printer_factory(device_id=dev_id, password="LatePagePass123!")
    job_id, lease_token = _create_and_claim_job(client, p["clientHeaders"], p["deviceToken"], dev_id)
    headers = {"X-Device-Token": p["deviceToken"], "X-Lease-Token": lease_token}

    # Job fails or completes
    failed = client.post(
        f"/api/device/jobs/{job_id}/fail",
        headers=headers,
        json={"failureReason": "Paper jam", "uncertain": False, "idempotencyKey": "fail-key-late"},
    )
    assert failed.status_code == 200
    assert failed.json()["status"] == "FAILED"

    # Late page event arrives AFTER failJob — must gracefully ACK, never 409
    late_event = {
        "eventId": "late-page-event-1",
        "eventType": "PAGE_TRANSMISSION_COMPLETED",
        "documentPageNumber": 1,
        "scheduledPageNumber": 1,
        "copyNumber": 1,
        "pageState": "TRANSMISSION_COMPLETED",
    }
    res = client.post(f"/api/device/jobs/{job_id}/pages/events", headers=headers, json=late_event)
    assert res.status_code == 200
    assert res.json()["terminal"] is True
    assert res.json()["duplicate"] is True

    # Late progress report arrives AFTER failJob — must gracefully ACK, never 409
    late_prog = {
        "status": "TRANSMITTING_USB",
        "progressPercent": 50,
        "message": "Late streaming packet",
    }
    prog_res = client.post(f"/api/device/jobs/{job_id}/progress", headers=headers, json=late_prog)
    assert prog_res.status_code == 200
    assert prog_res.json()["terminal"] is True


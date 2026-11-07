"""End-to-end API tests: device-centric password authentication and printing lifecycle."""

from __future__ import annotations

import io

import pytest
from pypdf import PdfWriter
from sqlalchemy.orm import Session

from backend.app.database.session import engine


def _valid_pdf_bytes() -> bytes:
    writer = PdfWriter()
    writer.add_blank_page(width=612, height=792)
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


def test_health_identifies_compatible_pocketprint_api(client):
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "HEALTHY"
    assert body["service"] == "PocketPrint Cloud"
    assert body["apiVersion"] == 1


def test_printer_registration_and_password_connect(client, printer_factory):
    p = printer_factory(device_id="node-reg-01", password="StrongPrinterPass1!")
    assert p["deviceToken"].startswith("pp_node_")
    assert p["clientToken"].startswith("pp_client_")

    # The credential is never stored in plaintext.
    from backend.app.models.device import ClientSession, DeviceCredential

    with Session(engine) as session:
        stored_creds = session.query(DeviceCredential).all()
        assert stored_creds
        assert all(c.token_hash != p["deviceToken"] for c in stored_creds)

        stored_sessions = session.query(ClientSession).all()
        assert stored_sessions
        assert all(s.token_hash != p["clientToken"] for s in stored_sessions)

    # Status check via client token
    status_res = client.get("/api/connect/device", headers=p["clientHeaders"])
    assert status_res.status_code == 200
    assert status_res.json()["id"] == "node-reg-01"


def test_wrong_password_rejected(client, printer_factory):
    printer_factory(device_id="node-pwd-test", password="CorrectPassword123")

    wrong = client.post(
        "/api/connect",
        json={"password": "WrongPassword456", "deviceId": "node-pwd-test"},
    )
    assert wrong.status_code == 401


def test_weak_printer_password_rejected(client):
    short = client.post(
        "/api/devices/register",
        json={
            "deviceId": "node-short-pwd",
            "deviceName": "Test",
            "devicePassword": "123",
            "printerModel": "HP",
            "vidPid": "03F0:1053",
            "appVersion": "1.0.0",
        },
    )
    assert short.status_code in (400, 422)


def test_client_session_disconnect_revocation(client, printer_factory):
    p = printer_factory(device_id="node-disc-test", password="DisconnectSecret1!")

    disc = client.post("/api/disconnect", headers=p["clientHeaders"])
    assert disc.status_code == 200
    assert disc.json()["status"] == "DISCONNECTED"

    # Revoked token cannot access device endpoints
    after = client.get("/api/connect/device", headers=p["clientHeaders"])
    assert after.status_code == 401


def test_multiple_printers_coexist_isolated_by_password(client, printer_factory):
    p1 = printer_factory(device_id="printer-alpha", password="AlphaPassword123!")
    p2 = printer_factory(device_id="printer-beta", password="BetaPassword123!")

    # Connect to alpha by its password without specifying deviceId
    c1 = client.post("/api/connect", json={"password": "AlphaPassword123!"})
    assert c1.status_code == 200
    assert c1.json()["device"]["id"] == "printer-alpha"

    # Connect to beta by its password without specifying deviceId
    c2 = client.post("/api/connect", json={"password": "BetaPassword123!"})
    assert c2.status_code == 200
    assert c2.json()["device"]["id"] == "printer-beta"


def test_agent_claim_download_progress_complete_flow(client, printer_factory):
    p = printer_factory(device_id="node-flow-test", password="FlowPassword123!")

    # No queued job yet
    assert client.get("/api/device/jobs/next", headers=p["deviceHeaders"]).status_code == 204

    # User uploads and queues job
    queued = client.post(
        "/api/jobs",
        headers=p["clientHeaders"],
        data={"deviceId": p["deviceId"], "copies": "1"},
        files={"file": ("hello.pdf", io.BytesIO(_valid_pdf_bytes()), "application/pdf")},
    )
    assert queued.status_code == 201, queued.text
    job_id = queued.json()["jobId"]

    # Agent claims next job
    claimed = client.get("/api/device/jobs/next", headers=p["deviceHeaders"])
    assert claimed.status_code == 200, claimed.text
    claim = claimed.json()
    assert claim["jobId"] == job_id
    lease = {"X-Lease-Token": claim["leaseToken"]}

    # Agent downloads document
    downloaded = client.get(f"/api/device/jobs/{job_id}/download", headers={**p["deviceHeaders"], **lease})
    assert downloaded.status_code == 200
    assert downloaded.content.startswith(b"%PDF-")

    # Agent reports progress
    progress = client.post(
        f"/api/device/jobs/{job_id}/progress",
        headers={**p["deviceHeaders"], **lease},
        json={"status": "TRANSMITTING_USB", "progressPercent": 40, "message": "streaming"},
    )
    assert progress.status_code == 200, progress.text

    # Agent reports completion
    complete = client.post(
        f"/api/device/jobs/{job_id}/complete",
        headers={**p["deviceHeaders"], **lease},
        json={"bytesTransferred": 12345},
    )
    assert complete.status_code == 200, complete.text
    assert complete.json()["physicalOutputConfirmed"] is False

    # Detail query via client session
    detail = client.get(f"/api/jobs/{job_id}", headers=p["clientHeaders"])
    assert detail.status_code == 200
    assert detail.json()["status"] == "COMPLETED"


def test_page_events_are_idempotent(client, printer_factory):
    p = printer_factory(device_id="node-page-test", password="PagePassword123!")

    client.post(
        "/api/jobs",
        headers=p["clientHeaders"],
        data={"deviceId": p["deviceId"]},
        files={"file": ("p.pdf", io.BytesIO(_valid_pdf_bytes()), "application/pdf")},
    )
    claim = client.get("/api/device/jobs/next", headers=p["deviceHeaders"]).json()
    job_id = claim["jobId"]
    lease = {"X-Lease-Token": claim["leaseToken"]}

    event = {
        "eventId": "page-event-0001",
        "eventType": "PAGE_TRANSMISSION_COMPLETED",
        "documentPageNumber": 1,
        "scheduledPageNumber": 1,
        "copyNumber": 1,
        "pageState": "TRANSMISSION_COMPLETED",
    }
    first = client.post(
        f"/api/device/jobs/{job_id}/pages/events",
        headers={**p["deviceHeaders"], **lease},
        json=event,
    )
    assert first.status_code == 200, first.text
    assert first.json()["duplicate"] is False

    second = client.post(
        f"/api/device/jobs/{job_id}/pages/events",
        headers={**p["deviceHeaders"], **lease},
        json=event,
    )
    assert second.status_code == 200
    assert second.json()["duplicate"] is True


def test_uncertain_failure_requires_reconciliation_not_retry(client, printer_factory):
    p = printer_factory(device_id="node-unc-test", password="UncertainPass123!")

    client.post(
        "/api/jobs",
        headers=p["clientHeaders"],
        data={"deviceId": p["deviceId"]},
        files={"file": ("p.pdf", io.BytesIO(_valid_pdf_bytes()), "application/pdf")},
    )
    claim = client.get("/api/device/jobs/next", headers=p["deviceHeaders"]).json()
    job_id = claim["jobId"]
    lease = {"X-Lease-Token": claim["leaseToken"]}

    failed = client.post(
        f"/api/device/jobs/{job_id}/fail",
        headers={**p["deviceHeaders"], **lease},
        json={"failureReason": "USB transfer ambiguous", "uncertain": True},
    )
    assert failed.status_code == 200
    assert failed.json()["status"] == "UNCERTAIN"

    # Blind retry is refused while the outcome is uncertain
    retry = client.post(f"/api/jobs/{job_id}/retry", headers=p["clientHeaders"])
    assert retry.status_code == 409

    reconcile = client.post(
        f"/api/jobs/{job_id}/reconcile",
        headers=p["clientHeaders"],
        json={"outcome": "not_printed", "note": "operator confirmed no page"},
    )
    assert reconcile.status_code == 200
    assert reconcile.json()["status"] == "RETRY_PENDING"

    retry_now = client.post(f"/api/jobs/{job_id}/retry", headers=p["clientHeaders"])
    assert retry_now.status_code == 200
    assert retry_now.json()["status"] == "QUEUED"


def test_document_upload_rejects_bad_extension_and_signature(client, printer_factory):
    p = printer_factory(device_id="node-doc-test", password="DocSecPassword1!")

    bad_ext = client.post(
        "/api/documents",
        headers=p["clientHeaders"],
        files={"file": ("evil.exe", io.BytesIO(b"MZ..."), "application/octet-stream")},
    )
    assert bad_ext.status_code == 400

    bad_magic = client.post(
        "/api/documents",
        headers=p["clientHeaders"],
        files={"file": ("fake.pdf", io.BytesIO(b"not-a-pdf"), "application/pdf")},
    )
    assert bad_magic.status_code == 400


def test_cross_device_document_isolation(client, printer_factory):
    p1 = printer_factory(device_id="printer-iso-1", password="IsoPassword123!")
    p2 = printer_factory(device_id="printer-iso-2", password="IsoPassword456!")

    upload = client.post(
        "/api/documents",
        headers=p1["clientHeaders"],
        files={"file": ("a.pdf", io.BytesIO(_valid_pdf_bytes()), "application/pdf")},
    )
    assert upload.status_code == 201
    document_id = upload.json()["id"]

    # Client for printer 2 cannot access document uploaded for printer 1
    forbidden = client.get(f"/api/documents/{document_id}/download", headers=p2["clientHeaders"])
    assert forbidden.status_code == 404

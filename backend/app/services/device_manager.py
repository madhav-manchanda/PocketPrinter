from __future__ import annotations

import json
from collections import defaultdict

from fastapi import WebSocket


class DeviceConnectionManager:
    """Best-effort, process-local WebSocket hints.

    PostgreSQL remains the source of truth; polling must keep working when a
    WebSocket is unavailable, and multi-worker deployments rely on the database
    rather than this in-memory map.
    """

    def __init__(self) -> None:
        self.connections: dict[str, set[WebSocket]] = defaultdict(set)

    async def connect(self, channel: str, websocket: WebSocket) -> None:
        await websocket.accept()
        self.connections[channel].add(websocket)

    def disconnect(self, channel: str, websocket: WebSocket) -> None:
        sockets = self.connections.get(channel)
        if not sockets:
            return
        sockets.discard(websocket)
        if not sockets:
            self.connections.pop(channel, None)

    async def publish(self, channel: str, message: dict) -> None:
        payload = json.dumps(message, separators=(",", ":"))
        dead: list[WebSocket] = []
        for websocket in list(self.connections.get(channel, set())):
            try:
                await websocket.send_text(payload)
            except Exception:
                dead.append(websocket)
        for websocket in dead:
            self.disconnect(channel, websocket)

    async def notify_new_job(self, device_id: str, job_id: str) -> None:
        await self.publish(f"device:{device_id}", {"event": "NEW_JOB", "jobId": job_id})

    async def notify_job_update(self, target_id: str, job_id: str, status: str) -> None:
        msg = {"event": "JOB_UPDATED", "jobId": job_id, "status": status}
        await self.publish(f"device:{target_id}", msg)
        await self.publish(f"user:{target_id}", msg)


device_manager = DeviceConnectionManager()

from __future__ import annotations

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect

from backend.app.core.security import decode_ws_ticket
from backend.app.services.device_manager import device_manager


router = APIRouter(tags=["WebSocket Realtime"])


async def _websocket_loop(websocket: WebSocket, channel: str) -> None:
    await device_manager.connect(channel, websocket)
    try:
        while True:
            data = await websocket.receive_text()
            if data == "PING":
                await websocket.send_text("PONG")
    except WebSocketDisconnect:
        pass
    finally:
        device_manager.disconnect(channel, websocket)


@router.websocket("/ws/device/{device_id}")
async def device_websocket_endpoint(
    websocket: WebSocket,
    device_id: str,
    ticket: str = Query(...),
):
    """Device channel authenticated by a short-lived ticket, never a long-lived token."""
    payload = decode_ws_ticket(ticket, f"device:{device_id}")
    if not payload or payload.get("sub") != device_id:
        await websocket.close(code=4003)
        return
    await _websocket_loop(websocket, f"device:{device_id}")


@router.websocket("/ws/user/{user_id}")
async def user_websocket_endpoint(
    websocket: WebSocket,
    user_id: str,
    ticket: str = Query(...),
):
    """User channel authenticated by a short-lived ticket scoped to the user."""
    payload = decode_ws_ticket(ticket, f"user:{user_id}")
    if not payload or payload.get("sub") != user_id:
        await websocket.close(code=4003)
        return
    await _websocket_loop(websocket, f"user:{user_id}")

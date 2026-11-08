# server.py
import asyncio
import json
import os
import uuid
from aiohttp import web, WSMsgType

PSK = os.environ.get("PSK", "")
PORT = int(os.environ.get("PORT", "8080"))
HERE = os.path.dirname(os.path.abspath(__file__))

state = {"devices": {}, "dashboards": set(), "pending": {}}

def _token(request: web.Request) -> str:
    h = request.headers.get("Authorization", "")
    return h[7:] if h.startswith("Bearer ") else h

@web.middleware
async def cors_middleware(request, handler):
    if request.method == "OPTIONS": response = web.Response()
    else: response = await handler(request)
    response.headers["Access-Control-Allow-Origin"] = "*"
    response.headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization"
    return response

async def index(request): return web.FileResponse(os.path.join(HERE, "index.html"))
async def wake(request): return web.json_response({"status": "awake"})

async def api_cmd(request: web.Request):
    if not PSK or _token(request) != PSK:
        return web.json_response({"ok": False, "error": "auth failed"}, status=401)
    try: payload = await request.json()
    except Exception: return web.json_response({"ok": False, "error": "bad json"}, status=400)
        
    target, cmd, args = payload.get("device_id"), payload.get("cmd"), payload.get("args") or {}
    dev_data = state["devices"].get(target)
    
    if not dev_data or dev_data["ws"].closed:
        return web.json_response({"ok": False, "error": "Device offline"}, status=503)

    req_id = uuid.uuid4().hex
    fut = asyncio.get_running_loop().create_future()
    state["pending"][req_id] = fut
    try: await dev_data["ws"].send_json({"type": "cmd", "id": req_id, "cmd": cmd, "args": args})
    except Exception: return web.json_response({"ok": False, "error": "send failed"}, status=502)
    try: return web.json_response(await asyncio.wait_for(fut, timeout=30.0))
    except asyncio.TimeoutError: return web.json_response({"ok": False, "error": "Timeout"}, status=504)
    finally: state["pending"].pop(req_id, None)

async def ws_handler(request):
    ws = web.WebSocketResponse(heartbeat=10.0)
    await ws.prepare(request)
    try:
        first = await asyncio.wait_for(ws.receive(), timeout=10.0)
        hello = json.loads(first.data)
    except Exception: await ws.close(); return ws

    if hello.get("psk", "") != PSK: await ws.close(); return ws

    role = hello.get("role")
    if role == "device":
        dev_id = hello.get("device_id", "unknown")
        dev_name = hello.get("device_name", "Unknown")
        state["devices"][dev_id] = {"ws": ws, "name": dev_name}
        await ws.send_json({"type": "ready"})
        for d in state["dashboards"]:
            if not d.closed: await d.send_json({"type": "device_online", "device_id": dev_id, "device_name": dev_name})
        try:
            async for msg in ws:
                data = json.loads(msg.data)
                if data.get("type") == "reply":
                    fut = state["pending"].get(data.get("id"))
                    if fut and not fut.done(): fut.set_result(data)
                elif data.get("type") == "push":
                    for d in state["dashboards"]:
                        if not d.closed: await d.send_json(data)
        finally:
            state["devices"].pop(dev_id, None)
            for d in state["dashboards"]:
                if not d.closed: await d.send_json({"type": "device_offline", "device_id": dev_id})
    elif role == "dashboard":
        state["dashboards"].add(ws)
        await ws.send_json({"type": "device_list", "devices": [{"id": k, "name": v["name"]} for k, v in state["devices"].items()]})
        try:
            async for msg in ws:
                if json.loads(msg.data).get("type") == "ping": await ws.send_json({"type": "pong"})
        finally: state["dashboards"].discard(ws)
    return ws

app = web.Application(middlewares=[cors_middleware])
app.router.add_get("/", index)
app.router.add_get("/api/wake", wake)
app.router.add_post("/api/cmd", api_cmd)
app.router.add_get("/ws", ws_handler)

if __name__ == "__main__":
    web.run_app(app, host="0.0.0.0", port=PORT)

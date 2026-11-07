# server.py — aiohttp relay. Stateless: no queue, no db, no command log.
# Config via env: PSK (required), PORT (default 8080).
import asyncio
import json
import os
import uuid
from aiohttp import web, WSMsgType

PSK = os.environ.get("PSK", "")
PORT = int(os.environ.get("PORT", "8080"))
HERE = os.path.dirname(os.path.abspath(__file__))

state = {
    "device": None,
    "dashboards": set(),
    "pending": {},
}

def _token(request: web.Request) -> str:
    h = request.headers.get("Authorization", "")
    return h[7:] if h.startswith("Bearer ") else h

async def index(request: web.Request):
    return web.FileResponse(os.path.join(HERE, "index.html"))

async def api_cmd(request: web.Request):
    if not PSK or _token(request) != PSK:
        return web.json_response({"ok": False, "error": "auth"}, status=401)
    dev = state["device"]
    if dev is None or dev.closed:
        return web.json_response({"ok": False, "error": "no device"}, status=503)
    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "bad json"}, status=400)
    cmd = payload.get("cmd")
    args = payload.get("args") or {}
    if not cmd:
        return web.json_response({"ok": False, "error": "no cmd"}, status=400)

    req_id = uuid.uuid4().hex
    fut = asyncio.get_running_loop().create_future()
    state["pending"][req_id] = fut
    try:
        await dev.send_json({"type": "cmd", "id": req_id, "cmd": cmd, "args": args})
    except Exception as e:
        state["pending"].pop(req_id, None)
        return web.json_response({"ok": False, "error": f"send: {e}"}, status=502)
    try:
        reply = await asyncio.wait_for(fut, timeout=45.0)
    except asyncio.TimeoutError:
        return web.json_response({"ok": False, "error": "timeout"}, status=504)
    finally:
        state["pending"].pop(req_id, None)
    return web.json_response(reply)

async def ws_handler(request: web.Request):
    ws = web.WebSocketResponse(heartbeat=25.0, max_msg_size=0)
    await ws.prepare(request)
    try:
        first = await asyncio.wait_for(ws.receive(), timeout=10.0)
    except asyncio.TimeoutError:
        await ws.close(); return ws
    if first.type != WSMsgType.TEXT:
        await ws.close(); return ws
    try:
        hello = json.loads(first.data)
    except Exception:
        await ws.close(); return ws
    role = hello.get("role")
    if not PSK or hello.get("psk", "") != PSK:
        await ws.send_json({"type": "error", "error": "auth"})
        await ws.close(); return ws

    if role == "device":
        prev = state["device"]
        if prev is not None and prev is not ws and not prev.closed:
            try: await prev.close()
            except Exception: pass
        state["device"] = ws
        await ws.send_json({"type": "ready", "role": "device"})
        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                try: data = json.loads(msg.data)
                except Exception: continue
                t = data.get("type")
                if t == "reply":
                    fut = state["pending"].get(data.get("id"))
                    if fut is not None and not fut.done():
                        fut.set_result({
                            "ok": bool(data.get("ok")),
                            "data": data.get("data"),
                            "error": data.get("error"),
                        })
                elif t == "push":
                    dead = set()
                    for d in list(state["dashboards"]):
                        if d.closed:
                            dead.add(d); continue
                        try: await d.send_json(data)
                        except Exception: dead.add(d)
                    state["dashboards"] -= dead
        finally:
            if state["device"] is ws:
                state["device"] = None
        return ws

    if role == "dashboard":
        state["dashboards"].add(ws)
        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                try: data = json.loads(msg.data)
                except Exception: continue
                if data.get("type") == "cmd" and state["device"] and not state["device"].closed:
                    try: await state["device"].send_json(data)
                    except Exception: pass
        finally:
            state["dashboards"].discard(ws)
        return ws
    await ws.close()
    return ws

def make_app():
    app = web.Application()
    app.router.add_get("/", index)
    app.router.add_post("/api/cmd", api_cmd)
    app.router.add_get("/ws", ws_handler)
    return app

if __name__ == "__main__":
    if not PSK:
        raise SystemExit("PSK env var is required")
    web.run_app(make_app(), host="0.0.0.0", port=PORT, access_log=None)

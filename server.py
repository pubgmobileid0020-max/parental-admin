# server.py — Multi-device aiohttp relay. Stateless.
import asyncio
import json
import os
import uuid
import time
from aiohttp import web, WSMsgType

PSK = os.environ.get("PSK", "")
PORT = int(os.environ.get("PORT", "8080"))
HERE = os.path.dirname(os.path.abspath(__file__))

state = {
    "devices": {},      
    "dashboards": set(),
    "pending": {},      
}

def _token(request: web.Request) -> str:
    h = request.headers.get("Authorization", "")
    return h[7:] if h.startswith("Bearer ") else h

async def index(request: web.Request):
    return web.FileResponse(os.path.join(HERE, "index.html"))

async def wake(request: web.Request):
    return web.json_response({"status": "awake", "time": time.time()})

async def api_cmd(request: web.Request):
    if not PSK or _token(request) != PSK:
        return web.json_response({"ok": False, "error": "auth failed"}, status=401)
    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "bad json"}, status=400)
        
    target = payload.get("device_id")
    cmd = payload.get("cmd")
    args = payload.get("args") or {}
    
    if not target or not cmd:
        return web.json_response({"ok": False, "error": "missing device_id or cmd"}, status=400)
        
    dev_data = state["devices"].get(target)
    if not dev_data or dev_data["ws"].closed:
        print(f"❌ CMD FAILED: Device {target} offline", flush=True)
        return web.json_response({"ok": False, "error": "device offline"}, status=503)

    req_id = uuid.uuid4().hex
    fut = asyncio.get_running_loop().create_future()
    state["pending"][req_id] = fut
    try:
        print(f"📤 Sending command '{cmd}' to {dev_data['name']}", flush=True)
        await dev_data["ws"].send_json({"type": "cmd", "id": req_id, "cmd": cmd, "args": args})
    except Exception as e:
        state["pending"].pop(req_id, None)
        return web.json_response({"ok": False, "error": f"send: {e}"}, status=502)
    try:
        reply = await asyncio.wait_for(fut, timeout=20.0)
    except asyncio.TimeoutError:
        print(f"⏳ CMD TIMEOUT: {dev_data['name']} did not reply", flush=True)
        return web.json_response({"ok": False, "error": "Device timeout"}, status=504)
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
        print("🚫 AUTH FAILED: Wrong PSK", flush=True)
        await ws.send_json({"type": "error", "error": "auth failed"})
        await ws.close(); return ws

    if role == "device":
        dev_id = hello.get("device_id", "unknown")
        dev_name = hello.get("device_name", "Unknown")
        
        prev = state["devices"].get(dev_id)
        if prev is not None and prev["ws"] is not ws and not prev["ws"].closed:
            try: await prev["ws"].close()
            except Exception: pass
            
        state["devices"][dev_id] = {"ws": ws, "name": dev_name}
        print(f"✅ PHONE CONNECTED: {dev_name} ({dev_id})", flush=True)
        
        await ws.send_json({"type": "ready", "role": "device"})
        
        for d in list(state["dashboards"]):
            if not d.closed:
                try: await d.send_json({"type": "device_online", "device_id": dev_id, "device_name": dev_name})
                except Exception: pass

        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT: continue
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
                    for d in list(state["dashboards"]):
                        if not d.closed:
                            try: await d.send_json(data)
                            except Exception: pass
        finally:
            if state["devices"].get(dev_id) and state["devices"][dev_id]["ws"] is ws:
                state["devices"].pop(dev_id, None)
                print(f"❌ PHONE DISCONNECTED: {dev_name}", flush=True)
                for d in list(state["dashboards"]):
                    if not d.closed:
                        try: await d.send_json({"type": "device_offline", "device_id": dev_id})
                        except Exception: pass
        return ws

    if role == "dashboard":
        state["dashboards"].add(ws)
        dev_list = [{"id": k, "name": v["name"]} for k, v in state["devices"].items()]
        print(f"🖥️ DASHBOARD CONNECTED. Sending devices: {dev_list}", flush=True)
        
        await ws.send_json({"type": "device_list", "devices": dev_list})
        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT: continue
        finally:
            state["dashboards"].discard(ws)
            print("🖥️ DASHBOARD DISCONNECTED", flush=True)
        return ws
    return ws

def make_app():
    app = web.Application()
    app.router.add_get("/", index)
    app.router.add_get("/api/wake", wake)
    app.router.add_post("/api/cmd", api_cmd)
    app.router.add_get("/ws", ws_handler)
    return app

if __name__ == "__main__":
    if not PSK: raise SystemExit("PSK env var required")
    print("🚀 SERVER STARTING...", flush=True)
    web.run_app(make_app(), host="0.0.0.0", port=PORT, access_log=None)

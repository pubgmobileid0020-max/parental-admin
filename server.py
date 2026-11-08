# ... (keep imports and middleware the same)

async def ws_handler(request):
    ws = web.WebSocketResponse(heartbeat=10.0)
    await ws.prepare(request)
    try:
        first = await asyncio.wait_for(ws.receive(), timeout=10.0)
        hello = json.loads(first.data)
    except Exception: await ws.close(); return ws

    if hello.get("psk", "") != PSK: 
        print("AUTH FAILED: Wrong PSK", flush=True)
        await ws.close(); return ws

    role = hello.get("role")
    print(f"INSTANCE: {os.getenv('FLY_ALLOC_ID')} | ROLE: {role}", flush=True)

    if role == "device":
        dev_id = hello.get("device_id", "unknown")
        dev_name = hello.get("device_name", "Unknown")
        state["devices"][dev_id] = {"ws": ws, "name": dev_name}
        print(f"DEVICE REGISTERED: {dev_id} {dev_name}", flush=True)
        
        await ws.send_json({"type": "ready"})
        
        dev_list = [{"id": k, "name": v["name"]} for k, v in state["devices"].items()]
        print(f"SEND DEVICE LIST: {dev_list}", flush=True)
        for d in state["dashboards"]:
            if not d.closed: await d.send_json({"type": "device_list", "devices": dev_list})
            
        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    data = json.loads(msg.data)
                    if data.get("type") in ["webrtc_offer", "webrtc_ice"]:
                        data["device_id"] = dev_id
                        for d in state["dashboards"]:
                            if not d.closed: await d.send_json(data)
                    elif data.get("type") == "push":
                        for d in state["dashboards"]:
                            if not d.closed: await d.send_json(data)
                    elif data.get("type") == "reply":
                        fut = state["pending"].get(data.get("id"))
                        if fut and not fut.done(): fut.set_result(data)
        finally:
            state["devices"].pop(dev_id, None)
            print(f"DEVICE DISCONNECTED: {dev_id}", flush=True)
            for d in state["dashboards"]:
                if not d.closed: await d.send_json({"type": "device_offline", "device_id": dev_id})
                
    elif role == "dashboard":
        state["dashboards"].add(ws)
        dev_list = [{"id": k, "name": v["name"]} for k, v in state["devices"].items()]
        print(f"DASHBOARD CONNECTED. SENDING: {dev_list}", flush=True)
        await ws.send_json({"type": "device_list", "devices": dev_list})
        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    data = json.loads(msg.data)
                    if data.get("type") in ["webrtc_answer", "webrtc_ice"]:
                        target = data.get("device_id")
                        if target and target in state["devices"]:
                            dev = state["devices"][target]["ws"]
                            if not dev.closed: await dev.send_json(data)
                    elif data.get("type") == "ping": await ws.send_json({"type": "pong"})
        finally: state["dashboards"].discard(ws)
    return ws

# ... (keep the rest the same)

#!/usr/bin/env python3
"""A relay for proving a live roll without changing qkt or the gateway. SIMULATED ANSWERS when a rule is set.

  deribit mode: relays Deribit's public REST and WebSocket to the paper gateway. A ticker of an instrument named in
    /ctl/far.json {"BTC_USDC-6NOV26": {"best_bid_price": 38787.5, "best_ask_price": 233490}} has those fields overwritten.
  vgp mode: relays qkt <-> gateway. While /ctl/hold holds a suffix (":open"), a POST /v1/orders whose client_order_id
    contains it is held and its client waits, so a restart can land between a roll's legs. /ctl/release holding
    "forward" then sends it on to the gateway; any other way, it never reaches the venue.

Every rewrite or hold is logged, so the evidence says which venue answers were simulated.
"""
import asyncio
import json
import os
import sys
import time

from aiohttp import ClientSession, WSMsgType, web

MODE = sys.argv[1]
CTL = "/ctl"
HOP = {"host", "content-length", "transfer-encoding", "connection", "upgrade", "content-encoding"}
DERIBIT = {"rest": "https://www.deribit.com", "ws": "wss://www.deribit.com/ws/api/v2"}
VGP = os.environ.get("VGP_UPSTREAM", "http://gw:8443")
rewritten = {}


def log(*args):
    print(time.strftime("%H:%M:%S", time.gmtime()), *args, flush=True)


def far_rules():
    try:
        with open(f"{CTL}/far.json") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def hold_rule():
    try:
        with open(f"{CTL}/hold") as f:
            return f.read().strip()
    except OSError:
        return ""


def release_rule():
    try:
        with open(f"{CTL}/release") as f:
            return f.read().strip() == "forward"
    except OSError:
        return False


def rewrite_ticker(text):
    rules = far_rules()
    if not rules or '"ticker.' not in text:
        return text
    message = json.loads(text)
    data = message.get("params", {}).get("data", {})
    rule = rules.get(data.get("instrument_name"))
    if rule is None:
        return text
    data.update(rule)
    name = data["instrument_name"]
    rewritten[name] = rewritten.get(name, 0) + 1
    if rewritten[name] % 200 == 1:
        log("SIMULATED ticker", name, rule, "count", rewritten[name])
    return json.dumps(message)


async def relay_ws(request, upstream, session, on_upstream=lambda t: t):
    client = web.WebSocketResponse(heartbeat=None, max_msg_size=0)
    await client.prepare(request)
    headers = {k: v for k, v in request.headers.items() if k.lower() == "authorization"}
    async with session.ws_connect(upstream, headers=headers, max_msg_size=0, heartbeat=None) as ws:
        async def pump(src, dst, transform):
            async for m in src:
                if m.type == WSMsgType.TEXT:
                    await dst.send_str(transform(m.data))
                elif m.type == WSMsgType.BINARY:
                    await dst.send_bytes(m.data)
                elif m.type in (WSMsgType.CLOSE, WSMsgType.ERROR, WSMsgType.CLOSED):
                    break
        tasks = [asyncio.create_task(pump(client, ws, lambda t: t)),
                 asyncio.create_task(pump(ws, client, on_upstream))]
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for t in tasks:
            t.cancel()
    return client


async def relay_http(request, base, session):
    body = await request.read()
    headers = {k: v for k, v in request.headers.items() if k.lower() not in HOP}
    async with session.request(request.method, base + request.path_qs, data=body, headers=headers,
                               allow_redirects=False) as r:
        payload = await r.read()
        out = {k: v for k, v in r.headers.items() if k.lower() not in HOP}
        return web.Response(status=r.status, body=payload, headers=out)


async def deribit(request):
    session = request.app["session"]
    if request.headers.get("Upgrade", "").lower() == "websocket":
        return await relay_ws(request, DERIBIT["ws"], session, rewrite_ticker)
    return await relay_http(request, DERIBIT["rest"], session)


async def vgp(request):
    session = request.app["session"]
    if request.headers.get("Upgrade", "").lower() == "websocket":
        return await relay_ws(request, VGP.replace("http", "ws", 1) + request.path_qs, session)
    if request.method == "POST" and request.path == "/v1/orders":
        body = await request.text()
        rule = hold_rule()
        log("ORDER", body)
        if rule and rule in json.loads(body).get("client_order_id", ""):
            log("HOLDING:", body)
            while not release_rule():
                await asyncio.sleep(0.2)
            log("RELEASED to the gateway:", body)
    return await relay_http(request, VGP, session)


async def on_start(app):
    app["session"] = ClientSession(auto_decompress=True)


async def on_stop(app):
    await app["session"].close()


app = web.Application(client_max_size=64 * 1024 * 1024)
app.on_startup.append(on_start)
app.on_cleanup.append(on_stop)
app.router.add_route("*", "/{tail:.*}", deribit if MODE == "deribit" else vgp)
web.run_app(app, port=8080, print=None)

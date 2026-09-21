#!/usr/bin/env python3
"""A loopback proxy in front of the MT5 gateway whose bar history lags for a few seconds.

    lagging-history-proxy.py --listen PORT --gateway http://127.0.0.1:5001 [--lag-seconds 5] [--drop-bars 2]

With --lose-limit-response it instead loses one answer: the first LIMIT order is placed at the venue
and the daemon is told 502 (case `engine/flatten-during-unknown-outcome`, #1234). Otherwise
everything is forwarded untouched, except that for --lag-seconds after the first bar-history read
the newest --drop-bars bars are removed from every `/fetch_data_*` answer - what a busy MT5 terminal
does when it has not yet synced the last minute or two. Used by the attestation case
`engine/lagging-history-warmup` to prove, in a real daemon, that warmup waits the lag out instead of
seeding indicators across the hole (#1228). Loopback only; it never alters an order or a quote.
"""
import argparse, json, time, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ap = argparse.ArgumentParser()
ap.add_argument("--listen", type=int, required=True)
ap.add_argument("--gateway", required=True)
ap.add_argument("--lag-seconds", type=float, default=5.0)
ap.add_argument("--drop-bars", type=int, default=2)
ap.add_argument("--lose-limit-response", action="store_true",
                help="place the first LIMIT order at the venue but answer the daemon 502: a lost response")
a = ap.parse_args()
first_history_read = [None]
limit_lost = [False]


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def forward(self):
        length = int(self.headers.get("content-length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length", "connection")}
        req = urllib.request.Request(a.gateway + self.path, data=body, method=self.command, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                status, payload, ctype = resp.status, resp.read(), resp.headers.get("content-type", "application/json")
        except urllib.error.HTTPError as error:
            status, payload, ctype = error.code, error.read(), error.headers.get("content-type", "application/json")
        if a.lose_limit_response and not limit_lost[0] and self.command == "POST" and self.path == "/order" and b"LIMIT" in (body or b"").upper():
            # The venue has the order; the daemon never hears back. HTTP 5xx after a send is an UNKNOWN outcome.
            limit_lost[0] = True
            status, payload, ctype = 502, b'{"ok": false, "error": "attestation proxy: response lost"}', "application/json"
        if not a.lose_limit_response and self.path.startswith("/fetch_data_") and status == 200:
            now = time.monotonic()
            first_history_read[0] = first_history_read[0] or now
            if now - first_history_read[0] < a.lag_seconds:
                doc = json.loads(payload)
                if isinstance(doc.get("data"), list) and len(doc["data"]) > a.drop_bars:
                    doc["data"] = doc["data"][: -a.drop_bars]
                    payload = json.dumps(doc).encode()
        self.send_response(status)
        self.send_header("content-type", ctype)
        self.send_header("content-length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    do_GET = do_POST = do_DELETE = do_PUT = forward


ThreadingHTTPServer(("127.0.0.1", a.listen), Proxy).serve_forever()

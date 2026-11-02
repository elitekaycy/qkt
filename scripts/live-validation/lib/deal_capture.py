"""What a lane case's daemon reports of its deals: a loopback stand-in for the Insights collector, and the verdict.

A restart past the 60-second grace must not lose a deal (#1570): VGP deals carry no leg, position or realized P&L, so only
what qkt booked and kept states them. Reporting is on only from the restart on (`capture_deals` on a `qkt_restart` drill),
or the first process would report the deal before it stopped and the restart would prove nothing. It records `broker.deal`
events and answers 200; the venue's answers are untouched, this only listens to qkt.
"""
import json, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class DealCapture:
    def __init__(self):
        self.deals = []
        capture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                try:
                    events = json.loads(body).get("events", [])
                except ValueError:
                    events = []
                capture.deals += [e for e in events if e.get("type") == "broker.deal"]
                self.send_response(200)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{}")

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def config(self):
        """The daemon's `insights:` block that reports to this capture."""
        return {"insights": {"enabled": True, "url": f"http://127.0.0.1:{self.server.server_port}/ingest",
                             "instance_id": "attestation-capture", "token": "attestation-capture",
                             "events": ["deal"], "flush_interval_ms": 250, "state_poll_ms": 5000, "deal_backfill_days": 1}}

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def payload(event):
    return event.get("payload") or {}


def judge(reported, venue_deals, settlements, strategy):
    """What is wrong with what qkt [reported] of the strategy's [venue_deals] and [settlements]: each is reported once
    under its strategy, as a leg of a position, with its realized from qkt's books."""
    by_ticket = {}
    for event in reported:
        by_ticket.setdefault(str(payload(event).get("dealTicket")), {})[event.get("id")] = payload(event)
    found = []
    for deal in venue_deals:
        rows = list(by_ticket.get(str(deal["fill_id"]), {}).values())
        if not rows:
            found.append(f"deals-reported-after-restart: venue deal {deal['fill_id']} of {deal['client_order_id']} was never reported")
            continue
        fact = rows[0]
        if fact.get("strategyId") != strategy:
            found.append(f"deals-reported-after-restart: deal {deal['fill_id']} reported for {fact.get('strategyId')}")
        if not fact.get("positionTicket") or fact.get("legEntry") not in ("IN", "OUT"):
            found.append(f"deals-reported-after-restart: deal {deal['fill_id']} reported without its leg: {fact}")
    fills = {str(d["fill_id"]) for d in venue_deals}
    settled = [p for tickets in by_ticket.items() if tickets[0] not in fills for p in tickets[1].values()
               if p.get("strategyId") == strategy and p.get("legEntry") != "IN"]
    if len(settled) < len(settlements):
        found.append(f"deals-reported-after-restart: {len(settlements)} settlement(s), {len(settled)} reported as a closing deal")
    return found

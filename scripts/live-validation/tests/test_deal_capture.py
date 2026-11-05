"""Offline tests of the deals-reported-after-restart verdict and the capture a daemon posts to. No JVM, no venue."""
import json, os, sys, unittest, urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../lib"))
import deal_capture  # noqa: E402

DEAL = {"fill_id": "f1", "client_order_id": "dsl-s--1.x"}


def event(ticket="f1", strategy="s", position="p1", entry="IN"):
    return {"id": f"deal-A-{ticket}", "type": "broker.deal", "payload": {
        "dealTicket": ticket, "strategyId": strategy, "positionTicket": position, "legEntry": entry}}


class Verdict(unittest.TestCase):
    def test_a_deal_reported_with_its_leg_passes(self):
        self.assertEqual(deal_capture.judge([event(), event()], [DEAL], [], "s"), [])

    def test_a_lost_deal_fails(self):
        self.assertIn("never reported", deal_capture.judge([], [DEAL], [], "s")[0])

    def test_a_deal_without_its_position_fails(self):
        self.assertIn("without its leg", deal_capture.judge([event(position=None)], [DEAL], [], "s")[0])

    def test_a_deal_reported_for_another_strategy_fails(self):
        self.assertIn("reported for", deal_capture.judge([event(strategy="z")], [DEAL], [], "s")[0])

    def test_a_settlement_needs_a_closing_deal(self):
        self.assertIn("settlement", deal_capture.judge([event()], [DEAL], [{"symbol": "X"}], "s")[0])
        self.assertEqual(deal_capture.judge([event(), event("settle-1", entry="OUT")], [DEAL], [{"symbol": "X"}], "s"), [])


class Capture(unittest.TestCase):
    def test_it_keeps_only_deal_events_and_answers_ok(self):
        capture = deal_capture.DealCapture()
        self.addCleanup(capture.close)
        url = capture.config()["insights"]["url"]
        body = json.dumps({"instanceId": "i", "events": [event(), {"type": "state.account"}]}).encode()
        with urllib.request.urlopen(urllib.request.Request(url, data=body, method="POST"), timeout=5) as reply:
            self.assertEqual(reply.status, 200)
        self.assertEqual(len(capture.deals), 1)


if __name__ == "__main__":
    unittest.main()

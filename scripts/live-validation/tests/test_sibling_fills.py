"""Offline tests of which unattributed fills a daemon case may pass. No JVM, no venue."""
import os, sys, unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../lib"))
from sibling_fills import strays  # noqa: E402


def accepted(session, order):
    return (f"20:24:00.856 [qkt-live-engine] INFO  [{session}] com.qkt.app.OrderManager - order accepted "
            f"order_id=dsl-{session}--2 strategy_id={session} broker_order_id={order}\n")


def dropped(session, order, client=None):
    client = f"mt5-close-{order}" if client is None else client
    return (f"20:24:00.990 [qkt-live-engine] WARN  [{session}] com.qkt.app.TradingPipeline - unattributed fill dropped: "
            f"order_id={client} broker_order_id={order} EXNESS:BTCUSD SELL qty=0.01 — position books not adjusted\n")


class Strays(unittest.TestCase):
    def test_a_close_another_session_accepted_passes(self):
        log = accepted("a", "101") + accepted("b", "202") + dropped("b", "101") + dropped("a", "202")
        self.assertEqual(strays(log, sibling_closes=True), [])

    def test_a_book_child_session_name_parses(self):
        # A portfolio child logs as `book:slot`; its sibling's close must pass like a standalone's.
        log = accepted("atto_mix:a", "101") + accepted("solo", "202") + dropped("atto_mix:a", "202") + dropped("solo", "101")
        self.assertEqual(strays(log, sibling_closes=True), [])

    def test_the_same_close_fails_without_the_option(self):
        log = accepted("a", "101") + dropped("b", "101")
        self.assertEqual(len(strays(log)), 1)

    def test_an_order_nobody_accepted_fails(self):
        log = accepted("a", "101") + dropped("b", "999")
        self.assertEqual(len(strays(log, sibling_closes=True)), 1)

    def test_an_order_id_that_only_starts_the_same_fails(self):
        log = accepted("a", "1012") + dropped("b", "101")
        self.assertEqual(len(strays(log, sibling_closes=True)), 1)

    def test_a_session_losing_its_own_order_fails(self):
        log = accepted("a", "101") + dropped("a", "101")
        self.assertEqual(len(strays(log, sibling_closes=True)), 1)

    def test_a_line_with_no_session_fails(self):
        log = accepted("a", "101") + dropped("main", "101")
        self.assertEqual(len(strays(log, sibling_closes=True)), 1)

    def test_a_fill_with_no_broker_id_fails(self):
        log = accepted("a", "null") + dropped("b", "null")
        self.assertEqual(len(strays(log, sibling_closes=True)), 1)

    def test_a_blank_client_id_is_still_seen(self):
        self.assertEqual(len(strays(accepted("a", "101") + dropped("b", "999", client=""))), 1)
        self.assertEqual(strays(accepted("a", "101") + dropped("b", "101", client=""), sibling_closes=True), [])

    def test_an_unreadable_line_fails(self):
        self.assertEqual(len(strays(accepted("a", "101") + "unattributed fill dropped: 101\n", sibling_closes=True)), 1)

    def test_a_clean_log_passes(self):
        self.assertEqual(strays(accepted("a", "101")), [])


if __name__ == "__main__":
    unittest.main()

"""A case's preflight and judging on a shared netting account (lib/strategy_scope.py, run-derivatives-lane-case.py).

A drill's position is often closed by something that is not the strategy (the gateway kill switch, the guardian, a
positions sweep, an operator), so the strategy's own deals over the lookback do not net. On a netting account the
venue's empty position on a symbol settles it: the strategy cannot hold what the account does not."""
import importlib.util, os, types, unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("gateway_lane", os.path.join(HERE, "../run-derivatives-lane-case.py"))
gateway_lane = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway_lane)
scope = gateway_lane.scope

BUY = {"client_order_id": "dsl-S-1.1", "symbol": "BTC-PERPETUAL", "side": "buy", "quantity": "10", "time": 1_000,
       "price": "1", "costs": []}


def position(symbol, quantity):
    return {"symbol": symbol, "quantity": quantity, "ticket": None}


class FakeRun(gateway_lane.Run):
    def __init__(self, deals, positions, orders=(), venue_fills=True):
        self.strategy, self.roll = "S", None
        self.started_ms = self.ended_ms = 0
        self.deals, self.positions, self.orders = list(deals), list(positions), list(orders)
        self.venue_fills, self.posted, self.deleted = venue_fills, [], []

    def get(self, path, body=None, method=None, key=None):
        if method == "DELETE":
            self.deleted.append(path)
            self.orders = [o for o in self.orders if not path.endswith(o["client_order_id"])]
            return {}
        if method == "POST" and path == "/v1/orders":
            self.posted.append(body)
            if self.venue_fills:
                self.deals.append(dict(BUY, client_order_id=body["client_order_id"], side=body["side"],
                                       quantity=body["quantity"], time=2_000))
            return dict(body, status="filled")
        if path.startswith("/v1/deals"):
            return {"deals": list(self.deals)}  # a gateway that ignores the window: the runner must not rely on it
        if path.startswith("/v1/settlements"):
            return {"settlements": []}
        if path == "/v1/positions":
            return {"positions": list(self.positions)}
        if path == "/v1/orders":
            return {"orders": list(self.orders)}
        raise AssertionError(path)


class PreflightTest(unittest.TestCase):
    def test_unbalanced_deals_on_a_symbol_the_account_does_not_hold_are_flat(self):
        run = FakeRun([BUY], [])  # the kill switch closed the buy under its own label
        self.assertTrue(run.preflight_clear(0))

    def test_size_the_account_holds_that_the_deals_attribute_to_the_strategy_is_swept_first(self):
        run = FakeRun([BUY], [position("BTC-PERPETUAL", "30")])  # the soak's size shares the account
        self.assertTrue(run.preflight_clear(0))
        self.assertEqual([(o["side"], o["quantity"], o["reduce_only"]) for o in run.posted], [("sell", "10", True)])
        self.assertEqual(run.leftovers_swept["held"], {"BTC-PERPETUAL": "10"})
        self.assertEqual(run.positions, [position("BTC-PERPETUAL", "30")])  # the account's other size is not touched

    def test_a_working_order_of_the_strategy_is_cancelled_first_another_user_s_is_left(self):
        run = FakeRun([], [], orders=[{"client_order_id": "dsl-S-2.2", "symbol": "BTC-PERPETUAL"},
                                      {"client_order_id": "soak-s01-1", "symbol": "BTC-PERPETUAL"}])
        self.assertTrue(run.preflight_clear(0))
        self.assertEqual(run.deleted, ["/v1/orders/dsl-S-2.2"])
        self.assertEqual(run.leftovers_swept["cancelled"], ["dsl-S-2.2"])

    def test_a_leftover_the_sweep_cannot_clear_still_refuses(self):
        run = FakeRun([BUY], [position("BTC-PERPETUAL", "30")], venue_fills=False)
        with mock.patch.object(gateway_lane, "LEFTOVER_FLAT_WAIT_S", 0), mock.patch("time.sleep"):
            self.assertFalse(run.preflight_clear(0))
        self.assertFalse(getattr(run, "owns_strategy", False))
        self.assertEqual(run.leftovers_swept["left"]["held"], {"BTC-PERPETUAL": "10"})

    def test_other_users_size_on_a_symbol_the_strategy_never_touched_does_not_refuse(self):
        run = FakeRun([BUY, dict(BUY, side="sell", time=2_000)], [position("ETH-PERPETUAL", "5")])
        self.assertTrue(run.preflight_clear(0))

    def test_account_held_symbols(self):
        self.assertEqual(scope.account_held({"A": 1, "B": -2}, [position("A", "0"), position("B", "-3")]), {"B": -2})


class CaseWindowTest(unittest.TestCase):
    def test_deals_before_the_case_never_reach_its_judging(self):
        run = FakeRun([BUY, dict(BUY, client_order_id="dsl-S-3.3", time=5_000)], [])
        run.started_ms = 4_000
        self.assertEqual([d["client_order_id"] for d in run.strategy_deals()], ["dsl-S-3.3"])
        self.assertEqual(run.holdings(), {"BTC-PERPETUAL": 10})

    def test_a_case_that_never_started_judges_only_from_its_preflight(self):
        run = FakeRun([BUY], [])
        run.started_ms = 0
        self.assertTrue(run.preflight_clear(0))
        self.assertGreater(run.started_ms, 0)
        self.assertTrue(run.flat())  # the sweep after a failed prepare does not reach back to the old buy


if __name__ == "__main__":
    unittest.main()

"""The gateway lanes on a shared account (lib/strategy_scope.py): a case judges and touches only its strategy's own
orders, deals and holding, never another user's, and says when the account's cash cannot judge it.

    python3 -m unittest discover -s scripts/live-validation/tests
"""
import json, os, subprocess, sys, tempfile, time, unittest
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "../lib"))
sys.path.insert(0, HERE)
import strategy_scope as scope  # noqa: E402
import test_derivatives_settlement as expiry  # noqa: E402

PERP = "BTC_USDC-PERPETUAL"


def deal(side, quantity, time_ms, cid="dsl-atto_perp--0.1", symbol=PERP, price="80000"):
    return {"client_order_id": cid, "symbol": symbol, "side": side, "quantity": quantity, "price": price,
            "time": time_ms, "costs": []}


class Attribution(unittest.TestCase):
    def test_both_engine_id_forms_are_the_strategy_s_and_nothing_else_is(self):
        self.assertTrue(scope.is_own("dsl-atto_perp--3.177", "atto_perp"))
        self.assertTrue(scope.is_own("ORD-atto_perp-0.177", "atto_perp"))
        self.assertFalse(scope.is_own("dsl-atto_perp_b--3.177", "atto_perp"))  # a strategy whose name extends it
        self.assertFalse(scope.is_own("soak-s01-9", "atto_perp"))
        self.assertFalse(scope.is_own(None, "atto_perp"))

    def test_the_holding_comes_from_the_strategy_s_own_deals_not_the_account_position(self):
        deals = [deal("buy", "0.002", 1), deal("buy", "0.5", 2, cid="soak-s01-1"), deal("sell", "0.001", 3)]
        self.assertEqual(scope.holdings(scope.own(deals, "atto_perp")), {PERP: Decimal("0.001")})

    def test_a_round_trip_beside_another_user_s_open_position_is_flat(self):
        deals = [deal("buy", "0.002", 1), deal("buy", "0.5", 2, cid="soak-s01-1"), deal("sell", "0.002", 3)]
        self.assertTrue(scope.is_flat([], deals, [], "atto_perp"))

    def test_a_working_order_of_the_strategy_is_not_flat_another_user_s_is_ignored(self):
        self.assertFalse(scope.is_flat([{"client_order_id": "dsl-atto_perp--4.1"}], [], [], "atto_perp"))
        self.assertTrue(scope.is_flat([{"client_order_id": "soak-s01-4"}], [], [], "atto_perp"))

    def test_a_contract_the_venue_settled_is_no_longer_held(self):
        settled = [{"symbol": "C-83000", "holding": Decimal("0.01")}]
        self.assertTrue(scope.is_flat([], [deal("buy", "0.01", 1, symbol="C-83000")], settled, "atto_perp"))


class Funding(unittest.TestCase):
    def test_the_strategy_owes_its_holding_over_the_account_position(self):
        record = {"funding_id": "f1", "symbol": PERP, "amount": "1.2", "position": "0.6", "time": 10}
        self.assertEqual(scope.funding_share(record, [deal("buy", "0.1", 1)]), Decimal("0.2"))

    def test_a_record_on_a_symbol_the_strategy_never_held_is_another_user_s(self):
        record = {"funding_id": "f1", "symbol": PERP, "amount": "1.2", "position": "0.5", "time": 10}
        self.assertEqual(scope.strategy_funding([record], [deal("buy", "0.1", 20)]), [])

    def test_funding_realized_after_the_close_falls_to_the_strategy_that_traded(self):
        record = {"funding_id": "f1", "symbol": PERP, "amount": "0.3", "position": "0", "time": 10}
        trip = [deal("buy", "0.1", 1), deal("sell", "0.1", 5)]
        self.assertEqual(scope.funding_share(record, trip), Decimal("0.3"))

    def test_a_record_without_a_position_is_the_holder_s_in_full(self):
        record = {"funding_id": "f1", "symbol": PERP, "amount": "0.3", "time": 10}
        self.assertEqual(scope.funding_share(record, [deal("sell", "0.1", 1)]), Decimal("0.3"))


class Sweep(unittest.TestCase):
    def test_closes_only_the_strategy_s_holding_under_its_own_id(self):
        orders = scope.sweep_orders({PERP: Decimal("0.002")}, [{"symbol": PERP, "quantity": "0.502"}], "atto_perp", 7)
        self.assertEqual(len(orders), 1)
        self.assertEqual((orders[0]["side"], orders[0]["quantity"], orders[0]["reduce_only"]), ("sell", "0.002", True))
        self.assertTrue(scope.is_own(orders[0]["client_order_id"], "atto_perp"))

    def test_a_close_against_another_user_s_opposite_size_is_not_reduce_only(self):
        orders = scope.sweep_orders({PERP: Decimal("-0.002")}, [{"symbol": PERP, "quantity": "0.5"}], "atto_perp", 7)
        self.assertEqual((orders[0]["side"], orders[0]["reduce_only"]), ("buy", False))


class Balance(unittest.TestCase):
    def test_another_user_s_deal_in_the_window_makes_the_balance_not_comparable(self):
        note = scope.balance_note([deal("buy", "0.1", 1), deal("buy", "0.5", 2, cid="soak-s01-1")], "atto_perp")
        self.assertTrue(note.startswith("not comparable"))

    def test_only_the_strategy_s_deals_leave_it_comparable(self):
        self.assertIsNone(scope.balance_note([deal("buy", "0.1", 1)], "atto_perp"))


class SharedGateway(expiry.FakeGateway):
    """The expiry case's gateway, shared: another user holds the perpetual, rests an order and trades during the case."""

    def __init__(self):
        super().__init__()
        self.foreign_position = {"symbol": PERP, "quantity": "0.5", "avg_price": "80000", "ticket": None}
        self.foreign_order = {"client_order_id": "soak-s01-7", "symbol": PERP, "side": "buy", "type": "limit",
                              "quantity": "0.1", "limit_price": "40000"}
        self.closes = []

    def answer(self, path, query):
        if path == "/v1/positions":
            return {"accounting": "netting", "positions": self.positions + [self.foreign_position]}
        if path == "/v1/orders":
            return {"orders": [self.foreign_order]}
        return super().answer(path, query)

    def act(self, path):
        if path == "/v1/positions/close":
            self.closes.append(path)
            return {}
        if path == "/test/buy":
            self.deals.append(deal("sell", "0.1", int(time.time() * 1000), cid="soak-s01-8"))
        return super().act(path)


class SharedAccountCase(unittest.TestCase):
    def test_a_case_beside_another_user_passes_leaves_their_holding_and_cannot_judge_the_cash(self):
        gateway = SharedGateway()
        self.addCleanup(gateway.server.server_close)
        self.addCleanup(gateway.server.shutdown)
        out = os.path.join(tempfile.mkdtemp(), "run")
        env = dict(os.environ, QKT_DERIV_GATEWAY_KEY="token", QKT_LIVE_DEMO_ORDER_APPROVAL="LOCALHOST_DEMO_ONLY",
                   FAKE_GATEWAY=gateway.url, FAKE_SETTLE="once")
        done = subprocess.run([sys.executable, expiry.RUNNER, "--case", expiry.CASE, "--out", out, "--gateway-url",
                               gateway.url, "--expected-login", "test-login", "--arm", "I_UNDERSTAND_DEMO_ORDER_0.01",
                               "--cli", os.path.join(HERE, "fake_qkt.py"), "--budget-seconds", "30"],
                              env=env, capture_output=True, text=True, timeout=90)
        with open(os.path.join(out, "result.json")) as file:
            result = json.load(file)
        self.assertEqual(result["status"], "passed", done.stdout + done.stderr)
        self.assertTrue(result["balanceNote"].startswith("not comparable"), result["balanceNote"])
        self.assertEqual(gateway.closes, [])  # the sweep never closed the account's (another user's) position
        self.assertEqual(Decimal(result["venueNet"]), Decimal(result["qktRealized"]))


if __name__ == "__main__":
    unittest.main()

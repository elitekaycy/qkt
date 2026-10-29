"""The gateway lane runner's money rules, offline: a coin fee is no cash, a venue without funding has none."""
import importlib.util, os, sys, unittest
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "../lib"))
spec = importlib.util.spec_from_file_location("lane", os.path.join(HERE, "../run-derivatives-lane-case.py"))
lane = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lane)


class CostValueTest(unittest.TestCase):
    def test_a_coin_fee_on_its_pair_took_no_cash(self):
        deal = {"symbol": "BTCUSD", "price": "81082.264"}
        self.assertEqual(lane.cost_value({"amount": "0.0000005", "currency": "BTC"}, deal), Decimal(0))
        self.assertEqual(lane.cost_value({"amount": "0.0000005", "currency": "BTC"}, {"symbol": "BTC_USDC", "price": "1"}),
                         Decimal(0))

    def test_a_fee_in_money_is_cash(self):
        deal = {"symbol": "BTCUSD", "price": "81082.264"}
        self.assertEqual(lane.cost_value({"amount": "0.04", "currency": "USD"}, deal), Decimal("0.04"))
        perp = {"symbol": "BTC-PERPETUAL", "price": "80000"}
        self.assertEqual(lane.cost_value({"amount": "0.0001", "currency": "USDC"}, perp), Decimal("0.0001"))


class AccountFundingTest(unittest.TestCase):
    @staticmethod
    def runner(answer):
        """A runner whose gateway answers every read with [answer], or raises it."""
        cls = next(v for v in vars(lane).values() if isinstance(v, type) and hasattr(v, "account_funding"))
        r = object.__new__(cls)
        r.started_ms, r.ended_ms = 0, 1

        def get(_path, *_args, **_kwargs):
            if isinstance(answer, Exception):
                raise answer
            return answer
        r.get = get
        return r

    def test_a_venue_that_serves_no_funding_has_none(self):
        import urllib.error
        r = self.runner(urllib.error.HTTPError("u", 501, "Not Implemented", {}, None))
        self.assertEqual(r.account_funding(), [])

    def test_records_are_read_whatever_the_capabilities(self):
        r = self.runner({"funding": [{"symbol": "ETH-PERPETUAL", "amount": "1"}]})
        self.assertEqual(r.account_funding(), [{"symbol": "ETH-PERPETUAL", "amount": "1"}])



class CopyCatalogTest(unittest.TestCase):
    def test_a_case_without_contracts_copies_its_instruments_only(self):
        import tempfile
        with tempfile.TemporaryDirectory() as data, tempfile.TemporaryDirectory() as root:
            open(os.path.join(data, "instruments.yaml"), "w").write("ALPACA:AAPL: {}\n")
            lane.copy_catalog(data, root)
            self.assertEqual(sorted(os.listdir(root)), ["instruments.yaml"])

    def test_a_case_with_contracts_copies_them_too(self):
        import tempfile
        with tempfile.TemporaryDirectory() as data, tempfile.TemporaryDirectory() as root:
            open(os.path.join(data, "instruments.yaml"), "w").write("")
            os.makedirs(os.path.join(data, "contracts", "DERIBIT"))
            open(os.path.join(data, "contracts", "DERIBIT", "c.yaml"), "w").write("")
            lane.copy_catalog(data, root)
            self.assertTrue(os.path.isfile(os.path.join(root, "contracts", "DERIBIT", "c.yaml")))

if __name__ == "__main__":
    unittest.main()

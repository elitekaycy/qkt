"""The gateway lanes' daemons treat their accounts as shared unless a case is about the refusal itself."""
import importlib.util, os, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("gateway_lane", os.path.join(HERE, "../run-derivatives-lane-case.py"))
gateway_lane = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway_lane)


class SharedAccountTest(unittest.TestCase):
    def test_a_case_is_shared_by_default(self):
        self.assertIs(gateway_lane.shared_account({}), True)

    def test_a_case_may_ask_for_the_refusal(self):
        self.assertIs(gateway_lane.shared_account({"shared_account": False}), False)


class FundingAtTheCloseTest(unittest.TestCase):
    BUY = {"symbol": "P", "side": "buy", "quantity": "1", "time": 100}
    SELL = {"symbol": "P", "side": "sell", "quantity": "1", "time": 200}

    def owed(self, *records):
        return [f["funding_id"] for f in gateway_lane.scope.strategy_funding(list(records), [self.BUY, self.SELL])]

    def test_a_record_stamped_just_after_the_close_is_owed(self):
        self.assertEqual(self.owed({"funding_id": "a", "symbol": "P", "amount": "2", "time": 201}), ["a"])

    def test_a_later_record_with_no_trade_since_is_someone_elses(self):
        self.assertEqual(self.owed({"funding_id": "a", "symbol": "P", "amount": "2", "time": 201},
                                   {"funding_id": "b", "symbol": "P", "amount": "2", "time": 300}), ["a"])

    def test_a_record_before_the_strategy_traded_is_not_owed(self):
        self.assertEqual(self.owed({"funding_id": "a", "symbol": "P", "amount": "2", "time": 50}), [])


if __name__ == "__main__":
    unittest.main()

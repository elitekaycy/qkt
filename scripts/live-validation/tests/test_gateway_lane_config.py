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


if __name__ == "__main__":
    unittest.main()

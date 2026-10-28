"""Which venue deals are a strategy's: lib/owned-deals.jq over the Exness demo's account-wide history of 2026-10-28."""
import json, os, subprocess, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
LIB = os.path.join(HERE, "../lib")
HISTORY = os.path.join(HERE, "fixtures/exness-stock-deals-2026-10-28.json")


def ask(expr, strategy):
    prefixes = json.dumps([f"dsl-{strategy}", f"ORD-{strategy}-"])
    out = subprocess.run(["jq", "-c", "-L", LIB, "--argjson", "prefixes", prefixes, f'include "owned-deals"; {expr}',
                          HISTORY], check=True, capture_output=True, text=True).stdout
    return json.loads(out)


class OwnedDealsTest(unittest.TestCase):
    def test_a_plain_ORD_entry_owns_its_close(self):
        # The DAY limit entered as ORD-atto_stk_day-1; its close carries no comment and lost 0.22.
        self.assertEqual(ask("deal_net($prefixes)", "atto_stk_day"), -0.22)
        self.assertEqual(ask("closing_deals($prefixes)", "atto_stk_day"), 1)

    def test_a_dsl_entry_still_owns_its_close(self):
        self.assertEqual(ask("deal_net($prefixes)", "atto_stk_bracket"), -0.27)
        self.assertEqual(ask("closing_deals($prefixes)", "atto_stk_bracket"), 1)

    def test_another_strategy_s_deals_are_not_counted(self):
        # atto_stk_reentry traded twice in the same history; neither round trip belongs to atto_stk_day.
        day = ask("[owned($prefixes)[].positionTicket] | unique", "atto_stk_day")
        reentry = ask("[owned($prefixes)[].positionTicket] | unique", "atto_stk_reentry")
        self.assertEqual(len(reentry), 2)
        self.assertFalse(set(day) & set(reentry))

    def test_a_strategy_with_no_deals_owns_none(self):
        self.assertEqual(ask("deal_net($prefixes)", "atto_absent"), 0)
        self.assertEqual(ask("closing_deals($prefixes)", "atto_absent"), 0)


if __name__ == "__main__":
    unittest.main()

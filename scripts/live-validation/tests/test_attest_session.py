"""Session-bound lanes against the clock: said up front, from the market's own window, and only the market's."""
import datetime as dt, os, sys, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "../lib"))
import attest_session as s  # noqa: E402

ALPACA = {"session": "nyse[09:30-16:00]", "venue_gateway": True, "budget_seconds": 420}


def utc(text):
    return dt.datetime.fromisoformat(text).replace(tzinfo=dt.timezone.utc)


class JudgeLaneTest(unittest.TestCase):
    # 2026-11-03 is a Tuesday in New York winter time: 09:30-16:00 New York is 14:30-21:00 UTC.
    def test_inside_with_room_is_fine(self):
        self.assertIsNone(s.judge_lane("alpaca", ALPACA["session"], 2400, 1800, utc("2026-11-03T14:30:00")))

    def test_a_run_too_late_is_told_now_with_the_latest_start(self):
        reason = s.judge_lane("alpaca", ALPACA["session"], 2400, 1800, utc("2026-11-03T20:00:00"))
        self.assertIn("closes Tue 21:00Z", reason)
        self.assertIn("latest start was Tue 19:50Z", reason)
        self.assertIn("start at or after Wed 14:00Z", reason)

    def test_a_run_that_would_arrive_before_the_open_is_told_when_to_start(self):
        reason = s.judge_lane("alpaca", ALPACA["session"], 2400, 1800, utc("2026-11-03T12:00:00"))
        self.assertIn("start at or after Tue 14:00Z", reason)

    def test_a_weekend_points_at_monday(self):
        reason = s.judge_lane("alpaca", ALPACA["session"], 2400, 0, utc("2026-11-07T15:00:00"))
        self.assertIn("Mon 14:30Z", reason)

    def test_a_resumed_run_with_no_lead_can_start_later(self):
        self.assertIsNone(s.judge_lane("alpaca", ALPACA["session"], 600, 0, utc("2026-11-03T20:45:00")))


class CanStartTest(unittest.TestCase):
    def test_a_case_must_fit_before_the_close(self):
        case = {"budget_seconds": 600}
        self.assertTrue(s.can_start(case, ALPACA, utc("2026-11-03T20:50:00")))
        self.assertFalse(s.can_start(case, ALPACA, utc("2026-11-03T20:51:00")))
        self.assertFalse(s.can_start(case, ALPACA, utc("2026-11-03T14:00:00")))

    def test_a_24_7_case_is_not_bound_by_its_lane(self):
        self.assertTrue(s.can_start({"session": "none"}, ALPACA, utc("2026-11-07T03:00:00")))

    def test_a_case_in_an_unbound_lane_always_may(self):
        self.assertTrue(s.can_start({}, {"budget_seconds": 300}, utc("2026-11-07T03:00:00")))


class LaneSecondsTest(unittest.TestCase):
    def test_a_serial_lane_sums_its_budgets_at_the_measured_fraction(self):
        self.assertEqual(s.lane_seconds(ALPACA, [{"budget_seconds": 600}, {"budget_seconds": 200}]), 660)

    def test_a_coin_case_does_not_count_against_the_share_window(self):
        self.assertEqual(s.lane_seconds(ALPACA, [{"session": "none"}]), 0)

    def test_a_parallel_lane_takes_its_longest_per_batch(self):
        lane = {"session": "nyse[09:40-15:45]", "budget_seconds": 300}
        self.assertEqual(s.lane_seconds(lane, [{"budget_seconds": 420}] * 8), 2 * 450)


if __name__ == "__main__":
    unittest.main()

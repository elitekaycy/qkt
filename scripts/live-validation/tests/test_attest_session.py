"""Session-bound lanes against the clock: said up front, from the market's own window, and only the market's."""
import datetime as dt, os, sys, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "../lib"))
import attest_session as s  # noqa: E402

ALPACA = {"session": "nyse[09:30-16:00]", "venue_gateway": True, "budget_seconds": 420}


def utc(text):
    return dt.datetime.fromisoformat(text).replace(tzinfo=dt.timezone.utc)


STOCKS = {"session": "nyse[09:40-15:45]", "budget_seconds": 420}


def lanes_at(alpaca=2700, stocks=900):
    return [("alpaca", ALPACA["session"], alpaca), ("stocks", STOCKS["session"], stocks)]


class ScheduleTest(unittest.TestCase):
    # 2026-11-05 is a Thursday in New York winter time: 09:30-16:00 New York is 14:30-21:00 UTC.
    def test_the_v0660_release_start_plans_instead_of_refusing(self):
        # Started Thu 20:48Z, reaching the catalog at 21:20Z (the evidence): both lanes wait for Friday's open.
        plan = s.schedule(lanes_at(), 32 * 60, utc("2026-11-05T20:48:00"))
        self.assertEqual(plan["problems"], [])
        by_lane = {i["lane"]: i for i in plan["lanes"]}
        self.assertEqual(by_lane["alpaca"]["verdict"], "wait"); self.assertEqual(by_lane["alpaca"]["startUtc"], "2026-11-06T14:30:00Z")
        self.assertEqual(by_lane["stocks"]["verdict"], "wait"); self.assertEqual(by_lane["stocks"]["startUtc"], "2026-11-06T14:40:00Z")
        self.assertIsNone(plan["holdUntilUtc"])  # 18 h: the evidence of the lanes run now is still young at assembly
        self.assertEqual(plan["summary"], "session lanes alpaca at Fri 14:30Z, stocks at Fri 14:40Z; other lanes now")
        self.assertIn("the market is closed when the run reaches it (Thu 21:20Z)", by_lane["stocks"]["reason"])

    def test_a_start_inside_the_window_runs_the_session_lanes_first(self):
        plan = s.schedule(lanes_at(), 0, utc("2026-11-03T15:00:00"))
        self.assertEqual([i["verdict"] for i in plan["lanes"]], ["now", "now"])
        self.assertIn("session lanes alpaca, stocks first", plan["summary"])
        self.assertIn("closes Tue 21:00Z", plan["lanes"][0]["reason"])

    def test_too_little_of_the_window_left_waits_for_the_next_one(self):
        verdict, start, text = s.plan_lane("alpaca", ALPACA["session"], 2400, utc("2026-11-03T20:30:00"), utc("2026-11-06T00:00:00"))
        self.assertEqual((verdict, s.iso(start)), ("wait", "2026-11-04T14:30:00Z"))
        self.assertIn("only 30 min of the window are left", text)

    def test_a_window_past_the_wait_limit_fails_with_the_reason(self):
        plan = s.schedule(lanes_at(), 0, utc("2026-11-06T21:00:00"), max_wait_h=24)
        self.assertEqual(plan["lanes"], [])
        self.assertIn("the alpaca lane's next window that fits its ~45 min opens Mon 14:30Z", plan["problems"][0])
        self.assertIn("past the session wait limit Sat 21:00Z", plan["problems"][0])

    def test_the_default_limit_covers_a_weekend_and_holds_the_run_to_keep_evidence_young(self):
        plan = s.schedule(lanes_at(), 1870, utc("2026-11-06T21:00:00"))
        self.assertEqual(plan["problems"], [])
        self.assertEqual([i["startUtc"] for i in plan["lanes"]], ["2026-11-09T14:30:00Z", "2026-11-09T14:40:00Z"])
        # Running the 24/7 lanes on Friday would make their evidence ~66 h old by Monday's bundle: hold until 14:30Z
        # less the lead to the catalog and 15 min, so everything runs together inside Monday's window.
        self.assertEqual(plan["holdUntilUtc"], "2026-11-09T13:43:50Z")
        self.assertIn("whole run held until Mon 13:43Z", plan["summary"])

    def test_a_lane_longer_than_its_window_can_never_run(self):
        plan = s.schedule([("stocks", STOCKS["session"], 9 * 3600)], 0, utc("2026-11-03T15:00:00"))
        self.assertIn("longer than its whole nyse[09:40-15:45] window", plan["problems"][0])

    def test_no_work_left_is_no_plan(self):
        plan = s.schedule([], 0, utc("2026-11-07T03:00:00"))
        self.assertEqual((plan["lanes"], plan["problems"], plan["holdUntilUtc"]), ([], [], None))


class WaitUntilTest(unittest.TestCase):
    def test_a_wait_is_logged_recorded_and_slept_through(self):
        import json, tempfile
        with tempfile.TemporaryDirectory() as tmp:
            clock = os.path.join(tmp, "clock"); open(clock, "w").write("2026-11-05T21:20:00Z")
            record = os.path.join(tmp, "waits.jsonl")
            os.environ[s.CLOCK_FILE] = clock
            try:
                s.wait_until(utc("2026-11-06T14:40:00"), "stocks", "closed", record)
                self.assertEqual(s.iso(s.now()), "2026-11-06T14:40:00Z")
            finally:
                del os.environ[s.CLOCK_FILE]
            entry = json.loads(open(record).read())
            self.assertEqual(entry, {"lane": "stocks", "plannedStartUtc": "2026-11-06T14:40:00Z", "reason": "closed",
                                     "recordedAtUtc": "2026-11-05T21:20:00Z"})

    def test_a_real_wait_sleeps_in_short_steps(self):
        slept = []
        start = dt.datetime.now(dt.timezone.utc)
        def fake_sleep(seconds):
            slept.append(seconds)
            if len(slept) > 3:
                raise StopIteration
        with self.assertRaises(StopIteration):
            s.wait_until(start + dt.timedelta(hours=2), "alpaca", "closed", None, sleep=fake_sleep)
        self.assertTrue(all(x <= 600 for x in slept))


class CanStartTest(unittest.TestCase):
    """The safety net under the plan: whatever it said, a case starts only if it can finish inside its window."""
    def test_a_case_must_fit_before_the_close(self):
        case = {"budget_seconds": 600}
        self.assertTrue(s.can_start(case, ALPACA, utc("2026-11-03T20:50:00")))
        self.assertFalse(s.can_start(case, ALPACA, utc("2026-11-03T20:51:00")))
        self.assertFalse(s.can_start(case, ALPACA, utc("2026-11-03T14:00:00")))

    def test_a_24_7_case_is_not_bound_by_its_lane(self):
        self.assertTrue(s.can_start({"session": "none"}, ALPACA, utc("2026-11-07T03:00:00")))

    def test_a_case_in_an_unbound_lane_always_may(self):
        self.assertTrue(s.can_start({}, {"budget_seconds": 300}, utc("2026-11-07T03:00:00")))

    def test_the_v0660_start_time_is_still_refused_per_case(self):
        self.assertFalse(s.can_start({}, STOCKS, utc("2026-11-05T21:20:00")))
        self.assertFalse(s.can_start({}, ALPACA, utc("2026-11-05T21:20:00")))
        self.assertTrue(s.can_start({}, STOCKS, utc("2026-11-06T14:40:00")))


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

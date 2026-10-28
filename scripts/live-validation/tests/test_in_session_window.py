"""The stocks lane's session window, judged on New York time: edges, daylight saving and weekends."""
import os, sys, unittest
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "../lib"))
from in_session_window import in_window  # noqa: E402


def utc(text):
    return datetime.fromisoformat(text).replace(tzinfo=timezone.utc)


class InWindowTest(unittest.TestCase):
    def test_the_open_minute_is_in_and_the_minute_before_is_not(self):
        # 2026-10-28 is New York summer time: 09:30 New York is 13:30 UTC.
        self.assertTrue(in_window("nyse[09:30-15:45]", utc("2026-10-28T13:30:00")))
        self.assertFalse(in_window("nyse[09:30-15:45]", utc("2026-10-28T13:29:59")))

    def test_the_close_is_out(self):
        self.assertTrue(in_window("nyse[09:30-15:45]", utc("2026-10-28T19:44:59")))
        self.assertFalse(in_window("nyse[09:30-15:45]", utc("2026-10-28T19:45:00")))

    def test_winter_time_moves_the_window_an_hour_in_utc(self):
        # 2026-11-02, after New York left summer time on 2026-11-01: 09:30 New York is 14:30 UTC.
        self.assertFalse(in_window("nyse[09:30-15:45]", utc("2026-11-02T14:29:00")))
        self.assertTrue(in_window("nyse[09:30-15:45]", utc("2026-11-02T14:30:00")))

    def test_a_weekend_is_out(self):
        self.assertFalse(in_window("nyse[09:30-15:45]", utc("2026-10-31T15:00:00")))

    def test_a_session_that_is_not_a_window_is_refused(self):
        with self.assertRaises(ValueError):
            in_window("fx", utc("2026-10-28T15:00:00"))


if __name__ == "__main__":
    unittest.main()

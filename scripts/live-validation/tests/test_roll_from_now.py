"""A roll generated from the clock: minutes ahead, at any hour, across a midnight, on the front contract."""
import datetime as dt, os, sys, tempfile, unittest

import yaml

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../lib"))
import roll_from_now as r  # noqa: E402


def ms(text):
    return int(dt.datetime.fromisoformat(text).replace(tzinfo=dt.timezone.utc).timestamp() * 1000)


class RollFromNowTest(unittest.TestCase):
    EXPIRIES = [ms("2026-11-06T08:00:00"), ms("2026-11-13T08:00:00"), ms("2026-12-25T08:00:00")]

    def test_the_roll_is_the_next_minute_past_the_lead(self):
        days, at, front = r.choose(ms("2026-11-02T15:40:30"), self.EXPIRIES, 3)
        self.assertEqual((days, at, front), (4, "15:44", self.EXPIRIES[0]))

    def test_the_date_and_time_recompute_to_the_chosen_instant(self):
        for now in ("2026-11-02T15:40:30", "2026-11-05T23:59:30", "2026-11-06T07:55:10", "2026-11-07T03:00:00"):
            days, at, front = r.choose(ms(now), self.EXPIRIES, 3)
            hours, minutes = map(int, at.split(":"))
            date = dt.datetime.fromtimestamp(front / 1000, dt.timezone.utc).date() - dt.timedelta(days=days)
            instant = dt.datetime(date.year, date.month, date.day, hours, minutes, tzinfo=dt.timezone.utc)
            lead = instant.timestamp() - ms(now) / 1000
            self.assertTrue(180 <= lead <= 240, (now, lead))

    def test_a_contract_expiring_before_the_roll_is_not_the_front(self):
        self.assertEqual(r.choose(ms("2026-11-06T07:58:00"), self.EXPIRIES, 3)[2], self.EXPIRIES[1])

    def test_one_contract_left_is_not_a_roll(self):
        with self.assertRaisesRegex(RuntimeError, "two listed contracts"):
            r.choose(ms("2026-11-02T00:00:00"), self.EXPIRIES[:1], 3)

    def test_the_file_is_rewritten_keeping_the_rest_of_the_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = f"{tmp}/i.yaml"
            yaml.safe_dump({"futures": [{"root": "DERIBIT:BTC_USDC", "tickSize": 2.5,
                                         "roll": {"daysBeforeExpiry": 1, "atUtc": "08:00", "adjust": "none", "anchor": "latest"}}]}, open(path, "w"))
            instruments = [{"kind": "future", "code": f"BTC_USDC-{i}", "expiry": e} for i, e in enumerate(self.EXPIRIES)]
            chosen = r.apply(path, {"root": "DERIBIT:BTC_USDC"}, instruments, ms("2026-11-02T15:40:30"))
            root = yaml.safe_load(open(path))["futures"][0]
            self.assertEqual(root["roll"], {"daysBeforeExpiry": 4, "atUtc": "15:44", "adjust": "none", "anchor": "latest"})
            self.assertEqual(chosen["atUtc"], "15:44"); self.assertEqual(root["tickSize"], 2.5)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Session-bound lanes against the clock: when the market lets a lane run, and whether a run reaches it in time.

The window is the market's own (attestation/lanes.yaml), New York time on weekdays; holidays and early closes are the
venue's to report and show up as a stale quote or a refused order. A case with `session: none` trades a 24/7 market
and is not bound by its lane's window. The only limit here is the market being closed: nothing waits for an hour.

    attest_session.py can-start --lanes FILE --case-yaml FILE    exits 0 when the case can start and finish in time
"""
import argparse, datetime as dt, math, os, re, sys
from zoneinfo import ZoneInfo

import yaml

NEW_YORK = ZoneInfo("America/New_York")
SESSION = re.compile(r"nyse\[(\d\d):(\d\d)-(\d\d):(\d\d)\]")
# Stage seconds before the catalog (build and wave measured on 2026-10-30; proofs estimated now they run side by side);
# a resumed run counts only those it must repeat.
STAGE_SECONDS = {"build": 90, "proofs": 900, "wave": 880}
# A serial gateway lane leaves as soon as a case is flat: it took 2360 s of 3240 s of budgets on 2026-10-30.
SERIAL_TYPICAL_FRACTION = 0.75
CASE_OVERHEAD_SECONDS = 30


def window_on(session, day):
    h1, m1, h2, m2 = map(int, SESSION.fullmatch(session).groups())
    return (dt.datetime(day.year, day.month, day.day, h1, m1, tzinfo=NEW_YORK),
            dt.datetime(day.year, day.month, day.day, h2, m2, tzinfo=NEW_YORK))


def current_or_next_window(session, at):
    """The window `at` is in, or the next one: (open, close), in New York time."""
    day = at.astimezone(NEW_YORK).date()
    for _ in range(8):
        if day.weekday() < 5:
            opens, closes = window_on(session, day)
            if at < closes:
                return opens, closes
        day += dt.timedelta(days=1)
    raise ValueError(f"no window within a week: {session}")


def budget_of(case, lane_config):
    return int(case.get("budget_seconds", lane_config.get("budget_seconds", 420)))


def lane_cases(root, lane, keep=()):
    """The lane's ready cases still to run, as parsed case.yaml documents."""
    folder = os.path.join(root, "attestation", "cases", lane)
    found = []
    for case_id in sorted(os.listdir(folder)) if os.path.isdir(folder) else []:
        path = os.path.join(folder, case_id, "case.yaml")
        if os.path.exists(path) and f"{lane}-{case_id}" not in keep:
            doc = yaml.safe_load(open(path))
            if doc.get("status") == "ready":
                found.append(doc)
    return found


def lane_seconds(lane_config, cases, slots=7):
    """Roughly how long the lane's session-bound cases need: in turn on one account, or side by side."""
    budgets = [budget_of(c, lane_config) for c in cases if c.get("session") != "none"]
    if not budgets:
        return 0
    if lane_config.get("venue_gateway"):
        return int(sum(budgets) * SERIAL_TYPICAL_FRACTION) + CASE_OVERHEAD_SECONDS * len(budgets)
    return math.ceil(len(budgets) / slots) * (max(budgets) + CASE_OVERHEAD_SECONDS)


def judge_lane(lane, session, seconds, lead_seconds, now):
    """None when a run started now reaches and finishes the lane inside its window, else the reason, said up front."""
    reach = now + dt.timedelta(seconds=lead_seconds)
    finish = reach + dt.timedelta(seconds=seconds)
    opens, closes = current_or_next_window(session, reach)
    z = lambda t: t.astimezone(dt.timezone.utc).strftime("%a %H:%MZ")
    latest = closes - dt.timedelta(seconds=seconds + lead_seconds)
    if reach < opens:
        return (f"the {lane} lane opens {z(opens)} ({session} New York) but this run would reach it at {z(reach)} and the "
                f"catalog refuses it then; start at or after {z(opens - dt.timedelta(seconds=lead_seconds))}")
    if finish > closes:
        return (f"the {lane} lane closes {z(closes)} ({session} New York) but this run would reach it at {z(reach)} and needs "
                f"about {seconds // 60} min; latest start was {z(latest)}, or start at or after "
                f"{z(current_or_next_window(session, closes + dt.timedelta(minutes=1))[0] - dt.timedelta(seconds=lead_seconds))}")
    return None


def can_start(case, lane_config, now):
    session = lane_config.get("session")
    if case.get("session") == "none" or not session:
        return True
    opens, closes = current_or_next_window(session, now)
    return opens <= now and now + dt.timedelta(seconds=budget_of(case, lane_config)) <= closes


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)
    p = sub.add_parser("can-start")
    p.add_argument("--lanes", required=True); p.add_argument("--case-yaml", required=True); p.add_argument("--at")
    a = ap.parse_args()
    case = yaml.safe_load(open(a.case_yaml))
    lane = os.path.basename(os.path.dirname(os.path.dirname(os.path.abspath(a.case_yaml))))
    config = yaml.safe_load(open(a.lanes))["lanes"][lane]
    now = dt.datetime.fromisoformat(a.at.replace("Z", "+00:00")) if a.at else dt.datetime.now(dt.timezone.utc)
    return 0 if can_start(case, config, now) else 1


if __name__ == "__main__":
    sys.exit(main())

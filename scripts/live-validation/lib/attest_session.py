#!/usr/bin/env python3
"""Session-bound lanes against the clock: when the market lets a lane run, and when a run started now should run it.

The window is the market's own (attestation/lanes.yaml), New York time on weekdays; holidays and early closes are the
venue's to report and show up as a stale quote or a refused order. A case with `session: none` trades a 24/7 market
and is not bound by its lane's window.

A release can start at any hour. A session lane that the run reaches inside a window it fits in runs first; otherwise it
waits, in process, for the next window it fits in, while every other lane runs now. Only a window further away than the
wait limit (profile `session_wait_max_h`, default 72 h: a weekend) stops the run, at preflight. When waiting would let
the first evidence age past the verifier's limit before the bundle is assembled, the whole run is held until shortly
before the window instead, so every lane runs together. Whatever the plan says, a case never starts unless it can
finish inside its window (`can-start`): the plan only orders and times the lanes.

    attest_session.py can-start --lanes FILE --case-yaml FILE [--at ISO]   exits 0 when the case can start and finish in time
    attest_session.py in-window --lanes FILE --lane LANE                   exits 0 inside the lane's window now
    attest_session.py lane-plan --lanes FILE --repo-root DIR --lane LANE --deadline ISO [--keep-file FILE]
        prints `none`, `now CLOSES TEXT`, `wait START TEXT` or `fail TEXT`
    attest_session.py deadline --hours H                                   prints now + H hours, ISO UTC
    attest_session.py wait-until --until ISO --lane LANE --reason TEXT --record FILE
        logs the planned wait, appends it to FILE (jsonl), then sleeps until ISO

QKT_ATTEST_CLOCK_FILE (the tests' clock) names a file holding the current time as ISO UTC; wait-until moves it forward
instead of sleeping.
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
DEFAULT_WAIT_MAX_H = 72  # covers a weekend: a Friday-evening start reaches Monday's open in about 66 h
# A held run starts this long before the lead to the window would bring it there, so it is waiting, not late.
HOLD_EARLY_SECONDS = 15 * 60
# After the last session lane: the insights stage, the bundle and the verify, before the evidence ages are judged.
EVIDENCE_TAIL_SECONDS = 2 * 3600
CLOCK_FILE = "QKT_ATTEST_CLOCK_FILE"


def parse_utc(text):
    return dt.datetime.fromisoformat(text.strip().replace("Z", "+00:00")).astimezone(dt.timezone.utc)


def iso(t):
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def z(t):
    return t.astimezone(dt.timezone.utc).strftime("%a %H:%MZ")


def now():
    path = os.environ.get(CLOCK_FILE)
    return parse_utc(open(path).read()) if path else dt.datetime.now(dt.timezone.utc)


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


def lane_start(session, seconds, reach):
    """The first moment at or after `reach` from which `seconds` of work fit inside one window: (start, close)."""
    opens, closes = current_or_next_window(session, reach)
    if seconds > (closes - opens).total_seconds():
        raise ValueError(f"needs about {seconds // 60} min, longer than its whole {session} window")
    start = max(reach, opens)
    if start + dt.timedelta(seconds=seconds) > closes:
        opens, closes = current_or_next_window(session, closes + dt.timedelta(minutes=1))
        start = opens
    return start, closes


def plan_lane(lane, session, seconds, reach, deadline):
    """What a run that reaches the lane at `reach` does with it: ("now", close, text), ("wait", start, text) or
    ("fail", None, reason) when no window it fits in opens before `deadline`."""
    try:
        start, closes = lane_start(session, seconds, reach)
    except ValueError as error:
        return "fail", None, f"the {lane} lane {error}"
    if start > deadline:
        return "fail", None, (f"the {lane} lane's next window that fits its ~{seconds // 60} min opens {z(start)} "
                              f"({session} New York), past the session wait limit {z(deadline)}; raise session_wait_max_h "
                              f"or start at or after {z(start - (deadline - reach))}")
    if start <= reach:
        return "now", closes, f"the {lane} lane runs first, ~{seconds // 60} min before it closes {z(closes)}"
    opens, closes_then = current_or_next_window(session, reach)
    if opens <= reach:
        why = (f"only {int((closes_then - reach).total_seconds()) // 60} min of the window are left when the run reaches it "
               f"(closes {z(closes_then)}) and it needs ~{seconds // 60} min")
    else:
        why = f"the market is closed when the run reaches it ({z(reach)})"
    return "wait", start, f"the {lane} lane waits for its window {z(start)} ({session} New York): {why}"


def schedule(lanes, lead_seconds, at, max_wait_h=DEFAULT_WAIT_MAX_H, max_age_h=None):
    """The plan for every session lane with session-bound work left: lanes is [(lane, session, seconds)].

    Returns {"lanes": [...], "problems": [...], "holdUntilUtc": ISO or None, "summary": TEXT}."""
    if max_age_h is None:
        import attest_cases
        max_age_h = attest_cases.MAX_EVIDENCE_AGE_HOURS
    reach = at + dt.timedelta(seconds=lead_seconds)
    deadline = at + dt.timedelta(hours=float(max_wait_h))
    items, problems = [], []
    for lane, session, seconds in lanes:
        verdict, when, text = plan_lane(lane, session, seconds, reach, deadline)
        if verdict == "fail":
            problems.append(text)
            continue
        start = when if verdict == "wait" else reach
        items.append({"lane": lane, "session": session, "seconds": seconds, "verdict": verdict, "startUtc": iso(start),
                      "reason": text})
    items.sort(key=lambda i: (i["startUtc"], i["lane"]))
    waiting = [i for i in items if i["verdict"] == "wait"]
    hold = None
    if waiting and not problems:
        last = max(parse_utc(i["startUtc"]) + dt.timedelta(seconds=i["seconds"]) for i in items)
        if (last - reach).total_seconds() + EVIDENCE_TAIL_SECONDS > max_age_h * 3600:
            hold = min(parse_utc(i["startUtc"]) for i in waiting) - dt.timedelta(seconds=lead_seconds + HOLD_EARLY_SECONDS)
            hold = hold if hold > at else None
    return {"lanes": items, "problems": problems, "holdUntilUtc": iso(hold) if hold else None,
            "summary": summary(items, problems, hold, max_age_h)}


def summary(items, problems, hold, max_age_h):
    if problems:
        return "; ".join(problems)
    if not items:
        return "no session lane has work left"
    now_lanes = [i["lane"] for i in items if i["verdict"] == "now"]
    later = ", ".join(f"{i['lane']} at {z(parse_utc(i['startUtc']))}" for i in items if i["verdict"] == "wait")
    parts = []
    if hold:
        parts.append(f"whole run held until {z(hold)} so no evidence is older than {max_age_h} h at assembly")
    if now_lanes:
        parts.append(f"session lanes {', '.join(now_lanes)} first, inside their window")
    if later:
        parts.append(f"session lanes {later}")
    parts.append("other lanes now" if not hold else "other lanes with them")
    return "; ".join(parts)


def can_start(case, lane_config, at):
    session = lane_config.get("session")
    if case.get("session") == "none" or not session:
        return True
    opens, closes = current_or_next_window(session, at)
    return opens <= at and at + dt.timedelta(seconds=budget_of(case, lane_config)) <= closes


def in_window(session, at):
    opens, closes = current_or_next_window(session, at)
    return opens <= at < closes


def wait_until(until, lane, reason, record, sleep=None):
    """Logs and records a planned wait, then sleeps through it in short steps (a suspended host wakes on time)."""
    import json, time
    entry = {"lane": lane, "plannedStartUtc": iso(until), "reason": reason, "recordedAtUtc": iso(now())}
    if record:
        os.makedirs(os.path.dirname(os.path.abspath(record)), exist_ok=True)
        with open(record, "a") as stream:
            stream.write(json.dumps(entry) + "\n")
    print(f"{iso(now())} waiting until {iso(until)} for the {lane} lane: {reason}", flush=True)
    clock = os.environ.get(CLOCK_FILE)
    if clock:
        if until > now():
            open(clock, "w").write(iso(until))
        return
    while (left := (until - now()).total_seconds()) > 0:
        (sleep or time.sleep)(min(left, 600))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)
    p = sub.add_parser("can-start")
    p.add_argument("--lanes", required=True); p.add_argument("--case-yaml", required=True); p.add_argument("--at")
    p = sub.add_parser("in-window")
    p.add_argument("--lanes", required=True); p.add_argument("--lane", required=True)
    p = sub.add_parser("lane-plan")
    for flag in ("lanes", "repo-root", "lane", "deadline"):
        p.add_argument(f"--{flag}", required=True)
    p.add_argument("--keep-file")
    p = sub.add_parser("deadline"); p.add_argument("--hours", type=float, required=True)
    p = sub.add_parser("wait-until")
    for flag in ("until", "lane", "reason"):
        p.add_argument(f"--{flag}", required=True)
    p.add_argument("--record")
    a = ap.parse_args()
    if a.command == "deadline":
        print(iso(now() + dt.timedelta(hours=a.hours)))
        return 0
    if a.command == "wait-until":
        wait_until(parse_utc(a.until), a.lane, a.reason, a.record)
        return 0
    if a.command == "in-window":
        session = yaml.safe_load(open(a.lanes))["lanes"][a.lane].get("session")
        return 0 if not session or in_window(session, now()) else 1
    if a.command == "lane-plan":
        config = yaml.safe_load(open(a.lanes))["lanes"][a.lane]
        keep = set(open(a.keep_file).read().split()) if a.keep_file and os.path.exists(a.keep_file) else set()
        seconds = lane_seconds(config, lane_cases(a.repo_root, a.lane, keep))
        if seconds == 0 or not config.get("session"):
            print("none")
            return 0
        at = now()
        verdict, when, text = plan_lane(a.lane, config["session"], seconds, at, parse_utc(a.deadline))
        print(verdict, *([iso(when)] if when else []), text)
        return 0
    case = yaml.safe_load(open(a.case_yaml))
    lane = os.path.basename(os.path.dirname(os.path.dirname(os.path.abspath(a.case_yaml))))
    config = yaml.safe_load(open(a.lanes))["lanes"][lane]
    return 0 if can_start(case, config, parse_utc(a.at) if a.at else now()) else 1


if __name__ == "__main__":
    sys.exit(main())

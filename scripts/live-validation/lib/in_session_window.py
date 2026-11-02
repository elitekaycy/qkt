"""Exits 0 when now (or --at, ISO UTC) falls inside a lane's `nyse[HH:MM-HH:MM]` session on a weekday in New York.

Holidays and early closes are not judged here: the venue's own last quote decides those."""
import argparse, re, sys
from datetime import datetime, time, timezone
from zoneinfo import ZoneInfo

import yaml


def in_window(session: str, at: datetime) -> bool:
    match = re.fullmatch(r"nyse\[(\d\d):(\d\d)-(\d\d):(\d\d)\]", session)
    if not match:
        raise ValueError(f"not a nyse[HH:MM-HH:MM] session: {session}")
    h1, m1, h2, m2 = map(int, match.groups())
    local = at.astimezone(ZoneInfo("America/New_York"))
    return local.weekday() < 5 and time(h1, m1) <= local.time() < time(h2, m2)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("lanes_yaml")
    parser.add_argument("lane")
    parser.add_argument("--at", help="ISO UTC instant to judge instead of now")
    args = parser.parse_args()
    session = yaml.safe_load(open(args.lanes_yaml))["lanes"][args.lane]["session"]
    at = datetime.fromisoformat(args.at.replace("Z", "+00:00")) if args.at else datetime.now(timezone.utc)
    return 0 if in_window(session, at) else 1


if __name__ == "__main__":
    sys.exit(main())

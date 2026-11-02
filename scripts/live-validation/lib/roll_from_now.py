"""Makes a futures roll happen minutes after a case starts, on any day at any hour, instead of at the root's fixed time.

A root's roll is `daysBeforeExpiry` days before a contract's expiry date at `atUtc`, so both are chosen from now and the
front contract: the one that expires soonest after the roll. Backtest and live read the same generated file.
"""
import datetime as dt

import yaml


def choose(now_ms, expiries_ms, lead_minutes):
    """(daysBeforeExpiry, atUtc, front expiry ms): the next minute boundary at least `lead_minutes` away is the roll."""
    roll = (now_ms // 60_000 + lead_minutes + 1) * 60_000
    later = sorted(e for e in expiries_ms if e > roll)
    if len(later) < 2:
        raise RuntimeError("venue-untradeable: a roll needs two listed contracts expiring after it")
    instant = dt.datetime.fromtimestamp(roll / 1000, dt.timezone.utc)
    front = dt.datetime.fromtimestamp(later[0] / 1000, dt.timezone.utc)
    return (front.date() - instant.date()).days, instant.strftime("%H:%M"), later[0]


def apply(path, spec, instruments, now_ms):
    """Rewrites `spec.root`'s roll in the instruments file at `path`; returns the choice for the case's evidence."""
    family = spec["root"].split(":")[1]
    expiries = [i["expiry"] for i in instruments if i["kind"] == "future" and i.get("expiry")
                and i["code"].replace("-", "_").startswith(family + "_")]
    days, at_utc, front = choose(now_ms, expiries, int(spec.get("lead_minutes", 3)))
    with open(path) as stream:
        doc = yaml.safe_load(stream)
    root = next(r for r in doc["futures"] if r["root"] == spec["root"])
    root["roll"] = {**(root.get("roll") or {}), "daysBeforeExpiry": days, "atUtc": at_utc}
    with open(path, "w") as stream:
        yaml.safe_dump(doc, stream, sort_keys=False)
    return {"root": spec["root"], "daysBeforeExpiry": days, "atUtc": at_utc, "frontExpiryMs": front}

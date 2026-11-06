"""Names why an attestation case failed, and how many more attempts that cause earns.

Three causes, judged from the case's own problems and its log's tail:
  infrastructure  the gateway or venue did not answer (timeouts, 5xx, refused connections, venue link down);
  environment     this machine cannot run the case (disk full, a tool or file missing); retrying cannot help;
  behaviour       anything else, above all a failed assertion. It is the default: an unknown message is never
                  excused as the network's fault, and one assertion among transport errors makes it behaviour.
A fourth, session, is set by the runner when a session-bound case could not run because the market closed.
Two more come only from a gateway lane case's recorded venue evidence (lib/venue_health.py), never from its text:
  venue-outage      the attempt failed while samples taken during it show the venue unavailable, and every problem is
                    what an outage causes (a transport error, too few fills, not flat, a sweep that could not finish).
                    It reruns once the venue is back without spending the case's other budgets (bounded by
                    VENUE_RERUNS). A wrong number, a failed replay or drill is behaviour, outage or not. When the
                    evidence holds the venue's own refusals of the case's orders because their book was closed
                    (`closedBookRefusals`, e.g. 11030 orderbook_disabled) and the attempt filled nothing, what could
                    then not happen (a roll's legs, a drill that waits for a fill, an expected log line) is what the
                    outage caused too; anything that did happen and should not have (reject_log) stays behaviour;
  venue-unavailable the venue stayed unavailable past the profile's wait maximum before the case could start: final, so
                    a resumed release (lib/attest_resume.py) reruns only such cases once the venue is back.
Retrying only ever re-runs the whole case with every assertion: no class can turn a failure into a pass.
"""
import os
import re

INFRASTRUCTURE = "infrastructure"
ENVIRONMENT = "environment"
BEHAVIOUR = "behaviour"
SESSION = "session"
VENUE_OUTAGE = "venue-outage"
VENUE_UNAVAILABLE = "venue-unavailable"

BEHAVIOUR_RETRIES = 1
INFRASTRUCTURE_BACKOFF_SECONDS = (30, 60, 120)
VENUE_RERUNS = 8  # attempts an outage may void in one run; each next one first waits for the venue (venue_health)
VENUE_RERUN_DELAY_SECONDS = 60

_INFRA = re.compile(
    r"timed out|TimeoutError|Connection refused|Connection reset|Remote end closed|RemoteDisconnected"
    r"|Network is unreachable|Temporary failure in name resolution|curl: \((6|7|18|28|35|52|56)\)"
    r"|HTTP Error 5(?!01)\d\d|\bHTTP 5(?!01)\d\d\b|Bad Gateway|Service Unavailable|Gateway Timeout"
    r"|venue link|venue_connected|venue is (down|disconnected)|venue-untradeable"
    r"|gateway is not logged into|gateway is not healthy|no answer after \d+ attempts", re.I)
_ENV = re.compile(
    r"No space left|Errno 28|Errno 2\]|Errno 13|Permission denied|Cannot allocate memory|command not found"
    r"|\bis required$|\bmust be set$|output already exists|required command not found", re.I)
# The runner died without saying why: the reason, if any, is in the log.
_VAGUE = re.compile(r"runner exited unexpectedly|daemon exited during startup|could not pull instrument specs"
                    r"|wrote no result|runner error|venue deal history could not be read", re.I)


def _line_class(line, log_tail):
    if _ENV.search(line):
        return ENVIRONMENT
    if _INFRA.search(line):
        return INFRASTRUCTURE
    if _VAGUE.search(line):
        for earlier in reversed(log_tail):
            if _ENV.search(earlier):
                return ENVIRONMENT
            if _INFRA.search(earlier):
                return INFRASTRUCTURE
    return BEHAVIOUR


# What a venue outage itself causes, beside transport errors: the case could not trade, close, or sweep in time.
_OUTAGE_EFFECT = re.compile(r"^(expected \d+ fills within the budget, saw \d+|flat-by-strategy:|sweep:)")


# What a closed book causes in an attempt that filled nothing: whatever was to follow a fill never happened.
_NOTHING_TRADED_EFFECT = re.compile(r"^(roll-[a-z-]+: |\w+: never fired|expect_log: the daemon log never matched|"
                                    r"resume: the strategy never stopped)")


def venue_class(result, problems, log_tail=()):
    """SESSION, VENUE_UNAVAILABLE or VENUE_OUTAGE when the attempt's own recorded evidence ([result]'s `sessionClosed`:
    a venue wait took a session-bound case past its window; `venueWaits`; `venueOutage`) says so, else None. The
    problems' text alone never makes any of them."""
    result = result or {}
    if result.get("sessionClosed"):
        return SESSION
    if any(w.get("exceeded") for w in result.get("venueWaits") or []):
        return VENUE_UNAVAILABLE
    outage = result.get("venueOutage") or {}
    if not outage.get("unavailableSamples"):
        return None
    lines = [p for p in problems if p and p.strip()]
    nothing_traded = bool(outage.get("closedBookRefusals")) and not result.get("fills") and not result.get("rollLegs")

    def caused(line):
        return (_OUTAGE_EFFECT.search(line) or _line_class(line, log_tail) == INFRASTRUCTURE
                or (nothing_traded and _NOTHING_TRADED_EFFECT.search(line)))
    if lines and all(caused(line) for line in lines):
        return VENUE_OUTAGE
    return None


def classify(problems, log_tail=()):
    """The class of a failed attempt, from its problems (or its last log line when it wrote none)."""
    lines = [p for p in problems if p and p.strip()] or list(log_tail)[-1:]
    if not lines:
        return BEHAVIOUR
    classes = {_line_class(line, log_tail) for line in lines}
    for cause in (BEHAVIOUR, ENVIRONMENT, INFRASTRUCTURE):
        if cause in classes:
            return cause
    return BEHAVIOUR


def next_step(cause, earlier_causes):
    """(retry?, seconds to wait first), given this run's earlier failed attempts of the case."""
    if cause == BEHAVIOUR:
        return earlier_causes.count(BEHAVIOUR) < BEHAVIOUR_RETRIES, 0
    if cause == VENUE_OUTAGE:
        delay = int(os.environ.get("QKT_ATTEST_VENUE_RERUN_DELAY", VENUE_RERUN_DELAY_SECONDS))
        reruns = int(os.environ.get("QKT_ATTEST_VENUE_RERUNS", VENUE_RERUNS))
        return earlier_causes.count(VENUE_OUTAGE) < reruns, delay
    if cause == INFRASTRUCTURE:
        used = earlier_causes.count(INFRASTRUCTURE)
        backoff = os.environ.get("QKT_ATTEST_RETRY_BACKOFF")  # the tests' way past the waits
        delays = [int(d) for d in backoff.split(",")] if backoff else INFRASTRUCTURE_BACKOFF_SECONDS
        return (True, delays[used]) if used < len(delays) else (False, 0)
    return False, 0

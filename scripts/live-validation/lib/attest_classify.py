"""Names why an attestation case failed, and how many more attempts that cause earns.

Three causes, judged from the case's own problems and its log's tail:
  infrastructure  the gateway or venue did not answer (timeouts, 5xx, refused connections, venue link down);
  environment     this machine cannot run the case (disk full, a tool or file missing); retrying cannot help;
  behaviour       anything else, above all a failed assertion. It is the default: an unknown message is never
                  excused as the network's fault, and one assertion among transport errors makes it behaviour.
A fourth, session, is set by the runner when a session-bound case could not run because the market closed.
Retrying only ever re-runs the whole case with every assertion: no class can turn a failure into a pass.
"""
import os
import re

INFRASTRUCTURE = "infrastructure"
ENVIRONMENT = "environment"
BEHAVIOUR = "behaviour"
SESSION = "session"

BEHAVIOUR_RETRIES = 1
INFRASTRUCTURE_BACKOFF_SECONDS = (30, 60, 120)

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
    if cause == INFRASTRUCTURE:
        used = earlier_causes.count(INFRASTRUCTURE)
        backoff = os.environ.get("QKT_ATTEST_RETRY_BACKOFF")  # the tests' way past the waits
        delays = [int(d) for d in backoff.split(",")] if backoff else INFRASTRUCTURE_BACKOFF_SECONDS
        return (True, delays[used]) if used < len(delays) else (False, 0)
    return False, 0

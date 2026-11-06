"""Whether a gateway lane's venue can trade right now, sampled and kept as evidence, so a third-party outage never
spends one of a case's attempts and never passes one either.

A venue is available when its gateway answers `/v1/health` with `venue_connected: true` and, where the lane knows the
venue's own instrument state (a Deribit gateway, with QKT_ATTEST_DERIBIT_URL set), every instrument the case will
trade is `open` on the venue's `public/ticker` with a fresh timestamp: what its strategy names that the gateway still
lists unexpired, and what the case picks at run time, resolved as the runner and the daemon resolve it
(lib/venue_instruments.py: a `@front` stream's current and next contract, the dated contract the runner picks, each
option structure's legs). Anything
else - the gateway refusing connections or answering 5xx, the link down, an instrument `locked`, a stale ticker, the
venue's own API answering 5xx - is unavailable, with the reason recorded. A 4xx from the gateway is a real error
(a wrong token, a wrong path), never an outage: it is raised.

Used by the gateway lane runners (run-derivatives-lane-case.py, each gateway leg of run-cross-lane-case.py):
  - `await_available` before the case touches the account: polls until the venue is available, at most
    QKT_ATTEST_VENUE_WAIT_MAX_S (default 6 h), each wait logged and returned as evidence; past the maximum it raises
    VenueUnavailable (`venue unavailable for Xh: <reason>`), which the catalog records as `venue-unavailable`;
  - `Watch` while the case runs: samples every QKT_ATTEST_VENUE_WATCH_S and keeps every unavailable sample. Only those
    samples - never a failure's text alone - let the catalog rerun a failed attempt without spending its budget
    (attest_classify.venue_class). A failed attempt also records each order the venue refused because its book or
    instrument was closed (`11030 orderbook_disabled`, `instrument_not_open`, `10019 locked_by_admin`, from the daemon
    log), with a state sample taken then (`Watch.record_refusals`): the venue's own word that it would not trade.
Waits and outages are logged to stderr (the case log) and, when QKT_ATTEST_NOTICE_FD names an open descriptor (the
release's own log, set by run-attestation.sh), there too.
"""
import json, os, re, sys, threading, time, urllib.error, urllib.parse, urllib.request

DEFAULT_WAIT_MAX_S = 6 * 3600
DEFAULT_POLL_S = 60
DEFAULT_WATCH_S = 30
TICKER_FRESH_MS = 120_000
MAX_KEPT_SAMPLES = 200
MAX_KEPT_REFUSALS = 20
# A venue refusal that says the book or instrument is closed (Deribit: 11030 other_reject orderbook_disabled, 10019
# locked_by_admin, instrument_not_open), as the engine logs it: `Order rejected: <id> reason=<venue's reason>`.
CLOSED_BOOK = re.compile(r"orderbook_disabled|orderbook_closed|instrument_not_open|locked_by_admin|\berror 10019\b",
                         re.I)
REFUSED = re.compile(r"Order rejected: (\S+) reason=(.*)$")


def env_seconds(name, default):
    value = os.environ.get(name)
    return float(value) if value else float(default)


def now_ms():
    return int(time.time() * 1000)


def utc(ms):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ms / 1000))


def notice(message):
    """One line to the case log and, when the release gave one, to the release log."""
    line = f"{time.strftime('%H:%M:%S', time.gmtime())} venue: {message}"
    print(line, file=sys.stderr, flush=True)
    fd = os.environ.get("QKT_ATTEST_NOTICE_FD")
    if fd:
        try:
            os.write(int(fd), (line + "\n").encode())
        except (OSError, ValueError):
            pass


class VenueUnavailable(RuntimeError):
    def __init__(self, label, seconds, reason, wait):
        super().__init__(f"venue unavailable for {seconds / 3600:.1f}h: {label}: {reason}")
        self.wait = wait


def strategy_symbols(text):
    """The venue symbols (BROKER:NAME) a strategy names, as qkt spells them (`-` as `_`); the gateway's listing decides
    which are its own (venue_codes)."""
    return sorted(set(re.findall(r"\b[A-Z][A-Z0-9_]*:([A-Za-z0-9_]+)", text)))


def venue_codes(names, instruments, at_ms):
    """The gateway codes of [names] that it still lists unexpired: what the venue must quote for the case to run. An
    index, a stale dated literal the case replaces at run time, or an expired contract has nothing to check."""
    codes = []
    for instrument in instruments:
        if instrument["code"].replace("-", "_") in names and not (instrument.get("expiry") and instrument["expiry"] <= at_ms):
            codes.append(instrument["code"])
    return sorted(codes)


def http_json(url, key=None, timeout=15):
    request = urllib.request.Request(url)
    if key:
        request.add_header("Authorization", f"Bearer {key}")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


class Probe:
    """One gateway venue's availability. [codes] are refreshed lazily from the strategy's [names] and the gateway's
    listing; [deribit_url] (QKT_ATTEST_DERIBIT_URL by default) enables the instrument-state check on a Deribit gateway."""

    def __init__(self, label, gateway_url, key, names=(), deribit_url=None, resolve=None):
        """[resolve](instruments, at_ms, quotes_of) gives the codes the case picks at run time beyond [names]
        (lib/venue_instruments.py); quotes_of(currency) reads the venue's option quotes."""
        self.label, self.gateway_url, self.key, self.names = label, gateway_url, key, set(names)
        self.deribit_url = deribit_url if deribit_url is not None else os.environ.get("QKT_ATTEST_DERIBIT_URL")
        self.resolve, self.codes, self.tracked, self.adapter = resolve, None, set(), None

    def track(self, codes):
        """Adds what the case picked once it ran (a roll's pair) to what every later sample checks."""
        self.tracked |= set(codes)
        if self.codes is not None:
            self.codes = sorted(set(self.codes) | self.tracked)

    def option_quotes(self, currency):
        """The venue's option quotes of [currency] (instrument_name, mark_iv, underlying_price); none when unreadable,
        and a leg then falls back to its expiry's median strike."""
        query = urllib.parse.urlencode({"currency": currency, "kind": "option"})
        try:
            return http_json(f"{self.deribit_url}/public/get_book_summary_by_currency?{query}")["result"]
        except (OSError, ValueError, KeyError, TypeError):
            return []

    def sample(self):
        """{time, available, reason[, instruments]}: one look at the venue now."""
        at = now_ms()
        try:
            health = http_json(self.gateway_url + "/v1/health", self.key)
        except urllib.error.HTTPError as error:
            if error.code < 500:
                raise
            return {"time": at, "available": False, "reason": f"gateway /v1/health answered HTTP {error.code}"}
        except (OSError, ValueError) as error:
            return {"time": at, "available": False, "reason": f"gateway /v1/health unreachable: {error}"}
        self.adapter = health.get("adapter")
        if not health.get("venue_connected"):
            return {"time": at, "available": False, "reason": "gateway has no venue link (venue_connected false)"}
        if health.get("adapter") == "deribit" and self.deribit_url and (self.names or self.resolve):
            return self.deribit_sample(at)
        return {"time": at, "available": True, "reason": "venue_connected"}

    def deribit_sample(self, at):
        try:
            if self.codes is None:
                listing = http_json(self.gateway_url + "/v1/instruments", self.key)
                codes = venue_codes(self.names, listing, at)
                if self.resolve:
                    codes += self.resolve(listing, at, self.option_quotes)
                self.codes = sorted(set(codes) | self.tracked)
        except urllib.error.HTTPError as error:
            if error.code < 500:
                raise
            return {"time": at, "available": False, "reason": f"gateway /v1/instruments answered HTTP {error.code}"}
        except (OSError, ValueError) as error:
            return {"time": at, "available": False, "reason": f"gateway /v1/instruments unreachable: {error}"}
        states = {}
        for code in self.codes:
            url = f"{self.deribit_url}/public/ticker?instrument_name={urllib.parse.quote(code)}"
            try:
                ticker = http_json(url)["result"]
            except urllib.error.HTTPError as error:
                if error.code < 500:  # the venue no longer knows it (expired, delisted): nothing to wait for
                    continue
                return {"time": at, "available": False, "reason": f"Deribit public/ticker {code} answered HTTP {error.code}"}
            except (OSError, ValueError, KeyError, TypeError) as error:
                return {"time": at, "available": False, "reason": f"Deribit public/ticker {code} unreachable: {error}"}
            state, stamp = ticker.get("state"), int(ticker.get("timestamp") or 0)
            states[code] = {"state": state, "timestamp": stamp}
            if state != "open":
                return {"time": at, "available": False, "reason": f"Deribit {code} is {state}", "instruments": states}
            if at - stamp > TICKER_FRESH_MS:
                return {"time": at, "available": False, "instruments": states,
                        "reason": f"Deribit {code} ticker is stale ({(at - stamp) // 1000}s old)"}
        return {"time": at, "available": True, "reason": "venue_connected; instruments open", "instruments": states}


def closed_book_refusals(log_text):
    """The engine's log lines of orders the venue refused because the book or instrument was closed, each once."""
    seen, lines = set(), []
    for line in log_text.splitlines():
        refused = REFUSED.search(line)
        if refused and CLOSED_BOOK.search(refused.group(2)) and refused.group(1) not in seen:
            seen.add(refused.group(1))
            lines.append(line.strip()[:400])
    return lines


def refusal_reason(lines):
    """`venue refused N order(s): the book is closed (11030 orderbook_disabled)`, from the first refusal."""
    first = lines[0]
    code = re.search(r"\berror (\d+)", first)
    return (f"venue refused {len(lines)} order(s): the book is closed "
            f"({(code.group(1) + ' ') if code else ''}{CLOSED_BOOK.search(first).group(0)})")


def await_available(probes, max_s=None, poll_s=None, sleep=None):
    """Blocks until every probe's venue is available; returns the waits (evidence: what was down, from, to, seconds,
    samples taken). Raises VenueUnavailable once the venue has been unavailable for more than [max_s]."""
    max_s = env_seconds("QKT_ATTEST_VENUE_WAIT_MAX_S", DEFAULT_WAIT_MAX_S) if max_s is None else max_s
    poll_s = env_seconds("QKT_ATTEST_VENUE_POLL_S", DEFAULT_POLL_S) if poll_s is None else poll_s
    sleep = sleep or time.sleep
    waits = []
    for probe in probes:
        first = probe.sample()
        if first["available"]:
            continue
        wait = {"venue": probe.label, "from": utc(first["time"]), "fromMs": first["time"], "reason": first["reason"],
                "samples": 1, "firstSample": first}
        notice(f"{probe.label} unavailable ({first['reason']}); waiting up to {max_s / 3600:.1f}h before the case starts")
        while True:
            sleep(poll_s)
            current = probe.sample()
            wait["samples"] += 1
            waited = (current["time"] - first["time"]) / 1000
            if current["available"]:
                wait.update(to=utc(current["time"]), toMs=current["time"], seconds=round(waited, 1), lastReason=wait["reason"])
                notice(f"{probe.label} available again after {waited / 60:.1f} min; the case starts")
                break
            wait["reason"] = current["reason"]
            if waited > max_s:
                wait.update(to=utc(current["time"]), toMs=current["time"], seconds=round(waited, 1), exceeded=True)
                waits.append(wait)
                notice(f"{probe.label} still unavailable after {waited / 3600:.1f}h ({current['reason']}); giving up")
                raise VenueUnavailable(probe.label, waited, current["reason"], waits)
        waits.append(wait)
    return waits


class Watch:
    """Samples every probe while a case runs and keeps the unavailable samples: the evidence an outage happened."""

    def __init__(self, probes, every_s=None):
        self.probes = probes
        self.every_s = env_seconds("QKT_ATTEST_VENUE_WATCH_S", DEFAULT_WATCH_S) if every_s is None else every_s
        self.samples, self.down, self.errors, self.refused = 0, [], [], 0
        self._stop = threading.Event()
        self._thread = None

    def look(self):
        for probe in self.probes:
            try:
                sample = probe.sample()
            except Exception as error:  # a 4xx: not an outage, never evidence of one
                self.errors.append(str(error)[:200])
                continue
            self.samples += 1
            if not sample["available"]:
                if not self.down:
                    notice(f"{probe.label} became unavailable during the case: {sample['reason']}")
                if len(self.down) < MAX_KEPT_SAMPLES:
                    self.down.append(dict(sample, venue=probe.label))

    def record_refusals(self, log_text):
        """Records each order the venue refused because its book was closed ([log_text]: the daemon log) as outage
        evidence, beside a state sample of the venue's instruments taken now. A refusal is attributed to the probes
        whose adapter it names (`deribit error ...`), to every probe when it names none of them."""
        lines = closed_book_refusals(log_text)
        if not lines:
            return
        named = [p for p in self.probes if p.adapter and any(p.adapter in line.lower() for line in lines)]
        for probe in named or self.probes:
            mine = [line for line in lines if not named or probe.adapter in line.lower()]
            try:
                state = probe.sample()
            except Exception as error:  # a 4xx: the refusal is still the venue's own word
                state = {"error": str(error)[:200]}
            reason = refusal_reason(mine)
            notice(f"{probe.label}: {reason}")
            self.refused += len(mine)
            self.down.append({"time": now_ms(), "available": False, "venue": probe.label, "reason": reason,
                              "refusals": mine[:MAX_KEPT_REFUSALS], "refusedOrders": len(mine), "stateSample": state})

    def start(self):
        def loop():
            while not self._stop.wait(self.every_s):
                self.look()
        self._thread = threading.Thread(target=loop, daemon=True)
        self._thread.start()
        return self

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=60)
            self._thread = None

    def outage(self):
        """The evidence of an outage during the case ({samples, unavailable: [...], from, to}), or None."""
        if not self.down:
            return None
        found = {"samplesTaken": self.samples, "unavailableSamples": len(self.down), "unavailable": self.down,
                 "from": utc(self.down[0]["time"]), "to": utc(self.down[-1]["time"]),
                 "venues": sorted({d["venue"] for d in self.down}), "reason": self.down[-1]["reason"]}
        if self.refused:
            found["closedBookRefusals"] = self.refused
        return found

#!/usr/bin/env python3
"""Which derivatives cases may run at the same time on the one Deribit testnet account, and on which gateway.

A netting account holds one position per instrument, and qkt's shared-account check (GatewayOtherHolders) judges
every code a daemon trades: a second daemon moving the same code is a refusal, a second case's fills are a wrong
verdict. So two cases may overlap only when they trade disjoint instruments. Each ready case names a `group:` in its
case.yaml; the cases of a group run one after another, the groups side by side. A case's *claims* are the
instrument classes it trades, derived from the case itself, never written twice:

    DERIBIT:BTC_USDC:front       a root's `@front` roll: its nearest two expiries (under 6 days out)
    DERIBIT:BTC_USDC:dated       a dated contract picked 7 to 45 days from expiry (`dated_from_root`, or a literal)
    DERIBIT:BTC_USDC:perpetual   the root's perpetual
    DERIBIT:BTC_USDC:options     the root's options (a structure's legs, or an expiring option)

Two groups must not share a claim (`problems`, run by attestation/lib/validate.py). The two futures classes of one
root never meet: a roll refuses a pair that reaches 6 days (run-derivatives-lane-case.py), a dated pick starts at 7.
At run time every case also takes a lock per claim (lib/claim_lock.py), so a wrong grouping, or the cross lane's
Deribit leg trading the BTC perpetual, queues instead of colliding.

A case whose drills include `kill_switch` acts on the gateway process (the switch is per process and a daemon refuses
to start while one is on), so each such case runs against a gateway of its own, from the `--drill-urls` pool, in the
order of the case ids. `gateway_outage` is not one of them: it cuts the daemon's own loopback proxy
(derivatives_drills.TcpProxy), never the gateway, and `qkt_restart` touches only the daemon; both share the base gateway.

    deriv_parallel.py plan --cases DIR --base-url URL --drill-urls URL,URL [--kept FILE] [--lane derivatives]
        prints `group<TAB>case-id<TAB>gateway-url` per pending case, the heaviest group first
    deriv_parallel.py claims --case DIR        prints `--claim X` lines for claim_lock.py
    deriv_parallel.py check-gateways --urls URL,URL --login LOGIN --base-url URL   (QKT_DERIV_GATEWAY_KEY)
"""
import argparse, json, os, re, sys, urllib.request

import yaml

DEFAULT_BUDGET_S = 420
GROUP = re.compile(r"[a-z0-9]+(-[a-z0-9]+)*")
_OPTION = re.compile(r"^(?P<root>[A-Z0-9]+_[A-Z0-9]+)_\d{1,2}[A-Z]{3}\d{2}_\d+(\.\d+)?_[CP]$")
_DATED = re.compile(r"^(?P<root>[A-Z0-9]+_[A-Z0-9]+)_\d{1,2}[A-Z]{3}\d{2}$")


class PlanError(ValueError):
    pass


def claim_of_symbol(symbol):
    """The instrument class one qkt symbol trades: `DERIBIT:BTC_USDC@front` is `DERIBIT:BTC_USDC:front`."""
    venue, _, name = symbol.partition(":")
    if venue in ("OPTIONS", "CHAIN"):  # OPTIONS:DERIBIT.BTC_USDC, CHAIN:DERIBIT.BTC_USDC.atm_iv.30d
        broker, root = name.split(".")[:2]
        return f"{broker}:{root}:options"
    if name.endswith("@front"):
        return f"{venue}:{name[:-len('@front')]}:front"
    if name.endswith("_PERPETUAL"):
        return f"{venue}:{name[:-len('_PERPETUAL')]}:perpetual"
    if (match := _OPTION.match(name)):
        return f"{venue}:{match['root']}:options"
    if (match := _DATED.match(name)):
        return f"{venue}:{match['root']}:dated"
    return f"{venue}:{name}:other"


def claims_of(case):
    """Every instrument class the case trades. A cross case claims only the legs on a Deribit gateway venue, under
    the Deribit lane's own name (`DERIBIT`), whatever label its config gives the venue."""
    labels = {v["label"]: v for v in case.get("venues") or [] if isinstance(v, dict) and "label" in v}
    claims = set()
    for symbol in case.get("symbols") or []:
        if labels:
            leg = labels.get(symbol.partition(":")[0])
            if not leg or leg.get("adapter") != "deribit":
                continue
            symbol = "DERIBIT:" + symbol.partition(":")[2]
        claims.add(claim_of_symbol(symbol))
    if case.get("dated_from_root"):  # the case's literal symbol is only the text it replaces
        claims.discard(claim_of_symbol(case["symbols"][0]))
        venue, _, root = case["dated_from_root"].partition(":")
        claims.add(f"{venue}:{root}:dated")
    roll = case.get("continuous_roll")
    if roll:
        claims.add(claim_of_symbol(roll["stream"]))
    return claims


def kill_switch(case):
    return any(d.get("kind") == "kill_switch" for d in case.get("drills") or [])


def ready_cases(cases_dir):
    """`(id, case.yaml)` of every ready case under [cases_dir], by id."""
    found = []
    for name in sorted(os.listdir(cases_dir)):
        path = os.path.join(cases_dir, name, "case.yaml")
        if os.path.isfile(path):
            with open(path) as stream:
                doc = yaml.safe_load(stream) or {}
            if doc.get("status") == "ready":
                found.append((name, doc))
    return found


def problems(cases):
    """What stops [cases] (`(id, case.yaml)` of one lane's ready cases) from running in parallel groups safely."""
    found, owners = [], {}
    groups = {doc.get("group") for _, doc in cases}
    for cid, doc in cases:
        group = doc.get("group")
        if not isinstance(group, str) or not GROUP.fullmatch(group):
            found.append(f"{cid}: a ready derivatives case names its `group:` (lowercase a-z0-9-)")
            continue
        for claim in sorted(claims_of(doc)):
            owner = owners.setdefault(claim, (group, cid))
            if owner[0] != group:
                found.append(f"{cid}: group {group} claims {claim}, which {owner[1]} holds in group {owner[0]}; "
                             "groups run side by side, so they may not share an instrument class")
        if "account-balance-equals-realized" in (doc.get("assertions") or []) and len(groups) > 1:
            found.append(f"{cid}: account-balance-equals-realized reads the account's whole cash, which every other "
                         "group's deals move; keep such a case scheduled, outside the parallel groups")
    return found


def plan(cases_dir, base_url, drill_urls, kept=(), lane="derivatives"):
    """`(group, case id, gateway url)` of every pending ready case: the heaviest group first (the sum of its cases'
    budgets), a group's cases by id. A `kill_switch` case gets the pool's next gateway, indexed over all ready ones
    so a resumed run keeps each case on the gateway it had."""
    cases = ready_cases(cases_dir)
    found = problems(cases)
    if found:
        raise PlanError("; ".join(found))
    switches = [cid for cid, doc in cases if kill_switch(doc)]
    if len(drill_urls) < len(switches):
        raise PlanError(f"{len(switches)} kill_switch cases ({', '.join(switches)}) each need a gateway of their own, "
                        f"and --deriv-drill-gateway-url was given {len(drill_urls)}")
    urls = [base_url, *drill_urls]
    if len(set(urls)) != len(urls):
        raise PlanError(f"the base gateway and each drill gateway must be different processes: {urls}")
    own = dict(zip(switches, drill_urls))
    pending = [(cid, doc) for cid, doc in cases if f"{lane}-{cid}" not in set(kept)]
    weight = {}
    for _, doc in pending:
        weight[doc["group"]] = weight.get(doc["group"], 0) + int(doc.get("budget_seconds", DEFAULT_BUDGET_S))
    order = sorted(weight, key=lambda g: (-weight[g], g))
    return [(doc["group"], cid, own.get(cid, base_url)) for g in order for cid, doc in pending if doc["group"] == g]


def check_gateways(urls, login, base_url, key, fetch=None):
    """What is wrong with the gateways a parallel lane will use: each must answer /v1/health as the expected demo
    account, venue connected, kill switch off. The base gateway is checked too: a drill pool beside a gateway on another
    account would pass every case on the wrong one."""
    fetch = fetch or _health
    found = []
    for url in [base_url, *urls]:
        try:
            health = fetch(url, key)
        except Exception as error:  # noqa: BLE001 - any failure is the answer
            found.append(f"{url}: /v1/health failed: {error}")
            continue
        if str(health.get("account_login")) != login:
            found.append(f"{url}: logged into {health.get('account_login')}, not {login}")
        if health.get("trade_mode") != "demo":
            found.append(f"{url}: trade mode {health.get('trade_mode')}, not demo")
        if not health.get("venue_connected"):
            found.append(f"{url}: no venue link")
        switch = health.get("kill_switch") or {}
        if switch.get("all") or switch.get("symbols"):
            found.append(f"{url}: its kill switch is on: {switch}")
    return found


def _health(url, key):
    request = urllib.request.Request(url + "/v1/health", headers={"Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)
    p = sub.add_parser("plan")
    p.add_argument("--cases", required=True); p.add_argument("--base-url", required=True)
    p.add_argument("--drill-urls", default=""); p.add_argument("--kept"); p.add_argument("--lane", default="derivatives")
    p = sub.add_parser("claims"); p.add_argument("--case", required=True)
    p = sub.add_parser("check-gateways")
    p.add_argument("--urls", required=True); p.add_argument("--login", required=True); p.add_argument("--base-url", required=True)
    a = ap.parse_args(argv)
    if a.command == "claims":
        with open(os.path.join(a.case, "case.yaml")) as stream:
            doc = yaml.safe_load(stream) or {}
        for claim in sorted(claims_of(doc)):
            print("--claim")
            print(claim)
        return 0
    if a.command == "check-gateways":
        key = os.environ.get("QKT_DERIV_GATEWAY_KEY") or sys.exit("QKT_DERIV_GATEWAY_KEY must be set")
        found = check_gateways([u for u in a.urls.split(",") if u], a.login, a.base_url, key)
        for line in found:
            print(f"deriv-parallel: {line}", file=sys.stderr)
        return 1 if found else 0
    kept = open(a.kept).read().split() if a.kept and os.path.exists(a.kept) else []
    try:
        entries = plan(a.cases, a.base_url, [u for u in a.drill_urls.split(",") if u], kept, a.lane)
    except PlanError as error:
        print(f"deriv-parallel: {error}", file=sys.stderr)
        return 1
    for group, cid, url in entries:
        print(f"{group}\t{cid}\t{url}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

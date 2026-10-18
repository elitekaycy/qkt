"""The suite's checks: each compares what qkt stored, or what `qkt data bars` reads back, with an
independent read of the vendor's file or the vendor's own data at another resolution. A check returns a
one-line summary when it passes and raises CheckFailure naming the first differences when it does not.
"""

import csv
import io
from decimal import Decimal
from pathlib import Path

import oracle as o


class CheckFailure(Exception):
    pass


READERS = {
    "binance_trades": lambda c, f: o.binance_trades(f, agg=c.get("agg", False)),
    "binance_klines": lambda c, f: o.binance_klines(f),
    "bybit_trades": lambda c, f: o.bybit_trades(f),
    "histdata_ticks": lambda c, f: o.histdata_ticks(f),
    "histdata_m1": lambda c, f: o.histdata_m1(f),
    "okx_trades": lambda c, f: o.okx_trades(f),
    "dukascopy_bi5_day": lambda c, f: o.dukascopy_bi5_day(f, c["divisor"]),
    "dukascopy_candles": lambda c, f: o.dukascopy_candles(f, c["divisor"]),
    "yfinance_bars": lambda c, f: o.yfinance_bars(f, c.get("ticker")),
    "qkt_bars_csv": lambda c, f: read_bars_csv(f),
    "bybit_kline": lambda c, f: o.bybit_kline_json([f]),
    "ccxt_ohlcv": lambda c, f: o.ccxt_ohlcv(f),
    "alphavantage_bars": lambda c, f: o.alphavantage_bars(f, c.get("zone")),
    "mt5_bars_mid": lambda c, f: o.mt5_bars_mid(f, c["clock"], c["point"]),
}


def run(spec: dict, case) -> str:
    kind = spec["kind"]
    fn = globals().get(f"check_{kind}")
    if fn is None:
        raise CheckFailure(f"unknown check kind {kind!r}")
    return fn(spec, case)


def _files(spec: dict, case, key: str = "file") -> list[Path]:
    names = spec[key] if isinstance(spec[key], list) else [spec[key]]
    return [Path(case.sub(n if n.startswith("{") else "{corpus}/" + n)) for n in names]


def _read(spec: dict, case, key: str = "file"):
    reader = READERS[spec["reader"]]
    out = []
    for f in _files(spec, case, key):
        out.extend(reader(spec, f))
    return out


def stored_ticks(case, symbol: str) -> list[o.Tick]:
    days = sorted((case.root / "symbols" / symbol).glob("*.csv.gz"))
    if not days:
        raise CheckFailure(f"no stored tick days for {symbol}")
    return [t for d in days for t in o.read_qkt_ticks(d)]


def read_bars_csv(path: Path) -> list[o.Bar]:
    rows = csv.DictReader(io.StringIO(Path(path).read_text()))
    return [o.Bar(int(r["timestamp"]), *(Decimal(r[k]) for k in ("open", "high", "low", "close", "volume"))) for r in rows]


def _first(problems: list[str], n: int = 6) -> str:
    return "; ".join(problems[:n]) + (f" … and {len(problems) - n} more" if len(problems) > n else "")


def check_ticks_equal(spec: dict, case) -> str:
    """Every stored tick equals the independent read, in order: time, price, volume, bid, ask."""
    want = _read(spec, case)
    got = stored_ticks(case, spec["symbol"])

    def key(t: o.Tick):
        q = lambda v: None if v is None else o.q8(v)  # noqa: E731
        return (t.ts, q(t.price), q(t.volume) if spec.get("volume", True) else None, q(t.bid), q(t.ask))

    if len(got) != len(want):
        raise CheckFailure(f"{spec['symbol']}: {len(got)} ticks stored, the file holds {len(want)}")
    for i, (g, w) in enumerate(zip(got, want)):
        if key(g) != key(w):
            raise CheckFailure(f"{spec['symbol']}: tick {i} stored {key(g)}, the file says {key(w)}")
    return f"{len(got)} ticks exact"


def check_bars_equal(spec: dict, case) -> str:
    """Every stored bar (bars/<SOURCE>/<SYMBOL>/<tf>/) equals the independent read."""
    source, sym = spec["symbol"].split(":")
    folder = case.root / "bars" / source / sym / spec.get("tf", "1m")
    got = [b for d in sorted(folder.glob("*.csv")) for b in read_bars_csv(d)]
    want = _read(spec, case)
    problems = o.diff_bars(spec["symbol"], got, want, fields=tuple(spec.get("fields", ("open", "high", "low", "close", "volume"))))
    if problems:
        raise CheckFailure(_first(problems))
    return f"{len(got)} bars exact"


def qkt_bars(case, symbol: str, tf: str, day_from: str, day_to: str, built: bool) -> list[o.Bar]:
    args = ["data", "bars", symbol, "--tf", tf, "--from", day_from, "--to", day_to, "--data-root", "{root}"]
    if built:
        bare = symbol.split(":")[-1]
        build = case.qkt(["data", "build-bars", bare, "--tf", tf, "--data-root", "{root}"])
        if build.returncode != 0:
            raise CheckFailure(f"build-bars {bare} {tf} exited {build.returncode}")
        args.append("--bars")
    proc = case.qkt(args)
    if proc.returncode != 0:
        raise CheckFailure(f"qkt data bars {symbol} {tf} exited {proc.returncode}: {proc.stderr.strip()[-200:]}")
    return read_bars_csv_text(proc.stdout)


def read_bars_csv_text(text: str) -> list[o.Bar]:
    return [
        o.Bar(int(r["timestamp"]), *(Decimal(r[k]) for k in ("open", "high", "low", "close", "volume")))
        for r in csv.DictReader(io.StringIO(text))
    ]


def check_vendor_bars(spec: dict, case) -> str:
    """`qkt data bars` at each timeframe equals the vendor's own bars at that timeframe, read independently.

    `paths`: `read` (stored bars, roll-ups, or bars aggregated from ticks) and/or `built` (the `--bars` .bin tier).
    `convention = "bybit"` restates qkt's bars the way Bybit's kline API draws them before comparing.
    `ambiguous_ms` allows up to that many fields to differ only where several trades share the bar's first or
    last millisecond at different prices, one of them the vendor's (the archive's order within a millisecond
    is not the vendor's matching order).
    """
    fields = tuple(spec.get("fields", ("open", "high", "low", "close", "volume")))
    total = 0
    ambiguous = 0
    for tf in spec["tfs"]:
        vendor_spec = dict(spec, file=[f.replace("{tf}", tf) for f in (spec["vendor"] if isinstance(spec["vendor"], list) else [spec["vendor"]])])
        want = _read(vendor_spec, case)
        for path in spec.get("paths", ["read"]):
            got = qkt_bars(case, spec["symbol"], tf, spec["from"], spec["to"], built=(path == "built"))
            if spec.get("convention") == "binance":
                got = o.binance_convention(got, want)
                want_cmp = want
            elif spec.get("convention") == "bybit":
                got = o.bybit_convention(got, want[0].start, len(want), o.tf_ms(tf))[1:]
                want_cmp = want[1:]
            else:
                want_cmp = want
            problems = o.diff_bars(f"{spec['symbol']} {tf} {path}", got, want_cmp, fields)
            if problems and spec.get("agg_trades_file"):
                touched = o.agg_crossing_minutes(_files(spec, case, "file_agg")[0], _files(spec, case, "agg_trades_file")[0], o.tf_ms(tf))
                outside = [p for p in problems if int(__import__("re").search(r" at (\d+)", p).group(1)) not in touched]
                if outside:
                    raise CheckFailure("differences outside the minutes a boundary-crossing aggTrade touches: " + _first(outside))
                ambiguous += len({int(__import__("re").search(r" at (\d+)", p).group(1)) for p in problems})
                problems = []
            if problems and spec.get("ambiguous_ms"):
                trades = _read(dict(spec, file=spec["trades"], reader=spec["trades_reader"]), case)
                problems, n = o.explain_same_ms(problems, got, want_cmp, trades, o.tf_ms(tf))
                ambiguous += n
                if ambiguous > spec["ambiguous_ms"]:
                    raise CheckFailure(f"{ambiguous} same-millisecond differences, more than the {spec['ambiguous_ms']} allowed")
            if problems:
                raise CheckFailure(_first(problems))
            total += len(got)
    why = "boundary-crossing aggTrade bars" if spec.get("agg_trades_file") else "same-ms ties"
    return f"{total} bars equal the vendor's ({'/'.join(spec['tfs'])})" + (f", {ambiguous} {why}" if ambiguous else "")


def check_same_store(spec: dict, case) -> str:
    """Two stored series are byte-identical, file by file (manifests and provenance aside)."""
    a = case.root / spec["a"]
    b = case.root / spec["b"]
    fa = {p.relative_to(a).as_posix(): p.read_bytes() for p in a.rglob("*.csv*")}
    fb = {p.relative_to(b).as_posix(): p.read_bytes() for p in b.rglob("*.csv*")}
    if fa.keys() != fb.keys():
        raise CheckFailure(f"{spec['a']} and {spec['b']} hold different days: {sorted(set(fa) ^ set(fb))[:5]}")
    diff = [k for k in fa if fa[k] != fb[k]]
    if diff:
        raise CheckFailure(f"{len(diff)} day(s) differ: {diff[:5]}")
    return f"{len(fa)} day files identical"


def check_exists(spec: dict, case) -> str:
    """Paths that must, or must not, exist after the steps."""
    for p in spec.get("paths", []):
        if not Path(case.sub(p)).exists():
            raise CheckFailure(f"{p} does not exist")
    for p in spec.get("absent", []):
        if Path(case.sub(p)).exists():
            raise CheckFailure(f"{p} exists")
    return ""


def check_files_equal(spec: dict, case) -> str:
    """Files produced by two runs are byte-identical (determinism of backtests, conversions)."""
    pairs = spec["pairs"]
    for a, b in pairs:
        pa, pb = Path(case.sub(a)), Path(case.sub(b))
        if pa.read_bytes() != pb.read_bytes():
            raise CheckFailure(f"{a} and {b} differ")
    return f"{len(pairs)} file pair(s) identical"


def check_built_bars(spec: dict, case) -> str:
    """`qkt data bars` at each timeframe equals bars the oracle builds from the raw file's ticks (`use`: price,
    mid or bid), through the plain read and the `--bars` tier. With `vendor`, the oracle's bars on the vendor's
    side (`vendor_use`, e.g. bid) must also equal the vendor's own bars, which proves the reader itself."""
    ticks = _read(spec, case)
    total = 0
    vendors = spec.get("vendors") or ([{"vendor": spec["vendor"], "use": spec.get("vendor_use", "bid")}] if "vendor" in spec else [])
    for v in vendors:
        want = _read(dict(spec, reader=spec["vendor_reader"], vendor=v["vendor"]), case, key="vendor")
        mine = o.bars_from_ticks(ticks, o.MINUTE, use=v["use"])
        extra_ok = spec.get("vendor_missing_ok", 0)
        problems = o.diff_bars("oracle vs vendor 1m", mine, want, fields=("open", "high", "low", "close"))
        if spec.get("vendor_flat_ok"):
            # The vendor draws a flat, zero-volume bar for a minute without ticks; anything else it has must match.
            by = {b.start: b for b in want}

            def flat(p: str) -> bool:
                if "missing bar" not in p:
                    return False
                b = by[int(p.rsplit(" ", 1)[1])]
                return b.open == b.high == b.low == b.close and b.volume == 0

            problems = [p for p in problems if not flat(p)]
        extras = [p for p in problems if "extra bar" in p]
        rest = [p for p in problems if "extra bar" not in p]
        if rest or len(extras) > extra_ok:
            raise CheckFailure("the oracle's own bars differ from the vendor's: " + _first(rest or extras))
    for tf in spec["tfs"]:
        want = o.bars_from_ticks(ticks, o.tf_ms(tf), use=spec.get("use", "mid"))
        for path in spec.get("paths", ["read", "built"]):
            got = qkt_bars(case, spec["symbol"], tf, spec["from"], spec["to"], built=(path == "built"))
            problems = o.diff_bars(f"{spec['symbol']} {tf} {path}", got, want, tuple(spec.get("fields", ("open", "high", "low", "close", "volume"))))
            if problems:
                raise CheckFailure(_first(problems))
            total += len(got)
    return f"{total} bars equal bars built from the raw ticks ({'/'.join(spec['tfs'])})"

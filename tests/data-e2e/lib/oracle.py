"""Independent readers for vendor files and for qkt's store, written apart from qkt's own code.

The data suite never trusts qkt to grade itself: every check compares what qkt stored with what this
module reads straight from the vendor's file (or the vendor's own coarser bars). Prices are Decimal
throughout; nothing here goes through a float.
"""

import csv
import gzip
import io
import lzma
import struct
import zipfile
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

SCALE = Decimal("1e-8")  # qkt stores prices and volumes as integers of 1e-8 (Money.SCALE)
MAGIC = b"QKB1"
MINUTE = 60_000


@dataclass(frozen=True)
class Bar:
    start: int  # epoch ms, UTC
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    volume: Decimal


@dataclass(frozen=True)
class Tick:
    ts: int  # epoch ms, UTC
    price: Decimal | None
    volume: Decimal | None
    bid: Decimal | None
    ask: Decimal | None


def _dec(s: str) -> Decimal | None:
    s = s.strip()
    return Decimal(s) if s else None


def q8(v: Decimal) -> Decimal:
    """A value as qkt stores it: 8 decimal places, half-even."""
    return v.quantize(SCALE, rounding="ROUND_HALF_EVEN")


# --- qkt's store ----------------------------------------------------------------------------------


def read_qkt_bin(path: Path) -> tuple[str, int, list[Bar]]:
    """A `bars/BACKTEST/<sym>/<tf>/<day>.bin` file: (symbol, timeframe ms, bars)."""
    b = Path(path).read_bytes()
    if b[:4] != MAGIC:
        raise ValueError(f"{path}: not a qkt bar file (magic {b[:4]!r})")
    off = 4
    version, scale, tf_ms = struct.unpack_from("<iiq", b, off)
    off += 16
    (n_sym,) = struct.unpack_from("<i", b, off)
    off += 4
    symbol = b[off : off + n_sym].decode()
    off += n_sym
    (n,) = struct.unpack_from("<i", b, off)
    off += 4
    cols = []
    for _ in range(6):
        cols.append(struct.unpack_from(f"<{n}q", b, off))
        off += 8 * n
    if off != len(b):
        raise ValueError(f"{path}: {len(b) - off} trailing bytes")
    unit = Decimal(1).scaleb(-scale)
    bars = [
        Bar(cols[0][i], *(Decimal(cols[c][i]) * unit for c in range(1, 6)))
        for i in range(n)
    ]
    return symbol, tf_ms, bars


def read_qkt_ticks(path: Path) -> list[Tick]:
    """A stored tick day `symbols/<SYM>/<day>.csv.gz`."""
    with gzip.open(path, "rt", newline="") as f:
        rows = csv.DictReader(f)
        return [
            Tick(int(r["timestamp"]), _dec(r["price"]), _dec(r["volume"]), _dec(r["bid"]), _dec(r["ask"]))
            for r in rows
        ]


def read_qkt_csv_bars(path: Path) -> list[Bar]:
    """An imported bar day `bars/<SOURCE>/<SYM>/<tf>/<day>.csv`."""
    with open(path, newline="") as f:
        return [
            Bar(int(r["timestamp"]), *(Decimal(r[k]) for k in ("open", "high", "low", "close", "volume")))
            for r in csv.DictReader(f)
        ]


# --- vendor files ---------------------------------------------------------------------------------


def _zip_text(path: Path) -> str:
    with zipfile.ZipFile(path) as z:
        (name,) = [n for n in z.namelist() if n.endswith(".csv")]
        return z.read(name).decode()


def _epoch_ms(raw: str) -> int:
    """Binance stamps ms before 2025 and µs on spot since; a 16-digit stamp is µs."""
    v = int(raw)
    return v // 1000 if len(raw) >= 16 else v


def binance_klines(path: Path) -> list[Bar]:
    out = []
    for row in csv.reader(io.StringIO(_zip_text(path))):
        if not row or not row[0].isdigit():
            continue  # header row on futures files
        out.append(Bar(_epoch_ms(row[0]), *(Decimal(row[i]) for i in range(1, 6))))
    return out


def binance_trades(path: Path, agg: bool) -> list[Tick]:
    """trades (id, price, qty, quote_qty, time, ...) or aggTrades (id, price, qty, first, last, time, ...)."""
    time_col = 5 if agg else 4
    out = []
    for row in csv.reader(io.StringIO(_zip_text(path))):
        if not row or not row[0].isdigit():
            continue  # header row on futures files
        out.append(Tick(_epoch_ms(row[time_col]), Decimal(row[1]), Decimal(row[2]), None, None))
    return out


def dukascopy_bi5_hour(path: Path, hour_start_ms: int, divisor: int) -> list[Tick]:
    """A datafeed `<HH>h_ticks.bi5`: LZMA of 20-byte big-endian records (ms in hour, ask, bid, ask vol, bid vol)."""
    raw = Path(path).read_bytes()
    if not raw:
        return []
    data = lzma.decompress(raw)
    out = []
    for i in range(0, len(data), 20):
        ms, ask, bid, av, bv = struct.unpack(">iiiff", data[i : i + 20])
        vol = Decimal(repr(av)) + Decimal(repr(bv))
        out.append(
            Tick(hour_start_ms + ms, None, vol, Decimal(bid) / divisor, Decimal(ask) / divisor)
        )
    return out


def dukascopy_bi5_candles(path: Path, day_start_ms: int, divisor: int) -> list[Bar]:
    """A datafeed `BID_candles_min_1.bi5`: 24-byte records (seconds in day, open, close, low, high, volume)."""
    data = lzma.decompress(Path(path).read_bytes())
    out = []
    for i in range(0, len(data), 24):
        sec, o, c, lo, hi, vol = struct.unpack(">iiiiif", data[i : i + 24])
        out.append(
            Bar(
                day_start_ms + sec * 1000,
                Decimal(o) / divisor,
                Decimal(hi) / divisor,
                Decimal(lo) / divisor,
                Decimal(c) / divisor,
                Decimal(repr(vol)),
            )
        )
    return out


# --- building bars --------------------------------------------------------------------------------


def bars_from_ticks(ticks: list[Tick], tf_ms: int, use: str = "price") -> list[Bar]:
    """OHLCV per UTC bucket of `tf_ms`, from trade prices (`price`) or bid/ask mids (`mid`) or bids (`bid`)."""
    buckets: dict[int, list[Decimal | Decimal]] = {}
    order: list[int] = []
    for t in ticks:
        if use == "price":
            p = t.price
        elif use == "bid":
            p = t.bid
        elif use == "ask":
            p = t.ask
        else:
            p = (t.bid + t.ask) / 2
        if p is None:
            continue
        k = t.ts - t.ts % tf_ms
        v = t.volume or Decimal(0)
        b = buckets.get(k)
        if b is None:
            buckets[k] = [p, p, p, p, v]
            order.append(k)
        else:
            b[1] = max(b[1], p)
            b[2] = min(b[2], p)
            b[3] = p
            b[4] += v
    return [Bar(k, *buckets[k]) for k in sorted(order)]


def roll_up(bars: list[Bar], tf_ms: int) -> list[Bar]:
    """Coarser bars from finer ones on the UTC grid (empty buckets produce no bar)."""
    out: dict[int, list[Decimal]] = {}
    for b in sorted(bars, key=lambda x: x.start):
        k = b.start - b.start % tf_ms
        cur = out.get(k)
        if cur is None:
            out[k] = [b.open, b.high, b.low, b.close, b.volume]
        else:
            cur[1] = max(cur[1], b.high)
            cur[2] = min(cur[2], b.low)
            cur[3] = b.close
            cur[4] += b.volume
    return [Bar(k, *out[k]) for k in sorted(out)]


def diff_bars(name: str, got: list[Bar], want: list[Bar], fields=("open", "high", "low", "close", "volume")) -> list[str]:
    """Every difference between two bar series, as readable lines (empty when identical)."""
    g = {b.start: b for b in got}
    w = {b.start: b for b in want}
    problems = []
    for k in sorted(set(w) - set(g)):
        problems.append(f"{name}: missing bar at {k}")
    for k in sorted(set(g) - set(w)):
        problems.append(f"{name}: extra bar at {k}")
    for k in sorted(set(g) & set(w)):
        for f in fields:
            a, b = getattr(g[k], f), getattr(w[k], f)
            if q8(a) != q8(b):
                problems.append(f"{name}: {f} at {k} is {a}, vendor {b}")
    return problems


def bybit_kline_json(paths: list[Path]) -> list[Bar]:
    """Bybit v5 /market/kline answers: list rows [start ms, open, high, low, close, volume, turnover], newest first."""
    import json

    out = {}
    for p in paths:
        for r in json.loads(Path(p).read_text())["result"]["list"]:
            out[int(r[0])] = Bar(int(r[0]), *(Decimal(r[i]) for i in range(1, 6)))
    return [out[k] for k in sorted(out)]


def bybit_trades(path: Path) -> list[Tick]:
    """public.bybit.com trading (epoch s with a fraction, size, price) or spot (id, epoch ms, price, volume) archives."""
    with gzip.open(path, "rt", newline="") as f:
        rows = list(csv.DictReader(f))
    out = []
    for r in rows:
        if "trdMatchID" in r:
            # Seconds with up to 4 decimals: exact Decimal, then floored to the millisecond as qkt stores it.
            ms = int((Decimal(r["timestamp"]) * 1000).to_integral_value(rounding="ROUND_FLOOR"))
            out.append(Tick(ms, Decimal(r["price"]), Decimal(r["size"]), None, None))
        else:
            out.append(Tick(int(r["timestamp"]), Decimal(r["price"]), Decimal(r["volume"]), None, None))
    out.sort(key=lambda t: t.ts)  # older Bybit days are newest-first
    return out


def bybit_convention(bars: list[Bar], start: int, count: int, tf_ms: int = MINUTE) -> list[Bar]:
    """Trade bars restated the way Bybit's kline API draws them: every bar opens at the previous close (its high
    and low widened to include it) and a minute without trades is a flat bar at the previous close."""
    by = {b.start: b for b in bars}
    out, prev = [], None
    for i in range(count):
        k = start + i * tf_ms
        b = by.get(k)
        if prev is None:
            if b is None:
                continue
            out.append(b)
        elif b is None:
            out.append(Bar(k, prev, prev, prev, prev, Decimal(0)))
        else:
            out.append(Bar(k, prev, max(prev, b.high), min(prev, b.low), b.close, b.volume))
        prev = out[-1].close
    return out


def _histdata_utc(naive):
    """HistData's clock, measured 2026-10-20 against Dukascopy UTC data (2016-2026): New York wall time until its
    feed changed at the weekly open of 2018-12-16 17:00, London wall time minus five hours since."""
    import datetime as dt
    from zoneinfo import ZoneInfo

    if naive < dt.datetime(2018, 12, 16, 17, 0):
        return naive.replace(tzinfo=ZoneInfo("America/New_York"))
    return (naive + dt.timedelta(hours=5)).replace(tzinfo=ZoneInfo("Europe/London"))


def histdata_ticks(path: Path) -> list[Tick]:
    """HistData ASCII ticks `yyyyMMdd HHmmssfff,bid,ask,0` on HistData's clock (see `_histdata_utc`)."""
    import datetime as dt

    out = []
    for row in csv.reader(io.StringIO(_zip_text(path))):
        stamp = _histdata_utc(dt.datetime.strptime(row[0][:15], "%Y%m%d %H%M%S"))
        ms = int(stamp.timestamp()) * 1000 + int(row[0][15:18])
        out.append(Tick(ms, None, None, Decimal(row[1]), Decimal(row[2])))
    return out


def histdata_m1(path: Path) -> list[Bar]:
    """HistData ASCII M1 bars `yyyyMMdd HHmmss;open;high;low;close;0` (bid) on HistData's clock."""
    import datetime as dt

    out = []
    for row in csv.reader(io.StringIO(_zip_text(path)), delimiter=";"):
        stamp = _histdata_utc(dt.datetime.strptime(row[0], "%Y%m%d %H%M%S"))
        out.append(Bar(int(stamp.timestamp()) * 1000, *(Decimal(row[i]) for i in range(1, 6))))
    return out


def tf_ms(tf: str) -> int:
    n, unit = int(tf[:-1]), tf[-1]
    return n * {"m": 60_000, "h": 3_600_000, "d": 86_400_000}[unit]


def okx_trades(path: Path) -> list[Tick]:
    """OKX trade history `instrument_name,trade_id,side,price,size,created_time[,source]` (epoch ms)."""
    rows = csv.DictReader(io.StringIO(_zip_text(path)))
    return [Tick(int(r["created_time"]), Decimal(r["price"]), Decimal(r["size"]), None, None) for r in rows]


def dukascopy_bi5_day(day_dir: Path, divisor: int) -> list[Tick]:
    """A day of datafeed hour files `<day_dir>/<HH>h_ticks.bi5`; the folder path ends in YYYY/MM(from 00)/DD."""
    import datetime as dt

    day_dir = Path(day_dir)
    y, m, d = int(day_dir.parent.parent.name), int(day_dir.parent.name) + 1, int(day_dir.name)
    start = int(dt.datetime(y, m, d, tzinfo=dt.timezone.utc).timestamp()) * 1000
    out = []
    for h in range(24):
        f = day_dir / f"{h:02d}h_ticks.bi5"
        raw = f.read_bytes()
        if not raw:
            continue
        data = lzma.decompress(raw)
        for i in range(0, len(data), 20):
            ms, ask, bid, av, bv = struct.unpack(">iiiff", data[i : i + 20])
            # Volume is the exact sum of the two float32 sizes, as qkt's own Dukascopy fetch stores it.
            out.append(Tick(start + h * 3_600_000 + ms, None, Decimal(av) + Decimal(bv), Decimal(bid) / divisor, Decimal(ask) / divisor))
    return out


def dukascopy_candles(path: Path, divisor: int) -> list[Bar]:
    """`<day>/BID_candles_min_1.bi5` (or ASK): 24-byte records (second of day, open, close, low, high, volume)."""
    import datetime as dt

    path = Path(path)
    day_dir = path.parent
    y, m, d = int(day_dir.parent.parent.name), int(day_dir.parent.name) + 1, int(day_dir.name)
    start = int(dt.datetime(y, m, d, tzinfo=dt.timezone.utc).timestamp()) * 1000
    data = lzma.decompress(path.read_bytes())
    out = []
    for i in range(0, len(data), 24):
        sec, op, cl, lo, hi, vol = struct.unpack(">iiiiif", data[i : i + 24])
        out.append(Bar(start + sec * 1000, *(Decimal(v) / divisor for v in (op, hi, lo, cl)), Decimal(vol)))
    return out


def _float32(x: float) -> float:
    return struct.unpack("<f", struct.pack("<f", x))[0]


def float32_text(text: str) -> Decimal:
    """The decimal a float32-sourced cell stands for: when the nearest float32, written at the text's own
    decimals, gives the text back, the shortest decimal naming that float32; otherwise the text itself."""
    value = Decimal(text)
    single = _float32(float(value))
    places = max(-value.as_tuple().exponent, 0)
    if Decimal(single).quantize(Decimal(1).scaleb(-places), rounding="ROUND_HALF_EVEN") != value:
        return value
    for digits in range(1, 10):
        s = f"{single:.{digits}g}"
        if _float32(float(s)) == single:
            return Decimal(s)
    return value


def yfinance_bars(path: Path, ticker: str | None = None) -> list[Bar]:
    """yfinance CSVs as `DataFrame.to_csv` writes them: flat (Date/Datetime + columns) or three-line headers
    (Price/Ticker/Date, one column per ticker). Dates are UTC days; datetimes carry their offset; a
    `Ticker.history()` daily date at the exchange's midnight is that session's date."""
    import datetime as dt

    rows = list(csv.reader(io.StringIO(Path(path).read_text())))
    header = rows[0]
    if header[0] == "Price":
        tickers = rows[1]
        body = rows[3:]
        col = {name: i for i, name in enumerate(header) if i and (ticker is None or tickers[i] == ticker)}
    else:
        body = rows[1:]
        col = {name: i for i, name in enumerate(header)}
    out = []
    for r in body:
        t = r[0]
        if len(t) == 10:
            stamp = dt.datetime.fromisoformat(t).replace(tzinfo=dt.timezone.utc)
        elif t.endswith(("00:00:00-04:00", "00:00:00-05:00")) and header[0] == "Date":
            stamp = dt.datetime.fromisoformat(t[:10]).replace(tzinfo=dt.timezone.utc)
        else:
            stamp = dt.datetime.fromisoformat(t)
        o_, h, l_, c = (float32_text(r[col[k]]) for k in ("Open", "High", "Low", "Close"))
        out.append(Bar(int(stamp.timestamp() * 1000), o_, h, l_, c, Decimal(r[col["Volume"]])))
    return out


def explain_same_ms(problems: list[str], got: list[Bar], want: list[Bar], trades: list[Tick], tf: int) -> tuple[list[str], int]:
    """Splits differences into those a same-millisecond trade order explains and the rest.

    A bar's difference is explained when its closing millisecond (or the previous bar's, whose close the
    vendor's open repeats) holds trades at several prices, one of them the vendor's value: the archive does
    not say which of them came last."""
    import re

    by_bar: dict[int, list[Tick]] = {}
    for t in trades:
        by_bar.setdefault(t.ts - t.ts % tf, []).append(t)
    want_by = {b.start: b for b in want}

    def closing_prices(k: int) -> set:
        ts = by_bar.get(k, [])
        if not ts:
            return set()
        last = max(t.ts for t in ts)
        return {t.price for t in ts if t.ts == last}

    rest, explained = [], set()
    for p in problems:
        m = re.search(r" at (\d+) is", p)
        if not m:
            rest.append(p)
            continue
        k = int(m.group(1))
        w = want_by.get(k)
        # The open repeats the last close before it, through any minutes without trades.
        back = k - tf
        while back >= k - 1440 * tf and back not in by_bar:
            back -= tf
        candidates = closing_prices(k) | closing_prices(back)
        vendor_values = {w.open, w.high, w.low, w.close} if w else set()
        if len(candidates) > 1 and candidates & vendor_values:
            explained.add(k)
        else:
            rest.append(p)
    return rest, len(explained)


def ccxt_ohlcv(path: Path) -> list[Bar]:
    """A ccxt `fetch_ohlcv` dump `[[ms, open, high, low, close, volume], ...]` (numbers as JSON floats)."""
    import json

    rows = json.loads(Path(path).read_text(), parse_float=Decimal)
    return [Bar(int(r[0]), *(Decimal(v) for v in r[1:6])) for r in rows]


def alphavantage_bars(path: Path, zone: str | None) -> list[Bar]:
    """Alpha Vantage CSV, newest first: daily dates are session days, intraday times US/Eastern wall clock."""
    import datetime as dt
    from zoneinfo import ZoneInfo

    out = []
    for r in csv.DictReader(io.StringIO(Path(path).read_text())):
        t = r["timestamp"]
        if len(t) == 10:
            stamp = dt.datetime.fromisoformat(t).replace(tzinfo=dt.timezone.utc)
        else:
            stamp = dt.datetime.fromisoformat(t).replace(tzinfo=ZoneInfo(zone or "America/New_York"))
        out.append(Bar(int(stamp.timestamp() * 1000), *(Decimal(r[k]) for k in ("open", "high", "low", "close", "volume"))))
    return sorted(out, key=lambda b: b.start)


def ny_close_utc_ms(wall) -> int:
    """A broker `ny-close` wall time (New York + 7 h, so 00:00 is the 17:00 New York rollover) in UTC ms."""
    import datetime as dt
    from zoneinfo import ZoneInfo

    ny = (wall - dt.timedelta(hours=7)).replace(tzinfo=ZoneInfo("America/New_York"))
    return int(ny.timestamp() * 1000)


def mt5_bars_mid(path: Path, clock: str, point: str) -> list[Bar]:
    """MetaTrader 5 bar export (tab-separated, bid prices, spread in points) shifted to mid by half the spread."""
    import datetime as dt

    text = Path(path).read_bytes()
    text = text.decode("utf-16") if text[:2] in (b"\xff\xfe", b"\xfe\xff") else text.decode("utf-8-sig")
    rows = list(csv.reader(io.StringIO(text), delimiter="\t"))
    head = [h.strip() for h in rows[0]]
    i = {h: n for n, h in enumerate(head)}
    out = []
    for r in rows[1:]:
        if not r or not r[0].strip():
            continue
        wall = dt.datetime.strptime(r[i["<DATE>"]] + " " + r[i["<TIME>"]], "%Y.%m.%d %H:%M:%S")
        if clock == "ny-close":
            ms = ny_close_utc_ms(wall)
        elif clock == "UTC":
            ms = int(wall.replace(tzinfo=dt.timezone.utc).timestamp() * 1000)
        else:
            raise ValueError(clock)
        half = Decimal(r[i["<SPREAD>"]]) * Decimal(point) / 2 if "<SPREAD>" in i else Decimal(0)
        o_, h, l_, c = (Decimal(r[i[k]]) + half for k in ("<OPEN>", "<HIGH>", "<LOW>", "<CLOSE>"))
        out.append(Bar(ms, o_, h, l_, c, Decimal(r[i["<TICKVOL>"]])))
    return out


def agg_crossing_minutes(agg_path: Path, trades_path: Path, tf: int) -> set[int]:
    """Bar starts touched by an aggTrade whose fills span a bar boundary. Binance stamps an aggTrade with its
    first fill's time, so its whole size lands in the first bar while the venue's klines split it."""
    def rows(p):
        return [r for r in csv.reader(io.StringIO(_zip_text(p))) if r and r[0].isdigit()]

    fill_time = {int(r[0]): _epoch_ms(r[4]) for r in rows(trades_path)}
    touched = set()
    for r in rows(agg_path):
        times = [fill_time[i] for i in range(int(r[3]), int(r[4]) + 1) if i in fill_time]
        if times and times[0] // tf != times[-1] // tf:
            touched.update(t - t % tf for t in times)
    return touched


def binance_convention(got: list[Bar], want: list[Bar]) -> list[Bar]:
    """qkt's bars restated the way Binance writes klines: a minute without trades is a bar of its own, flat at the
    previous close with zero volume. Only minutes the vendor has are added; every other bar is qkt's own."""
    g = {b.start: b for b in got}
    out, prev = [], None
    for w in sorted(want, key=lambda b: b.start):
        b = g.get(w.start)
        if b is None and prev is not None:
            b = Bar(w.start, prev, prev, prev, prev, Decimal(0))
        if b is not None:
            out.append(b)
            prev = b.close
    return out + [b for b in got if b.start not in {w.start for w in want}]

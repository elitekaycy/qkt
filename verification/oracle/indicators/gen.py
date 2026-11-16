"""Independent oracle for qkt's DSL indicators and numeric functions.

    python3 -I verification/oracle/indicators/gen.py                 # rewrite fixtures/ from series/
    python3 -I verification/oracle/indicators/gen.py --refresh-series --store ~/.qkt/data

Writes one JSON fixture per (indicator, arguments, series) and one per numeric function under `fixtures/`.
`IndicatorVerificationTest` replays every fixture through `IndicatorRegistry.create` / `FuncRegistry.invoke`
and compares bar by bar at the tolerance each fixture states. Nothing here imports or reads qkt code: TA-Lib
supplies the reference where it has the indicator; everything else is written below from the published
definition (and, for qkt's own constructs, from the prose in docs/reference/dsl/indicators.md). Where qkt's
documented convention differs from TA-Lib, the fixture encodes qkt's convention and says so in `note`.

Series under `series/` are checked in so fixtures regenerate without the private data store; `--refresh-series`
rebuilds them from the local qkt bar store (QKB1 day files) and records the source and range in each file.
"""

from __future__ import annotations

import argparse
import json
import math
import struct
import sys
import warnings
from dataclasses import dataclass
from decimal import ROUND_FLOOR, ROUND_HALF_EVEN, Decimal, localcontext
from pathlib import Path

import numpy as np
import pandas as pd
import scipy.stats
import talib

HERE = Path(__file__).resolve().parent
SERIES_DIR = HERE / "series"
FIXTURE_DIR = HERE / "fixtures"
ORACLE = f"TA-Lib {talib.__ta_version__.decode().split()[0]} (ta-lib {talib.__version__}), numpy {np.__version__}, pandas {pd.__version__}, scipy {scipy.__version__}"

MS_MIN = 60_000
MS_HOUR = 3_600_000
MS_DAY = 86_400_000
MONDAY_2025_01_06 = 1_736_121_600_000

# Tolerance groups (design §2 / audit §8.2). `exact` is half a unit at qkt's 8 decimals plus a 1e-11 relative
# term: qkt computes in 16 significant decimal digits and the oracle in IEEE double, and that last-digit
# disagreement both exceeds 5e-9 at a magnitude of 1e9 (synth_huge) and can tip an 8-decimal rounding.
EXACT = {"abs": 5e-9, "rel": 1e-11}
RECURSIVE = {"abs": 5e-9, "rel": 1e-9}
SQRT = {"abs": 1e-7, "rel": 1e-9}
DOUBLE = {"abs": 1e-300, "rel": 1e-12}
DECIMAL_EXACT = {"abs": 0.0, "rel": 0.0}


# --------------------------------------------------------------------------------------------------------------
# Series: real bars from the qkt store, synthetic edge series
# --------------------------------------------------------------------------------------------------------------


@dataclass
class Series:
    id: str
    source: str
    timeframe_ms: int
    bars: list  # [startMs, open, high, low, close, volume] as strings

    def frame(self) -> pd.DataFrame:
        f = pd.DataFrame(self.bars, columns=["start", "open", "high", "low", "close", "volume"])
        f["start"] = f["start"].astype("int64")
        for c in ("open", "high", "low", "close", "volume"):
            f[c] = f[c].astype(float)
        f["end"] = f["start"] + self.timeframe_ms
        return f


def read_qkb1(path: Path) -> list[list]:
    """Decode one qkt-bar-bin-v1 day file: little-endian header then six int64 columns scaled by 10^scale."""
    b = path.read_bytes()
    assert b[:4] == b"QKB1", path
    _version, scale = struct.unpack_from("<ii", b, 4)
    (timeframe_ms,) = struct.unpack_from("<q", b, 12)
    (n,) = struct.unpack_from("<i", b, 20)
    o = 24 + n
    (count,) = struct.unpack_from("<i", b, o)
    o += 4
    cols = [struct.unpack_from(f"<{count}q", b, o + i * 8 * count) for i in range(6)]
    q = Decimal(10) ** -scale
    rows = []
    for i in range(count):
        rows.append([str(cols[0][i])] + [str(Decimal(cols[c][i]) * q) for c in range(1, 6)])
    return rows, timeframe_ms


def store_days(store: Path, venue: str, symbol: str, tf: str, first_day: str, max_bars: int) -> tuple[list, int]:
    folder = store / "bars" / venue / symbol / tf
    rows, tf_ms = [], 0
    for p in sorted(folder.glob("*.bin")):
        if p.stem < first_day:
            continue
        day_rows, tf_ms = read_qkb1(p)
        rows.extend(day_rows)
        if len(rows) >= max_bars:
            break
    return rows[:max_bars], tf_ms


def aggregate_to_hours(rows: list) -> list:
    """Pairs of 30m bars -> 1h bars on the UTC hour grid (open=first, high=max, low=min, close=last, volume=sum)."""
    out = []
    for r in rows:
        start = int(r[0])
        hour = start - start % MS_HOUR
        if out and int(out[-1][0]) == hour:
            last = out[-1]
            last[2] = str(max(Decimal(last[2]), Decimal(r[2])))
            last[3] = str(min(Decimal(last[3]), Decimal(r[3])))
            last[4] = r[4]
            last[5] = str(Decimal(last[5]) + Decimal(r[5]))
        else:
            out.append([str(hour), r[1], r[2], r[3], r[4], r[5]])
    return out


def real_series(store: Path) -> list[Series]:
    xau30, _ = store_days(store, "BACKTEST", "XAUUSD", "30m", "2025-03-03", 1200)
    xau = aggregate_to_hours(xau30)[:600]
    btc1m, _ = store_days(store, "BINANCE_UM", "BTCUSDT", "1m", "2025-03-03", 1560)
    btc1h, _ = store_days(store, "BINANCE_UM", "BTCUSDT", "1h", "2025-03-03", 720)
    span = lambda rows: f"{pd.Timestamp(int(rows[0][0]), unit='ms')}..{pd.Timestamp(int(rows[-1][0]), unit='ms')} UTC"
    return [
        Series("xau_1h", f"BACKTEST:XAUUSD 30m QKB1 day files (~/.qkt/data/bars/BACKTEST/XAUUSD/30m) aggregated to 1h; {span(xau)}; {len(xau)} bars", MS_HOUR, xau),
        Series("btc_1m", f"BINANCE_UM:BTCUSDT 1m QKB1 day files (~/.qkt/data/bars/BINANCE_UM/BTCUSDT/1m) with traded volume; {span(btc1m)}; {len(btc1m)} bars", MS_MIN, btc1m),
        Series("btc_1h", f"BINANCE_UM:BTCUSDT 1h QKB1 day files (~/.qkt/data/bars/BINANCE_UM/BTCUSDT/1h) with traded volume; {span(btc1h)}; {len(btc1h)} bars", MS_HOUR, btc1h),
    ]


def d8(x: float) -> str:
    return str(Decimal(repr(x)).quantize(Decimal("0.00000001")))


def path_bars(prices: list[float], starts: list[int], vol=lambda i: 10 + i % 7, wick=1e-3) -> list:
    rows, prev = [], prices[0]
    for i, (p, s) in enumerate(zip(prices, starts)):
        o, c = prev, p
        rows.append([str(s), d8(o), d8(max(o, c) * (1 + wick)), d8(min(o, c) * (1 - wick)), d8(c), d8(vol(i))])
        prev = c
    return rows


def wave(i: int, base: float, amp: float) -> float:
    return base * (1 + amp * math.sin(i / 3.0) + amp * 0.2 * i / 10)


def synthetic_series() -> list[Series]:
    t0 = MONDAY_2025_01_06
    hourly = lambda n: [t0 + i * MS_HOUR for i in range(n)]
    flat = [[str(s), "100", "100", "100", "100", "10"] for s in hourly(60)]
    # Gap series: a x10 jump at bar 60, a 48h hole after bar 80 (weekend) and one missing hour at bar 100.
    prices = [wave(i, 100.0, 0.02) * (10 if i >= 60 else 1) for i in range(120)]
    starts, t = [], t0
    for i in range(120):
        starts.append(t)
        t += MS_HOUR
        if i == 80:
            t += 48 * MS_HOUR
        if i == 100:
            t += MS_HOUR
    gap = path_bars(prices, starts)
    zero_prices = [wave(i, 100.0, 0.02) for i in range(60)]
    zero = path_bars(zero_prices, hourly(60))
    zero[24] = [zero[24][0], "0", "0", "0", "0", zero[24][5]]
    zero[25][1] = "0"
    huge = path_bars([wave(i, 1e9, 0.01) for i in range(60)], hourly(60), vol=lambda i: 1e6 + i)
    # 3e-8 amplitude: visible at qkt's 8 decimals (a 1e-9 wave would quantise to a flat series).
    tiny = path_bars([1e-6 + 3e-8 * math.sin(i / 2.0) for i in range(60)], hourly(60), wick=1e-2)
    single = path_bars([wave(0, 100.0, 0.02)], hourly(1))
    short = path_bars([wave(i, 100.0, 0.02) for i in range(5)], hourly(5))
    return [
        Series("synth_flat", "constant 100 OHLC, volume 10, 60 hourly bars from 2025-01-06 00:00 UTC", MS_HOUR, flat),
        Series("synth_gap", "deterministic wave, x10 level jump at bar 60, 48h time gap after bar 80, one missing hour after bar 100", MS_HOUR, gap),
        Series("synth_zero", "deterministic wave with a zero-price bar (OHLC 0) at index 24 = 2025-01-07 00:00 UTC; bar 25 opens at 0", MS_HOUR, zero),
        Series("synth_huge", "deterministic wave around 1e9 with volume ~1e6", MS_HOUR, huge),
        Series("synth_tiny", "deterministic wave around 1e-6 with 3e-8 amplitude (three to seven units at 8 decimals)", MS_HOUR, tiny),
        Series("synth_single", "one bar", MS_HOUR, single),
        Series("synth_short", "five bars (period > data for every period-20 fixture)", MS_HOUR, short),
    ]


def write_series(series: list[Series]) -> None:
    SERIES_DIR.mkdir(parents=True, exist_ok=True)
    for s in series:
        (SERIES_DIR / f"{s.id}.json").write_text(
            json.dumps({"id": s.id, "source": s.source, "timeframeMs": s.timeframe_ms, "bars": s.bars}, separators=(",", ":"))
            + "\n"
        )


def load_series() -> dict[str, Series]:
    out = {}
    for p in sorted(SERIES_DIR.glob("*.json")):
        j = json.loads(p.read_text())
        out[j["id"]] = Series(j["id"], j["source"], j["timeframeMs"], j["bars"])
    return out


# --------------------------------------------------------------------------------------------------------------
# Reference formulas. Every function returns a float array aligned with the input bars, NaN before warmup.
# --------------------------------------------------------------------------------------------------------------


def nan(n: int) -> np.ndarray:
    return np.full(n, np.nan)


def rolling(x: np.ndarray, n: int, fn) -> np.ndarray:
    out = nan(len(x))
    for i in range(n - 1, len(x)):
        out[i] = fn(x[i - n + 1 : i + 1])
    return out


def ema(x: np.ndarray, n: int) -> np.ndarray:
    """EMA seeded with the SMA of the first n values (TA-Lib's default seeding); n = 1 is the identity."""
    return x.copy() if n == 1 else talib.EMA(x, n)


def wma(x: np.ndarray, n: int) -> np.ndarray:
    return x.copy() if n == 1 else talib.WMA(x, n)


def sma(x: np.ndarray, n: int) -> np.ndarray:
    return x.copy() if n == 1 else talib.SMA(x, n)


def hma(x: np.ndarray, n: int) -> np.ndarray:
    # Hull (2005): WMA(2*WMA(n/2) - WMA(n), sqrt(n)). qkt documents round(sqrt n) for the final length.
    half, root = max(n // 2, 1), max(round(math.sqrt(n)), 1)
    return wma(2 * wma(x, half) - wma(x, n), root)


def macd(x: np.ndarray, fast: int, slow: int, sig: int):
    # Appel's definition: EMA(fast) - EMA(slow), signal = EMA(sig) of that line, each EMA SMA-seeded on its own
    # first window. (talib.MACD seeds the fast EMA at the slow EMA's start instead, a non-standard variant.)
    line = ema(x, fast) - ema(x, slow)
    signal = ema(line, sig)
    line = line.copy()
    line[np.isnan(signal)] = np.nan  # qkt reports all three lines together, from the signal's first value
    return line, signal, line - signal


def rsi(x: np.ndarray, n: int) -> np.ndarray:
    return talib.RSI(x, n)


def wilder(x: np.ndarray, n: int) -> np.ndarray:
    """Wilder smoothing: mean of the first n values, then prev + (v - prev)/n."""
    out = nan(len(x))
    if len(x) < n:
        return out
    out[n - 1] = x[:n].mean()
    for i in range(n, len(x)):
        out[i] = out[i - 1] + (x[i] - out[i - 1]) / n
    return out


def true_range(f: pd.DataFrame) -> np.ndarray:
    h, l, c = f["high"].values, f["low"].values, f["close"].values
    tr = nan(len(f))
    tr[1:] = np.maximum.reduce([h[1:] - l[1:], np.abs(h[1:] - c[:-1]), np.abs(l[1:] - c[:-1])])
    return tr


def atr(f: pd.DataFrame, n: int) -> np.ndarray:
    tr = true_range(f)
    out = nan(len(f))
    out[1:] = wilder(tr[1:], n)
    return out


def adx_family(f: pd.DataFrame, n: int):
    """Wilder (1978): +DM/-DM, TR smoothed over n; DI = 100*DM/TR; DX = 100*|+DI - -DI|/(+DI + -DI); ADX = Wilder(DX, n).
    DI and ADX are undefined (null) while the smoothed true range is zero — qkt's documented rangeless-window rule."""
    h, l = f["high"].values, f["low"].values
    up, dn = h[1:] - h[:-1], l[:-1] - l[1:]
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    mdm = np.where((dn > up) & (dn > 0), dn, 0.0)
    tr = true_range(f)[1:]
    s_tr, s_p, s_m = wilder(tr, n), wilder(pdm, n), wilder(mdm, n)
    with np.errstate(invalid="ignore", divide="ignore"):
        pdi = np.where(s_tr != 0, 100 * s_p / s_tr, np.nan)
        mdi = np.where(s_tr != 0, 100 * s_m / s_tr, np.nan)
        dx = np.where(pdi + mdi != 0, 100 * np.abs(pdi - mdi) / (pdi + mdi), np.nan)
    valid = ~np.isnan(dx)
    adx_v = nan(len(dx))
    adx_v[valid] = wilder(dx[valid], n)
    pad = lambda a: np.concatenate([[np.nan], a])
    pdi, mdi, adx_v = pad(pdi), pad(mdi), pad(adx_v)
    # qkt exposes all three lines from one computation gated on the ADX warmup (documented 2N bars).
    gate = np.isnan(adx_v)
    pdi[gate], mdi[gate] = np.nan, np.nan
    return pdi, mdi, adx_v


def cci(f: pd.DataFrame, n: int) -> np.ndarray:
    tp = (f["high"].values + f["low"].values + f["close"].values) / 3

    def one(w):
        md = np.abs(w - w.mean()).mean()
        return 0.0 if md == 0 else (w[-1] - w.mean()) / (0.015 * md)

    return rolling(tp, n, one)


def stoch(f: pd.DataFrame, k: int, d: int, flat_value: float):
    """Fast %K = 100*(close - LL)/(HH - LL) over k bars (no slowing, i.e. TA-Lib STOCHF), %D = SMA(%K, d).
    A rangeless window (HH == LL) reads `flat_value`: qkt documents 50 where TA-Lib returns 0."""
    h, l, c = f["high"].values, f["low"].values, f["close"].values
    kk = nan(len(f))
    for i in range(k - 1, len(f)):
        hh, ll = h[i - k + 1 : i + 1].max(), l[i - k + 1 : i + 1].min()
        kk[i] = flat_value if hh == ll else 100 * (c[i] - ll) / (hh - ll)
    dd = nan(len(f))
    for i in range(k + d - 2, len(f)):
        dd[i] = kk[i - d + 1 : i + 1].mean()
    kk[np.isnan(dd)] = np.nan  # both lines share qkt's K + D - 1 warmup
    return kk, dd


def williams_r(f: pd.DataFrame, n: int) -> np.ndarray:
    h, l, c = f["high"].values, f["low"].values, f["close"].values
    out = nan(len(f))
    for i in range(n - 1, len(f)):
        hh, ll = h[i - n + 1 : i + 1].max(), l[i - n + 1 : i + 1].min()
        out[i] = -100.0 if hh == ll else -100 * (hh - c[i]) / (hh - ll)  # qkt: -100 on a rangeless window
    return out


def obv(f: pd.DataFrame) -> np.ndarray:
    # Granville: +volume on an up close, -volume on a down close. qkt starts at 0 (TA-Lib starts at volume[0]).
    c, v = f["close"].values, f["volume"].values
    out = np.zeros(len(f))
    for i in range(1, len(f)):
        out[i] = out[i - 1] + (v[i] if c[i] > c[i - 1] else -v[i] if c[i] < c[i - 1] else 0.0)
    return out


def stddev(x: np.ndarray, n: int, ddof: int) -> np.ndarray:
    return rolling(x, n, lambda w: w.std(ddof=ddof))


def zscore(x: np.ndarray, n: int) -> np.ndarray:
    def one(w):
        s = w.std(ddof=1)
        return np.nan if s == 0 else (w[-1] - w.mean()) / s

    return rolling(x, n, one)


def bollinger(x: np.ndarray, n: int, k: float):
    # Bollinger: SMA +- k * population standard deviation (TA-Lib BBANDS, matype SMA).
    if n == 1:
        return x.copy(), x.copy(), x.copy()
    return talib.BBANDS(x, n, k, k, 0)


def keltner(f: pd.DataFrame, n: int, k: float):
    mid, rng = ema(f["close"].values, n), atr(f, n)
    mid[np.isnan(rng)] = np.nan
    return mid + k * rng, mid, mid - k * rng


def regression_slope(x: np.ndarray, n: int) -> np.ndarray:
    return talib.LINEARREG_SLOPE(x, n)


def percentile_rank(x: np.ndarray, n: int) -> np.ndarray:
    return rolling(x, n, lambda w: (w[:-1] < w[-1]).sum() / n)


def simple_returns(x: np.ndarray) -> np.ndarray:
    with np.errstate(invalid="ignore", divide="ignore"):
        r = (x[1:] - x[:-1]) / x[:-1]
    r[x[:-1] == 0] = np.nan  # a zero previous price leaves the return undefined
    return r


def skew(x: np.ndarray, n: int) -> np.ndarray:
    """Population skewness g1 = m3 / m2^(3/2) of the last n simple returns; 0 on a dispersion-less window."""
    r = simple_returns(x)

    def one(w):
        if np.isnan(w).any():
            return np.nan
        d = w - w.mean()
        m2 = (d**2).mean()
        return 0.0 if m2 == 0 else (d**3).mean() / m2**1.5

    return np.concatenate([[np.nan], rolling(r, n, one)])


def efficiency_ratio(x: np.ndarray, n: int) -> np.ndarray:
    def one(w):
        path = np.abs(np.diff(w)).sum()
        return 0.0 if path == 0 else abs(w[-1] - w[0]) / path

    return rolling(x, n + 1, one)


def variance_ratio(x: np.ndarray, k: int, n: int) -> np.ndarray:
    """Lo-MacKinlay VR(k) = Var(overlapping k-period return sums) / (k Var(1-period returns)), population moments,
    over the last n simple returns (no finite-sample bias corrections). Undefined when the 1-period variance is 0."""
    r = simple_returns(x)

    def one(w):
        if np.isnan(w).any():
            return np.nan
        v1 = w.var()
        if v1 == 0:
            return np.nan
        sums = np.array([w[j : j + k].sum() for j in range(n - k + 1)])
        return sums.var() / (k * v1)

    return np.concatenate([[np.nan], rolling(r, n, one)])


def lag(x: np.ndarray, n: int) -> np.ndarray:
    out = nan(len(x))
    out[n:] = x[:-n]
    return out


def runlength(x: np.ndarray) -> np.ndarray:
    out, run = nan(len(x)), 0
    for i in range(1, len(x)):
        s = int(x[i] > x[i - 1]) - int(x[i] < x[i - 1])
        run = 0 if s == 0 else (run + s if run * s > 0 else s)
        out[i] = run
    return out


def runlength_where(cond: np.ndarray) -> np.ndarray:
    out, c = nan(len(cond)), 0
    for i, b in enumerate(cond):
        c = c + 1 if b else 0
        out[i] = c
    return out


def highest(x: np.ndarray, n: int) -> np.ndarray:
    # Donchian extreme of the n bars BEFORE the current one (qkt documents the one-bar exclusion).
    return lag(x.copy() if n == 1 else talib.MAX(x, n), 1)


def lowest(x: np.ndarray, n: int) -> np.ndarray:
    return lag(x.copy() if n == 1 else talib.MIN(x, n), 1)


def correlation(a: np.ndarray, b: np.ndarray, n: int) -> np.ndarray:
    def one(i):
        wa, wb = a[i - n + 1 : i + 1], b[i - n + 1 : i + 1]
        da, db = wa - wa.mean(), wb - wb.mean()
        den = math.sqrt((da * da).sum() * (db * db).sum())
        return np.nan if den == 0 else (da * db).sum() / den

    out = nan(len(a))
    for i in range(n - 1, len(a)):
        out[i] = one(i)
    return out


def beta(a: np.ndarray, b: np.ndarray, n: int) -> np.ndarray:
    """OLS slope of a on b over the window: Cov(a, b) / Var(b) (levels, not returns)."""
    out = nan(len(a))
    for i in range(n - 1, len(a)):
        wa, wb = a[i - n + 1 : i + 1], b[i - n + 1 : i + 1]
        vb = ((wb - wb.mean()) ** 2).sum()
        out[i] = np.nan if vb == 0 else ((wa - wa.mean()) * (wb - wb.mean())).sum() / vb
    return out


def vwap_ticks(f: pd.DataFrame, n: int) -> np.ndarray:
    pv, v = f["close"].values * f["volume"].values, f["volume"].values

    def one(i):
        sv = v[i - n + 1 : i + 1].sum()
        return np.nan if sv == 0 else pv[i - n + 1 : i + 1].sum() / sv

    out = nan(len(f))
    for i in range(n - 1, len(f)):
        out[i] = one(i)
    return out


# ---- session-anchored (written from docs/reference/dsl/indicators.md, "Session-anchored indicators") ----


def session_vwap(f: pd.DataFrame, anchor_hour: int):
    """Cumulative sum(tp*v)/sum(v) since the last anchorHour:00 UTC, with the volume-weighted population stdev
    of tp around it, computed in two passes over the session's bars (E[tp^2] - E[tp]^2 cancels at large prices
    and reads a spurious 1e-3 on a one-bar session). A session whose anchor candle was not observed stays null
    until the next anchor."""
    vwap, sd = nan(len(f)), nan(len(f))
    session, ok, tps, vols = None, False, [], []
    for i, (s, h, l, c, v) in enumerate(zip(f["start"], f["high"], f["low"], f["close"], f["volume"])):
        idx = (s - anchor_hour * MS_HOUR) // MS_DAY
        if idx != session:
            session, ok, tps, vols = idx, (s - anchor_hour * MS_HOUR) % MS_DAY == 0, [], []
        if ok and v > 0:
            tps.append((h + l + c) / 3)
            vols.append(v)
        if vols:
            sv = math.fsum(vols)
            mean = math.fsum(tp * w for tp, w in zip(tps, vols)) / sv
            vwap[i] = mean
            sd[i] = math.sqrt(math.fsum(w * (tp - mean) ** 2 for tp, w in zip(tps, vols)) / sv)
    return vwap, sd


def session_range(f: pd.DataFrame, sh: int, sm: int, eh: int, em: int):
    """High/low of the most recent completed instance of the daily UTC window [sh:sm, eh:em), latched when the
    first bar outside it (or of the next instance) arrives. An instance not observed from its first minute is
    discarded. The window wraps midnight when start > end."""
    start, end = sh * 60 + sm, eh * 60 + em
    hi, lo = nan(len(f)), nan(len(f))
    inst, complete, ch, cl, lh, ll = None, False, 0.0, 0.0, np.nan, np.nan
    for i, (s, h, l) in enumerate(zip(f["start"], f["high"], f["low"])):
        minute = s // MS_MIN
        tod = minute % 1440
        inside = (start <= tod < end) if start <= end else (tod >= start or tod < end)
        if inside:
            day0 = minute - tod
            inst_start = day0 + start if day0 + start <= minute else day0 + start - 1440
            if inst_start != inst:
                if inst is not None and complete:
                    lh, ll = ch, cl
                inst, complete, ch, cl = inst_start, minute == inst_start, h, l
            else:
                ch, cl = max(ch, h), min(cl, l)
        elif inst is not None:
            if complete:
                lh, ll = ch, cl
            inst = None
        hi[i], lo[i] = lh, ll
    return hi, lo


def pivots(f: pd.DataFrame):
    """Floor-trader pivots from the prior completed UTC day (observed from 00:00): P = (H+L+C)/3, R1 = 2P - L, S1 = 2P - H."""
    p, r1, s1 = nan(len(f)), nan(len(f)), nan(len(f))
    day, complete, ch, cl, cc, latched = None, False, 0.0, 0.0, 0.0, None
    for i, (s, h, l, c) in enumerate(zip(f["start"], f["high"], f["low"], f["close"])):
        d = s // MS_DAY
        if d != day:
            if day is not None and complete:
                latched = (ch, cl, cc)
            day, complete, ch, cl, cc = d, s % MS_DAY == 0, h, l, c
        else:
            ch, cl, cc = max(ch, h), min(cl, l), c
        if latched:
            pp = sum(latched) / 3
            p[i], r1[i], s1[i] = pp, 2 * pp - latched[1], 2 * pp - latched[0]
    return p, r1, s1


def seasonal_range(f: pd.DataFrame, window: int, ddof: int | None):
    """Mean (ddof None) or sample stdev of the last `window` PRIOR ranges of bars sharing this bar's UTC hour."""
    out, buckets = nan(len(f)), {h: [] for h in range(24)}
    for i, (s, h, l) in enumerate(zip(f["start"], f["high"], f["low"])):
        b = buckets[(s // MS_HOUR) % 24]
        if len(b) >= window:
            w = np.array(b[-window:])
            out[i] = w.mean() if ddof is None else w.std(ddof=ddof)
        b.append(h - l)
    return out


def session_momentum(f: pd.DataFrame, sh: int, eh: int, n_days: int) -> np.ndarray:
    """Sum over the last n completed days of (last in-window close / first in-window open - 1) for [sh, eh) UTC.
    A day counts only if its sh:00 bar was observed; a day completes when a later day's in-window bar arrives."""
    out, returns = nan(len(f)), []
    day, s_open, s_close = None, None, None
    for i, (s, o, c) in enumerate(zip(f["start"], f["open"], f["close"])):
        hour = (s // MS_HOUR) % 24
        if sh <= hour < eh:
            d = s // MS_DAY
            if d != day:
                if s_open is not None and s_close is not None:
                    returns.append(np.nan if s_open == 0 else s_close / s_open - 1)
                day = d
                s_open = o if s == d * MS_DAY + sh * MS_HOUR else None
            s_close = c
        if len(returns) >= n_days:
            out[i] = sum(returns[-n_days:])
    return out


def anchored_return(f: pd.DataFrame, bucket_minutes: int) -> np.ndarray:
    """close / open-of-the-current-bucket - 1 on the UTC grid; null when the bucket's first bar was not seen or opened at 0."""
    out, size = nan(len(f)), bucket_minutes * MS_MIN
    bucket, anchor = None, None
    for i, (s, o, c) in enumerate(zip(f["start"], f["open"], f["close"])):
        idx = s // size
        if idx != bucket:
            bucket, anchor = idx, (o if s % size == 0 else None)
        if anchor is not None and anchor != 0:
            out[i] = c / anchor - 1
    return out


def reopen_gap(f: pd.DataFrame, min_gap_hours: int):
    """At the first bar whose start follows the previous bar's end by more than minGapHours: size = reopen open - last
    close, origin = that close; fill = (reopen open - close) / size (null on a zero gap). All hold until the next reopen."""
    size, origin, fill = nan(len(f)), nan(len(f)), nan(len(f))
    g_origin, g_open = None, None
    for i, (s, e, o, c) in enumerate(zip(f["start"], f["end"], f["open"], f["close"])):
        if i > 0 and s - f["end"].iloc[i - 1] > min_gap_hours * MS_HOUR:
            g_origin, g_open = f["close"].iloc[i - 1], o
        if g_origin is not None:
            size[i], origin[i] = g_open - g_origin, g_origin
            fill[i] = np.nan if g_open == g_origin else (g_open - c) / (g_open - g_origin)
    return size, origin, fill


def failed_break(f: pd.DataFrame, range_len: int, reclaim_bars: int, arm_bars: int, high: bool) -> np.ndarray:
    """Reads 1 for armBars bars once the extreme of the prior rangeLen bars is pierced and a later bar within
    reclaimBars closes back inside; a pierce that keeps closing outside expires. Reads 0 from the bar that fills the
    range window (the first bar that can pierce is the next one); the compiler's warmup is one bar more conservative."""
    hs, ls, cs = f["high"].values, f["low"].values, f["close"].values
    out = nan(len(f))
    in_poke, poke_boundary, poke_age, armed = False, 0.0, 0, 0
    for i in range(len(f)):
        if armed > 0:
            armed -= 1
        if i == range_len - 1:
            out[i] = 0.0
        if i >= range_len:
            prior = hs[i - range_len : i] if high else ls[i - range_len : i]
            boundary = prior.max() if high else prior.min()
            if in_poke:
                poke_age += 1
                reclaimed = cs[i] < poke_boundary if high else cs[i] > poke_boundary
                if reclaimed:
                    armed, in_poke = arm_bars, False
                elif poke_age >= reclaim_bars:
                    in_poke = False
            pierced = hs[i] > boundary if high else ls[i] < boundary
            if not in_poke and armed == 0 and pierced:
                in_poke, poke_boundary, poke_age = True, boundary, 0
            out[i] = 1.0 if armed > 0 else 0.0
    return out


def ib_defended(f: pd.DataFrame, session_hour: int, ib_minutes: int, high: bool) -> np.ndarray:
    """1 once a bar has traded through the session's Initial-Balance edge (first ibMinutes from sessionStartHour UTC)
    and closed back inside, else 0; resets each session; null until the IB window has elapsed with an IB captured."""
    out = nan(len(f))
    off, ib_ms = session_hour * MS_HOUR, ib_minutes * MS_MIN
    session, ok, ib_hi, ib_lo, past, defended = None, False, None, None, False, False
    for i, (s, h, l, c) in enumerate(zip(f["start"], f["high"], f["low"], f["close"])):
        idx = (s - off) // MS_DAY
        if idx != session:
            session, ok, ib_hi, ib_lo, past, defended = idx, (s - off) % MS_DAY == 0, None, None, False, False
        if not ok:
            continue
        if (s - off) % MS_DAY < ib_ms:
            ib_hi = h if ib_hi is None else max(ib_hi, h)
            ib_lo = l if ib_lo is None else min(ib_lo, l)
        else:
            past = True
            edge = ib_hi if high else ib_lo
            if edge is not None and not defended:
                defended = (h >= edge and c < edge) if high else (l <= edge and c > edge)
        edge = ib_hi if high else ib_lo
        if past and edge is not None:
            out[i] = 1.0 if defended else 0.0
    return out


# --------------------------------------------------------------------------------------------------------------
# Fixture catalogue
# --------------------------------------------------------------------------------------------------------------


@dataclass
class Fixture:
    indicator: str
    args: list
    series: str
    input: str  # close | candle | tick | pair:close,open | bool:close>open
    tolerance: dict
    values: np.ndarray
    note: str = ""

    def name(self) -> str:
        a = "_".join(str(x) for x in self.args) or "noargs"
        return f"{self.indicator}__{a}__{self.series}"


def scaled(frame: pd.DataFrame, series_id: str):
    """synth_tiny sits below TA-Lib's 1e-8 zero guards, so TA-Lib runs on prices scaled by an exact power of two and
    scale-covariant outputs are divided back (exact in binary floating point). Returns (frame, scale)."""
    if series_id != "synth_tiny":
        return frame, 1.0
    k = 2.0**40
    g = frame.copy()
    for c in ("open", "high", "low", "close"):
        g[c] = g[c] * k
    return g, k


def numeric_fixtures(s: Series, f: pd.DataFrame, periods: dict) -> list[Fixture]:
    g, k = scaled(f, s.id)
    x = g["close"].values
    out = []
    p = periods
    flat_note = "qkt convention: a window with no up and no down moves reads 50 (TA-Lib 0)" if s.id == "synth_flat" else ""

    def add(name, args, values, tol, note="", inp="close", cov=True):
        v = values / k if (cov and k != 1.0) else values
        out.append(Fixture(name, args, s.id, inp, tol, v, note))

    add("SMA", [p["ma"]], sma(x, p["ma"]), EXACT)
    add("EMA", [p["ma"]], ema(x, p["ma"]), RECURSIVE)
    add("WMA", [p["ma"]], wma(x, p["ma"]), EXACT)
    # Nested EMAs read 8-decimal intermediates: 2 EMA + 1 EMA(EMA) reads bound DEMA at 1.5e-8, TEMA (3 + 3 + 1) at 3.5e-8.
    add("DEMA", [p["ma2"]], x.copy() if p["ma2"] == 1 else talib.DEMA(x, p["ma2"]), {"abs": 1.5e-8, "rel": 1e-9},
        "2 EMA - EMA(EMA) from 8-decimal EMA reads")
    add("TEMA", [p["ma2"]], x.copy() if p["ma2"] == 1 else talib.TEMA(x, p["ma2"]), {"abs": 3.5e-8, "rel": 1e-9},
        "3 EMA - 3 EMA(EMA) + EMA(EMA(EMA)) from 8-decimal EMA reads")
    add("HMA", [p["hma"]], hma(x, p["hma"]), {"abs": 2e-8, "rel": 1e-9}, "two 8-decimal intermediate WMAs can carry 1.5e-8 into the raw series")
    r = rsi(x, p["rsi"])
    if s.id == "synth_flat":
        r[~np.isnan(r)] = 50.0
    add("RSI", [p["rsi"]], r, RECURSIVE, flat_note, cov=False)
    # Formulas written here run on the raw values; only the TA-Lib calls above and below use the scaled copy.
    xr = f["close"].values
    add("STDDEV", [p["sd"]], stddev(xr, p["sd"], 1), SQRT, "sample (n-1) divisor", cov=False)
    add("VARIANCE", [p["sd"]], rolling(xr, p["sd"], lambda w: w.var(ddof=1)), EXACT, "sample (n-1) divisor", cov=False)
    add("ZSCORE", [p["sd"]], zscore(xr, p["sd"]), SQRT, "null on a flat window", cov=False)
    add("VARIANCE_RATIO", list(p["vr"]), variance_ratio(xr, *p["vr"]), EXACT, "population moments, overlapping sums; null when 1-bar variance is 0", cov=False)
    add("REGRESSION_SLOPE", [p["slope"]], regression_slope(xr, p["slope"]), EXACT, cov=False)
    add("PERCENTILE_RANK", [p["rank"]], percentile_rank(xr, p["rank"]), EXACT, "strictly-below count / N", cov=False)
    add("SKEW", [p["skew"]], skew(xr, p["skew"]), SQRT, "population g1 of simple returns; 0 on a dispersion-less window", cov=False)
    add("ER", [p["er"]], efficiency_ratio(xr, p["er"]), EXACT, "Kaufman; 0 on a zero path", cov=False)
    add("LAG", [p["lag"]], lag(xr, p["lag"]), DECIMAL_EXACT, cov=False)
    add("RUNLENGTH", [], runlength(xr), DECIMAL_EXACT, cov=False)
    add("RUNLENGTH_WHERE", [], runlength_where(f["close"].values > f["open"].values), DECIMAL_EXACT, "condition close > open", "bool:close>open", cov=False)
    add("HIGHEST", [p["don"]], highest(xr, p["don"]), DECIMAL_EXACT, "the N bars before the current one", cov=False)
    add("LOWEST", [p["don"]], lowest(xr, p["don"]), DECIMAL_EXACT, "the N bars before the current one", cov=False)
    line, sig, hist = macd(x, *p["macd"])
    for nm, v in (("MACD", line), ("MACD_SIGNAL", sig), ("MACD_HIST", hist)):
        add(nm, list(p["macd"]), v, {"abs": 2e-8, "rel": 1e-9},
            "EMA(fast) - EMA(slow), each SMA-seeded on its own first window; all three lines from bar slow + signal - 1; "
            "the signal EMA is fed 8-decimal EMA reads, so it can carry up to 1e-8")
    up, mid, lo = bollinger(x, p["bb"][0], p["bb"][1])
    bb_note = "population (n) standard deviation, as TA-Lib BBANDS; stddev() is sample (n-1)"
    add("BOLLINGER_UPPER", list(p["bb"]), up, SQRT, bb_note)
    add("BOLLINGER_MIDDLE", list(p["bb"]), mid, EXACT, bb_note)
    add("BOLLINGER_LOWER", list(p["bb"]), lo, SQRT, bb_note)
    a, b = f["close"].values, f["open"].values
    add("CORRELATION", [p["pair"]], correlation(a, b, p["pair"]), SQRT, "close vs open; null when either series is flat", "pair:close,open", cov=False)
    add("BETA", [p["pair"]], beta(a, b, p["pair"]), SQRT, "OLS slope of close on open (levels)", "pair:close,open", cov=False)
    return out


def candle_fixtures(s: Series, f: pd.DataFrame, periods: dict) -> list[Fixture]:
    g, k = scaled(f, s.id)
    out = []
    p = periods
    flat = s.id == "synth_flat"

    def add(name, args, values, tol, note="", inp="candle", cov=True):
        v = values / k if (cov and k != 1.0) else values
        out.append(Fixture(name, args, s.id, inp, tol, v, note))

    add("ATR", [p["atr"]], atr(g, p["atr"]), RECURSIVE, "Wilder; warmup N + 1 (the first true range needs a previous close)")
    ku, km, kl = keltner(g, *p["kc"])
    # The bands add an 8-decimal EMA read to k times an 8-decimal ATR read: half a unit each.
    kc_tol = {"abs": (0.5 + 0.5 * p["kc"][1]) * 1e-8, "rel": 1e-9}
    for nm, v in (("KELTNER_UPPER", ku), ("KELTNER_MIDDLE", km), ("KELTNER_LOWER", kl)):
        add(nm, list(p["kc"]), v, kc_tol if nm != "KELTNER_MIDDLE" else RECURSIVE, "EMA(close) +- k * Wilder ATR, from 8-decimal reads")
    pdi, mdi, ax = adx_family(g, p["adx"])
    adx_note = "Wilder; all three lines share the ADX warmup (2N) and are null while the smoothed true range is 0"
    add("PLUS_DI", [p["adx"]], pdi, RECURSIVE, adx_note, cov=False)
    add("MINUS_DI", [p["adx"]], mdi, RECURSIVE, adx_note, cov=False)
    add("ADX", [p["adx"]], ax, RECURSIVE, adx_note, cov=False)
    add("CCI", [p["cci"]], cci(g, p["cci"]), EXACT, "0.015 mean absolute deviation; 0 on a zero deviation", cov=False)
    add("WILLIAMS_R", [p["wr"]], williams_r(g, p["wr"]), EXACT, "qkt convention: -100 on a rangeless window (TA-Lib 0)" if flat else "", cov=False)
    kk, dd = stoch(g, p["stoch"][0], p["stoch"][1], 50.0)
    st_note = "fast %K (no slowing; TA-Lib STOCHF)" + ("; qkt convention: 50 on a rangeless window (TA-Lib 0)" if flat else "")
    add("STOCH_K", list(p["stoch"]), kk, EXACT, st_note, cov=False)
    add("STOCH_D", list(p["stoch"]), dd, EXACT, st_note, cov=False)
    add("OBV", [], obv(f), EXACT, "starts at 0 on the first candle (TA-Lib starts at volume[0])", cov=False)
    add("VWAP", [p["vwap"]], vwap_ticks(f, p["vwap"]), EXACT, "one tick per bar at the close with the bar's volume", "tick", cov=False)
    vw, sd = session_vwap(f, p["vws"])
    add("VWAP_SESSION", [p["vws"]], vw, EXACT, "typical price, cumulative since anchorHour UTC; null until the anchor candle is seen", cov=False)
    add("VWAP_SESSION_STDEV", [p["vws"]], sd, SQRT, "volume-weighted population stdev of typical price around the session VWAP", cov=False)
    hi, lo = session_range(f, *p["sr"])
    add("SESSION_RANGE_HIGH", list(p["sr"]), hi, DECIMAL_EXACT, "latched at the first bar outside a completed instance", cov=False)
    add("SESSION_RANGE_LOW", list(p["sr"]), lo, DECIMAL_EXACT, "latched at the first bar outside a completed instance", cov=False)
    pp, r1, s1 = pivots(f)
    for nm, v in (("PIVOT_P", pp), ("PIVOT_R1", r1), ("PIVOT_S1", s1)):
        add(nm, [], v, EXACT, "prior UTC day observed from 00:00", cov=False)
    add("SEASONAL_RANGE", [p["season"]], seasonal_range(f, p["season"], None), EXACT, "mean of prior same-hour ranges", cov=False)
    add("SEASONAL_RANGE_STDEV", [p["season"]], seasonal_range(f, p["season"], 1), SQRT, "sample (n-1) stdev of prior same-hour ranges", cov=False)
    add("SESSION_MOMENTUM", list(p["sm"]), session_momentum(f, *p["sm"]), EXACT, "sum of completed in-window day returns; a day whose start bar is missing is skipped", cov=False)
    add("ANCHORED_RETURN", [p["ar"]], anchored_return(f, p["ar"]), EXACT, "null when the bucket open was not seen or is 0", cov=False)
    sz, org, fl = reopen_gap(f, p["gap"])
    add("REOPEN_GAP", [p["gap"]], sz, EXACT, "start - previous end > minGapHours", cov=False)
    add("REOPEN_GAP_ORIGIN", [p["gap"]], org, DECIMAL_EXACT, cov=False)
    add("GAP_FILL_FRACTION", [p["gap"]], fl, EXACT, "null on a zero gap", cov=False)
    add("FAILED_BREAK_HIGH", list(p["fb"]), failed_break(f, *p["fb"], True), DECIMAL_EXACT, cov=False)
    add("FAILED_BREAK_LOW", list(p["fb"]), failed_break(f, *p["fb"], False), DECIMAL_EXACT, cov=False)
    add("IB_DEFENDED_HIGH", list(p["ib"]), ib_defended(f, *p["ib"], True), DECIMAL_EXACT, "a touch at the edge counts as a test", cov=False)
    add("IB_DEFENDED_LOW", list(p["ib"]), ib_defended(f, *p["ib"], False), DECIMAL_EXACT, "a touch at the edge counts as a test", cov=False)
    return out


SYNTH_PERIODS = dict(ma=5, ma2=3, hma=9, rsi=14, sd=5, vr=(2, 6), slope=5, rank=5, skew=5, er=5, lag=2, don=5,
                     macd=(3, 6, 3), bb=(5, 2), pair=5, atr=5, kc=(5, 2), adx=3, cci=5, wr=5, stoch=(5, 3), vwap=5,
                     vws=0, sr=(0, 0, 7, 0), season=2, sm=(0, 3, 1), ar=60, gap=12, fb=(5, 2, 3), ib=(0, 120))

PERIODS = {
    "xau_1h": dict(ma=20, ma2=9, hma=14, rsi=14, sd=20, vr=(2, 20), slope=20, rank=20, skew=20, er=10, lag=1, don=20,
                   macd=(12, 26, 9), bb=(20, 2), pair=20, atr=14, kc=(20, 2), adx=14, cci=20, wr=14, stoch=(14, 3),
                   vwap=20, vws=0, sr=(0, 0, 7, 0), season=5, sm=(12, 14, 3), ar=240, gap=12, fb=(20, 3, 6), ib=(8, 60)),
    "btc_1m": dict(ma=50, ma2=20, hma=20, rsi=2, sd=60, vr=(5, 120), slope=5, rank=100, skew=50, er=20, lag=21, don=1,
                   macd=(5, 13, 4), bb=(20, 2.5), pair=60, atr=14, kc=(20, 1.5), adx=14, cci=14, wr=14, stoch=(14, 3),
                   vwap=100, vws=12, sr=(22, 30, 1, 15), season=3, sm=(1, 3, 1), ar=30, gap=1, fb=(20, 3, 6), ib=(0, 60)),
    "btc_1h": dict(ma=9, ma2=5, hma=55, rsi=21, sd=10, vr=(3, 30), slope=10, rank=10, skew=10, er=5, lag=5, don=10,
                   macd=(2, 3, 2), bb=(2, 1), pair=5, atr=2, kc=(2, 2), adx=2, cci=3, wr=3, stoch=(5, 1), vwap=5,
                   vws=0, sr=(0, 0, 7, 0), season=20, sm=(12, 14, 3), ar=240, gap=12, fb=(10, 2, 4), ib=(0, 60)),
}
PERIOD_ONE = dict(SYNTH_PERIODS, ma=1, ma2=1, atr=1, don=1, kc=(1, 2), bb=(1, 2), cci=1, wr=1, stoch=(1, 1), vwap=1, lag=1)


def indicator_fixtures(series: dict[str, Series]) -> list[Fixture]:
    out = []
    for sid, s in series.items():
        f = s.frame()
        periods = PERIODS.get(sid, SYNTH_PERIODS)
        out += numeric_fixtures(s, f, periods) + candle_fixtures(s, f, periods)
    one = series["synth_gap"]
    fx = numeric_fixtures(one, one.frame(), PERIOD_ONE) + candle_fixtures(one, one.frame(), PERIOD_ONE)
    out += [x for x in fx if x.indicator in {"SMA", "EMA", "WMA", "DEMA", "TEMA", "ATR", "HIGHEST", "LOWEST", "LAG",
                                            "KELTNER_UPPER", "KELTNER_MIDDLE", "KELTNER_LOWER", "BOLLINGER_UPPER",
                                            "BOLLINGER_MIDDLE", "BOLLINGER_LOWER", "CCI", "WILLIAMS_R", "STOCH_K",
                                            "STOCH_D", "VWAP"}]
    return out


# --------------------------------------------------------------------------------------------------------------
# Numeric functions (FuncRegistry): Python math / decimal / scipy / numpy on a fixed grid
# --------------------------------------------------------------------------------------------------------------

GRID = ["0", "1", "-1", "2", "-2", "0.5", "-0.5", "2.5", "-2.5", "3.5", "-3.5", "0.1", "0.33333333", "1e-12", "-1e-12",
        "1e-6", "0.00000001", "16", "9", "100", "94273.12345678", "2347", "-2347.5", "1e12", "-1e12", "1e300", "10000",
        "-800", "-20", "-8", "6", "-6", "1.5", "2.00000001", "-0.49999999"]


def dstr(x: float) -> str:
    return repr(float(x))


def unary_double(fn, domain=lambda v: True):
    cases = []
    for g in GRID:
        v = float(g)
        try:
            cases.append({"args": [g], "expected": dstr(fn(v)) if domain(v) else None})
        except (ValueError, OverflowError, ZeroDivisionError):
            cases.append({"args": [g], "expected": None})
    return cases


def exact_cases(fn, arg_lists):
    cases = []
    with localcontext() as ctx:
        ctx.prec = 60
        for args in arg_lists:
            r = fn([Decimal(a) for a in args])
            cases.append({"args": list(args), "expected": None if r is None else str(r)})
    return cases


def q16(x: Decimal) -> Decimal:
    with localcontext() as ctx:
        ctx.prec, ctx.rounding = 16, ROUND_HALF_EVEN
        return +x


def pow_cases():
    pairs = [("2", "10"), ("9", "0.5"), ("10", "-400"), ("-8", "0.33333333"), ("0", "0"), ("0", "-1"), ("2", "0.5"),
             ("1.0001", "365"), ("10", "308"), ("10", "309"), ("-2", "3"), ("-2", "0.5"), ("94273.12345678", "2"),
             ("1e-6", "3"), ("0.5", "-1074"), ("0.5", "-1075"), ("2", "1023"), ("2", "1024")]
    out = []
    for a, b in pairs:
        try:
            r = math.pow(float(a), float(b))
            out.append({"args": [a, b], "expected": None if math.isinf(r) or math.isnan(r) else dstr(r)})
        except (ValueError, OverflowError, ZeroDivisionError):
            out.append({"args": [a, b], "expected": None})
    return out


def softmax_cases():
    sets = [["1", "2", "3"], ["3", "1", "2"], ["0", "0"], ["1000", "999", "998"], ["-1000", "0"], ["5", "5", "5", "5"],
            ["0.1", "0.2"], ["1e12", "0"], ["0", "1e12"], ["710", "0"], ["-745", "0"]]
    out = []
    for s in sets:
        v = np.array([float(x) for x in s])
        e = np.exp(v - v.max())
        out.append({"args": s, "expected": dstr(e[0] / e.sum())})
    return out


def floor_mod(a: Decimal, b: Decimal):
    return None if b == 0 else a - b * (a / b).to_integral_value(rounding=ROUND_FLOOR)


def round_to(x: Decimal, step: Decimal):
    return None if step == 0 else (x / step).to_integral_value(rounding=ROUND_HALF_EVEN) * step


def normalize(v: list[Decimal]):
    lo, hi = min(v), max(v)
    if hi == lo:
        return Decimal(0)
    with localcontext() as ctx:
        ctx.prec, ctx.rounding = 16, ROUND_HALF_EVEN
        return (v[0] - lo) / (hi - lo)


def function_fixtures() -> list[dict]:
    pairs2 = [("1.2034", "0.0050"), ("2347", "25"), ("31.27", "0.5"), ("-7", "3"), ("7", "-3"), ("-7", "-3"), ("7", "0"),
              ("0", "5"), ("5.5", "5.5"), ("94273.12345678", "100"), ("1e12", "7"), ("-0.00000001", "0.00000003")]
    var = [["1", "2", "3"], ["3", "1", "2"], ["2", "2", "2"], ["-1", "-1.5", "0"], ["1e12", "-1e12", "0"], ["0.1", "0.2"],
           ["5", "1", "5"], ["94273.12345678", "94273.12345677"]]
    unary = [[g] for g in GRID]
    return [
        {"function": "ABS", "oracle": "decimal", "tolerance": DECIMAL_EXACT, "cases": exact_cases(lambda a: abs(a[0]), unary)},
        {"function": "FLOOR", "oracle": "decimal", "tolerance": DECIMAL_EXACT, "cases": exact_cases(lambda a: a[0].to_integral_value(rounding=ROUND_FLOOR), unary)},
        {"function": "CEIL", "oracle": "decimal", "tolerance": DECIMAL_EXACT, "cases": exact_cases(lambda a: a[0].to_integral_value(rounding="ROUND_CEILING"), unary)},
        {"function": "ROUND", "oracle": "decimal, half-even", "tolerance": DECIMAL_EXACT, "cases": exact_cases(lambda a: a[0].to_integral_value(rounding=ROUND_HALF_EVEN), unary)},
        {"function": "MIN", "oracle": "decimal", "tolerance": DECIMAL_EXACT, "cases": exact_cases(min, var)},
        {"function": "MAX", "oracle": "decimal", "tolerance": DECIMAL_EXACT, "cases": exact_cases(max, var)},
        {"function": "MOD", "oracle": "decimal floored modulo a - b*floor(a/b); null for b = 0", "tolerance": DECIMAL_EXACT, "cases": exact_cases(lambda a: floor_mod(a[0], a[1]), pairs2)},
        {"function": "ROUND_TO", "oracle": "decimal round_half_even(x/step)*step; null for step = 0", "tolerance": DECIMAL_EXACT, "cases": exact_cases(lambda a: round_to(a[0], a[1]), pairs2)},
        {"function": "RANK_OF", "oracle": "competition rank: 1 + count strictly greater than the first", "tolerance": DECIMAL_EXACT, "cases": exact_cases(lambda a: Decimal(1 + sum(1 for v in a if v > a[0])), var)},
        {"function": "NORMALIZE", "oracle": "decimal (x0 - min)/(max - min) at 16 digits; 0 when all equal", "tolerance": {"abs": 0.0, "rel": 1e-15}, "cases": exact_cases(normalize, var)},
        {"function": "SQRT", "oracle": "math.sqrt; null for x < 0", "tolerance": DOUBLE, "cases": unary_double(math.sqrt, lambda v: v >= 0)},
        {"function": "LOG", "oracle": "math.log; null for x <= 0", "tolerance": DOUBLE, "cases": unary_double(math.log, lambda v: v > 0)},
        {"function": "EXP", "oracle": "math.exp; null on overflow", "tolerance": DOUBLE, "cases": unary_double(math.exp)},
        {"function": "NORM_CDF", "oracle": "scipy.stats.norm.cdf; qkt's Hart/West (2005) approximation is accurate to ~1e-8 relative in the far tail",
         "tolerance": {"abs": 1e-300, "rel": 2e-8}, "cases": unary_double(lambda v: float(scipy.stats.norm.cdf(v)))},
        {"function": "POW", "oracle": "math.pow; null when the double result is infinite or NaN", "tolerance": DOUBLE, "cases": pow_cases()},
        {"function": "SOFTMAX", "oracle": "numpy exp(x - max) / sum", "tolerance": DOUBLE, "cases": softmax_cases()},
    ]


# --------------------------------------------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------------------------------------------


def to_json_values(v: np.ndarray) -> list:
    return [None if (isinstance(x, float) and math.isnan(x)) else float(x) for x in v.tolist()]


def write_fixtures(fixtures: list[Fixture], functions: list[dict]) -> None:
    ind_dir, fn_dir = FIXTURE_DIR / "indicators", FIXTURE_DIR / "functions"
    for d in (ind_dir, fn_dir):
        d.mkdir(parents=True, exist_ok=True)
        for old in d.glob("*.json"):
            old.unlink()
    names = set()
    for fx in fixtures:
        assert fx.name() not in names, fx.name()
        names.add(fx.name())
        doc = {"indicator": fx.indicator, "args": [str(a) for a in fx.args], "series": fx.series, "input": fx.input,
               "oracle": ORACLE, "tolerance": fx.tolerance, "note": fx.note, "values": to_json_values(fx.values)}
        (ind_dir / f"{fx.name()}.json").write_text(json.dumps(doc, separators=(",", ":")) + "\n")
    for fn in functions:
        fn = dict(fn, oracle=f"{fn['oracle']}; {ORACLE}")
        (fn_dir / f"{fn['function']}.json").write_text(json.dumps(fn, indent=1) + "\n")
    print(f"wrote {len(fixtures)} indicator fixtures and {len(functions)} function fixtures to {FIXTURE_DIR}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--refresh-series", action="store_true", help="rebuild series/ from the local qkt bar store")
    ap.add_argument("--store", default=str(Path.home() / ".qkt" / "data"), help="qkt data store root")
    a = ap.parse_args()
    np.seterr(all="ignore")  # NaN arithmetic in short windows is the "undefined" signal, not an error
    warnings.simplefilter("ignore", RuntimeWarning)
    if a.refresh_series:
        write_series(real_series(Path(a.store).expanduser()) + synthetic_series())
    series = load_series()
    if not series:
        print("no series found; run with --refresh-series", file=sys.stderr)
        return 1
    write_fixtures(indicator_fixtures(series), function_fixtures())
    return 0


if __name__ == "__main__":
    sys.exit(main())

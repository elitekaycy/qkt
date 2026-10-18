"""Fetches the newest complete day from each free source into a scratch corpus and renders the drift cases for
it, so tests/data-e2e/run.py --unpinned can check today's vendor files the way the pinned corpus is checked.

    python3 -I tests/data-e2e/drift/fetch.py --out /tmp/drift      # writes /tmp/drift/corpus and /tmp/drift/cases.toml

A source that does not answer is listed in <out>/unavailable.txt and its cases left out; a file whose vendor
checksum does not match fails the fetch. Network only; nothing here runs on a pull request.
"""

import argparse
import datetime as dt
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
UA = {"User-Agent": "Mozilla/5.0 (qkt data drift check)"}


def get(url: str, dest: Path, tries: int = 6) -> bool:
    dest.parent.mkdir(parents=True, exist_ok=True)
    for n in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90) as r:
                data = r.read()
            if data:
                dest.write_bytes(data)
                return True
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            print(f"  retry {n + 1} {url}: {e}", file=sys.stderr)
        time.sleep(5 * (n + 1))
    return False


def checksum_ok(url: str, dest: Path) -> bool:
    side = dest.with_name(dest.name + ".CHECKSUM")
    if not get(url + ".CHECKSUM", side):
        return False
    return side.read_text().split()[0] == hashlib.sha256(dest.read_bytes()).hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--day", default=None, help="UTC day to fetch (default: two days ago)")
    a = ap.parse_args()
    out = Path(a.out)
    corpus = out / "corpus"
    day = dt.date.fromisoformat(a.day) if a.day else dt.datetime.now(dt.timezone.utc).date() - dt.timedelta(days=2)
    start = int(dt.datetime(day.year, day.month, day.day, tzinfo=dt.timezone.utc).timestamp() * 1000)
    unavailable, groups = [], []

    def need(group: str, ok: bool) -> None:
        if not ok:
            unavailable.append(group)
        elif group not in groups:
            groups.append(group)

    b = "https://data.binance.vision/data"
    ok = True
    for kind in ("trades", "1m", "1h", "1d"):
        sub = f"spot/daily/klines/ADAUSDT/{kind}" if kind != "trades" else "spot/daily/trades/ADAUSDT"
        name = f"ADAUSDT-{kind}-{day}.zip"
        url = f"{b}/{sub}/{name}"
        dest = corpus / "binance/spot" / name
        if not get(url, dest):
            ok = False
        elif not checksum_ok(url, dest):
            print(f"data drift: {url} does not match Binance's published checksum", file=sys.stderr)
            return 1
    need("binance", ok)

    ok = get(f"https://public.bybit.com/spot/ADAUSDT/ADAUSDT_{day}.csv.gz", corpus / f"bybit/ADAUSDT_{day}.csv.gz")
    for part in (1, 2):
        s = start + (part - 1) * 720 * 60_000
        url = f"https://api.bybit.com/v5/market/kline?category=spot&symbol=ADAUSDT&interval=1&start={s}&end={s + 719 * 60_000}&limit=1000"
        ok = ok and get(url, corpus / f"bybit/ADAUSDT-spot-kline-1m-{day}-p{part}.json")
    need("bybit", ok)

    ok = get(
        f"https://static.okx.com/cdn/okex/traderecords/trades/daily/{day:%Y%m%d}/BTC-USDT-trades-{day}.zip",
        corpus / f"okx/BTC-USDT-trades-{day}.zip",
    )
    need("okx", ok)

    ok = True
    month0 = f"{day.month - 1:02d}"
    folder = corpus / f"dukascopy/EURUSD/{day.year}/{month0}/{day.day:02d}"
    base = f"https://datafeed.dukascopy.com/datafeed/EURUSD/{day.year}/{month0}/{day.day:02d}"
    for h in range(24):
        ok = ok and get(f"{base}/{h:02d}h_ticks.bi5", folder / f"{h:02d}h_ticks.bi5", tries=8)
    for side in ("BID", "ASK"):
        ok = ok and get(f"{base}/{side}_candles_min_1.bi5", folder / f"{side}_candles_min_1.bi5", tries=8)
    need("dukascopy", ok and day.weekday() < 5)

    ok = get(
        "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=IBM&apikey=demo&datatype=csv",
        corpus / "alphavantage/IBM-daily.csv",
    )
    need("alphavantage", ok and (corpus / "alphavantage/IBM-daily.csv").read_text().startswith("timestamp,"))

    try:
        import yfinance as yf  # optional: the workflow installs it

        (corpus / "yfinance").mkdir(parents=True, exist_ok=True)
        yf.download("SPY", period="1mo", interval="1d", auto_adjust=False, multi_level_index=False, progress=False).to_csv(corpus / "yfinance/SPY-1d.csv")
        yf.download("AAPL", period="2d", interval="1m", auto_adjust=False, multi_level_index=False, progress=False).to_csv(corpus / "yfinance/AAPL-1m.csv")
        yf.download(["AAPL", "MSFT"], period="1mo", interval="1d", auto_adjust=False, progress=False).to_csv(corpus / "yfinance/AAPL-MSFT-1d-multi.csv")
        need("yfinance", True)
    except Exception as e:  # noqa: BLE001 - any failure of the client is the source being unavailable
        print(f"  yfinance: {e}", file=sys.stderr)
        need("yfinance", False)

    template = (HERE / "cases.template.toml").read_text()
    blocks = template.split("\n# @group ")
    rendered = [blocks[0]] + [blk for blk in blocks[1:] if blk.split("\n", 1)[0].strip() in groups]
    month0_day = f"{day.year}/{month0}/{day.day:02d}"
    text = "\n# @group ".join(rendered).replace("{day}", str(day)).replace("{dukascopy_day}", month0_day)
    (out / "cases.toml").write_text(text)
    (out / "unavailable.txt").write_text("\n".join(unavailable) + ("\n" if unavailable else ""))
    print(json.dumps({"day": str(day), "checked": groups, "unavailable": unavailable}))
    return 0


if __name__ == "__main__":
    sys.exit(main())

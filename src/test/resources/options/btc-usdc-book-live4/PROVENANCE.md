# BTC_USDC live book, four snapshots — provenance

Four real Deribit book snapshots of every live BTC_USDC option (614 contracts each), taken anonymously
on 2026-10-01 between 06:15:16Z and 06:18:20Z, about a minute apart, with qkt itself:

```bash
qkt fetch DERIBIT:BTC_USDC --catalog
for i in 1 2 3 4; do qkt fetch DERIBIT:BTC_USDC --chains --live; sleep 60; done
```

The chain file holds the four snapshots unchanged (2456 rows). The catalog keeps the listings of those
614 contracts. `instruments.yaml` declares the root to trade the book series, with no fees.
`StructureBacktestTest` reads each fill's quote straight from this file.

| File | SHA-256 |
|---|---|
| `chains/DERIBIT/BTC_USDC/book/2026-10-01.csv.gz` | `fb5786f959d24d4c29d036d7bba9ed45d73c0f09d2664f81893226ab86adb527` |
| `contracts/DERIBIT/BTC_USDC.options.json` | `aa71cec2f7f21e0a17de216efa784017a2a7e38840e738c46412ba34a7a852b2` |
| `instruments.yaml` | `2dc2f7f9384db712d6483d9be345dd456ecc6df21f101c9f192101829f308e53` |

## Independent Greeks (`StructureGreeksTest`)

Black-76 with `math.erf`, rate 0, forward = the median `underlying` of the expiry's usable quotes (IV > 0, mark age
at the clock <= 1 h), T from the clock, 30 s after the first snapshot. Short 0.19 of 9OCT26 82000 P, long 0.19 of 79000 P:
delta 0.030679607082869577, gamma -7.757036237081573e-06, vega -3.537833577427546 per vol point,
theta 6.148692334513596 per day.

```python
# Independent Black-76 Greeks for a held put spread on the live4 fixture (no qkt code).
import gzip, csv, json, math, statistics
D = './'
rows = list(csv.DictReader(gzip.open(D + 'chains/DERIBIT/BTC_USDC/book/2026-10-01.csv.gz', 'rt')))
cat = {c['symbol']: c for c in json.load(open(D + 'contracts/DERIBIT/BTC_USDC.options.json'))['contracts']}
first = min(int(r['atMs']) for r in rows)
snap = [r for r in rows if int(r['atMs']) == first]
MAX_AGE = 3_600_000
now = first + 30_000
def usable(r):
    # judged at now: unexpired then, and the quote aged by the time since the snapshot
    c = cat.get(r['contract'])
    return c and c['expiryMs'] > now and r['markIv'] and float(r['markIv']) > 0 and int(r['markAgeMs']) + (now - first) <= MAX_AGE
N = lambda x: 0.5 * (1 + math.erf(x / math.sqrt(2)))
n = lambda x: math.exp(-x * x / 2) / math.sqrt(2 * math.pi)
now = first + 30_000
legs = [('BTC_USDC-9OCT26-82000-P', -0.19), ('BTC_USDC-9OCT26-79000-P', 0.19)]
tot = dict(delta=0, gamma=0, vega=0, theta=0)
for name, q in legs:
    c = cat[name]; e = c['expiryMs']
    same = [r for r in snap if usable(r) and cat[r['contract']]['expiryMs'] == e]
    F = statistics.median(float(r['underlying']) for r in same)
    r = next(r for r in snap if r['contract'] == name)
    s = float(r['markIv']) / 100; K = float(c['strike']); T = (e - now) / (365 * 86_400_000)
    d1 = (math.log(F / K) + s * s * T / 2) / (s * math.sqrt(T))
    g = dict(delta=N(d1) - 1, gamma=n(d1) / (F * s * math.sqrt(T)), vega=F * n(d1) * math.sqrt(T) / 100,
             theta=-F * n(d1) * s / (2 * math.sqrt(T)) / 365)
    print(name, 'F', F, 'iv', s, 'T', T, g)
    for k in tot: tot[k] += q * g[k]
print('first', first, 'now', now)
for k, v in tot.items(): print(k, repr(v))
```

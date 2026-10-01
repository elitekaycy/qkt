# BTC_USDC trade-chain days 2026-09-25/26 — provenance

The complete hourly trade-built BTC_USDC chains of two days (every contract, 3,156 and 2,557 rows),
fetched anonymously on 2026-10-01 with qkt itself:

```bash
qkt fetch DERIBIT:BTC_USDC --catalog
qkt fetch DERIBIT:BTC_USDC --chains --from 2026-09-25 --to 2026-09-26
```

The catalog keeps the 237 contracts those days quote and the 25–27 September `btc_usdc` delivery
prices. `instruments.yaml` declares the root to trade the trade series with a 5% mark spread.

| File | SHA-256 |
|---|---|
| `chains/DERIBIT/BTC_USDC/trade/2026-09-25.csv.gz` | `381218597a9aafbb58fbaf3047d09da5ab67d17ec2915d73cc25b04f7cbd5d97` |
| `chains/DERIBIT/BTC_USDC/trade/2026-09-26.csv.gz` | `189d1c47542db586b16809e3a1e70bb27fd2ba2bd85d76b9ba678316e733ad11` |
| `contracts/DERIBIT/BTC_USDC.options.json` | `bbee71336c10280e6047daa5cf639ab99d26ba10268556ad054e86d8f08a8d6a` |
| `instruments.yaml` | `e122a615c62d270a722efa07612efc4639d83634bd1855e1aae66930e0020e8f` |

## Expected `atm_iv.1d` (ChainAnalyticsBacktestTest)

An independent Python implementation of the phase 43.4 ATM rule finds the metric defined at 24 of the
48 hourly instants (the others lack fresh quotes around the 1-day tenor). The only value above 41 is
41.64045509 at 1790424000000 (2026-09-26T12:00:00Z).

```python
import gzip,json,math,statistics
fx='src/test/resources/options/btc-usdc-trade-25sep26'
cat={c['symbol']:c for c in json.load(open(fx+'/contracts/DERIBIT/BTC_USDC.options.json'))['contracts']}
Y=365*24*3600*1000
snaps={}
for d in ['2026-09-25','2026-09-26']:
    for l in gzip.open(f'{fx}/chains/DERIBIT/BTC_USDC/trade/{d}.csv.gz','rt').read().splitlines()[1:]:
        r=l.split(','); snaps.setdefault(int(r[0]),[]).append(r)
def atm(t,rows,days):
    by={}
    for r in rows:
        if r[5]=='' or int(r[8])>3600000: continue
        c=cat[r[1]]; by.setdefault(c['expiryMs'],[]).append((float(c['strike']),float(r[5]),float(r[6])))
    pts=[]
    for e,q in sorted(by.items()):
        T=(e-t)/Y
        if T<=0: continue
        F=statistics.median([x[2] for x in q]); k=min({x[0] for x in q},key=lambda s:(abs(s-F),s))
        pts.append((T,statistics.mean([x[1] for x in q if x[0]==k])))
    tau=days/365
    for T,v in pts:
        if T==tau: return v
    for (T1,a),(T2,b) in zip(pts,pts[1:]):
        if T1<=tau<=T2: return math.sqrt((a*a*T1+(b*b*T2-a*a*T1)*(tau-T1)/(T2-T1))/tau)
    return None
vals=[(t,atm(t,rows,1)) for t,rows in sorted(snaps.items())]
d=[(t,v) for t,v in vals if v is not None]
print('instants',len(vals),'defined',len(d))
for t,v in d[:30]: print(t, round(v,8))
```

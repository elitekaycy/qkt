# BTC_USDC book snapshot fixture — provenance

One real Deribit book snapshot of every live BTC_USDC option (614 contracts, 8 expiries), taken
anonymously on 2026-10-01 with qkt itself:

```bash
qkt fetch DERIBIT:BTC_USDC --catalog
qkt fetch DERIBIT:BTC_USDC --chains --live
```

The chain file holds the first snapshot of that day (instant 1790822716427, unchanged rows); the
catalog keeps the listings of those 614 contracts.

| File | SHA-256 |
|---|---|
| `chains/DERIBIT/BTC_USDC/book/2026-10-01.csv.gz` | `a5e3b6ea461865d475bdb5e48a46112825e2178a65a2abdc6da21d29b1c04e05` |
| `contracts/DERIBIT/BTC_USDC.options.json` | `aa71cec2f7f21e0a17de216efa784017a2a7e38840e738c46412ba34a7a852b2` |

## Expected analytics (ChainAnalyticsTest)

Computed by this independent Python implementation of the phase 43.4 rules (no qkt code; normal CDF
from `math.erf`):

```python
import gzip,json,math,statistics,sys
fx='src/test/resources/options/btc-usdc-book-20261001'
rows=[l.split(',') for l in gzip.open(fx+'/chains/DERIBIT/BTC_USDC/book/2026-10-01.csv.gz','rt').read().splitlines()[1:]]
cat={c['symbol']:c for c in json.load(open(fx+'/contracts/DERIBIT/BTC_USDC.options.json'))['contracts']}
t=int(rows[0][0]); Y=365*24*3600*1000
def N(x): return 0.5*(1+math.erf(x/math.sqrt(2)))
by={}
for r in rows:
    if r[5]=='' or int(r[8])>3600000: continue
    c=cat[r[1]]; by.setdefault(c['expiryMs'],[]).append((float(c['strike'].replace('d','.')) if False else float(c['strike']), c['right'], float(r[5]), float(r[6]), float(r[7]) if r[7] else 0.0))
exp={}
for e,q in sorted(by.items()):
    T=(e-t)/Y
    if T<=0: continue
    F=statistics.median([x[3] for x in q])
    ks=sorted({x[0] for x in q}); k=min(ks,key=lambda s:(abs(s-F),s))
    atm=statistics.mean([x[2] for x in q if x[0]==k])
    def delta(K,right,iv,rate):
        s=iv/100; d1=(math.log(F/K)+0.5*s*s*T)/(s*math.sqrt(T)); df=math.exp(-rate*T)
        return df*N(d1) if right=='call' else -df*N(-d1)
    calls=sorted(((delta(K,'call',iv,r),iv) for K,rt,iv,_,r in q if rt=='call'))
    puts=sorted(((delta(K,'put',iv,r),iv) for K,rt,iv,_,r in q if rt=='put'))
    def at(pts,target):
        for (d0,v0),(d1,v1) in zip(pts,pts[1:]):
            if d0<=target<=d1:
                return v0 if d1==d0 else v0+(v1-v0)*(target-d0)/(d1-d0)
        return None
    c25=at(calls,0.25); p25=at(puts,-0.25)
    exp[e]=(T,F,k,atm,None if c25 is None or p25 is None else p25-c25)
def tenor(days,idx):
    tau=days/365
    items=[(T,v[idx]) for e,(T,*rest) in sorted(exp.items()) for v in [exp[e]]]
    pts=[(v[0],v[idx]) for e,v in sorted(exp.items()) if v[idx] is not None]
    for (T1,a),(T2,b) in zip(pts,pts[1:]):
        if T1<=tau<=T2:
            if idx==3:
                w=a*a*T1+(b*b*T2-a*a*T1)*(tau-T1)/(T2-T1); return math.sqrt(w/tau)
            return a+(b-a)*(tau-T1)/(T2-T1)
    for T1,a in pts:
        if T1==tau: return a
    return None
print('at',t,'expiries',len(exp))
for e,v in list(sorted(exp.items()))[:8]: print(e, 'T=%.6f F=%.2f K=%s atm=%.4f skew=%s'%(v[0],v[1],v[2],v[3],v[4]))
for d in [1,7,30,90,400]: print('atm_iv.%dd'%d, tenor(d,3), ' skew_25d.%dd'%d, tenor(d,4))
```

Output: `atm_iv.1d` 30.348051825995896, `atm_iv.7d` 30.906520486637266, `atm_iv.30d` 33.80563317134286,
`skew_25d.7d` 0.662717312518918, `skew_25d.30d` 1.068997868586103; 90-day and longer tenors have no
value (the last expiry is 85 days out).

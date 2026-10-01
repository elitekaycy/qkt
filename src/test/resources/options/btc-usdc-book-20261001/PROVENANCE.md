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
MONEY=0.10; GAP=0.15; Y=365*24*3600*1000
def N(x): return 0.5*(1+math.erf(x/math.sqrt(2)))
def metrics(t,rows,cat,maxage=3600000):
    by={}
    for r in rows:
        if r[5]=='' or int(r[8])>maxage or r[1] not in cat: continue
        iv=float(r[5])
        if not (iv>0 and math.isfinite(iv)): continue
        c=cat[r[1]]; by.setdefault(c['expiryMs'],[]).append((float(c['strike']),c['right'],iv,float(r[6])))
    out=[]
    for e,q in sorted(by.items()):
        T=(e-t)/Y
        if T<=0: continue
        F=statistics.median([x[3] for x in q])
        strikes=sorted({x[0] for x in q})
        ivk={k:statistics.mean([x[2] for x in q if x[0]==k]) for k in strikes}
        below=[k for k in strikes if k<=F]; above=[k for k in strikes if k>=F]
        atm=None
        if below and above:
            k1,k2=max(below),min(above)
            if abs(k1-F)/F<=MONEY and abs(k2-F)/F<=MONEY:
                atm=ivk[k1] if k1==k2 else ivk[k1]+(ivk[k2]-ivk[k1])*(F-k1)/(k2-k1)
        def delta(K,right,iv):
            s=iv/100; d1=(math.log(F/K)+0.5*s*s*T)/(s*math.sqrt(T))
            return N(d1) if right=='call' else -N(-d1)
        def wing(right,target):
            pts=sorted((delta(K,right,iv),iv) for K,rt,iv,_ in q if rt==right)
            for (d0,v0),(d1,v1) in zip(pts,pts[1:]):
                if d0<=target<=d1 and abs(d0-target)<=GAP and abs(d1-target)<=GAP:
                    return v0 if d1==d0 else v0+(v1-v0)*(target-d0)/(d1-d0)
            return None
        c25=wing('call',0.25); p25=wing('put',-0.25)
        out.append((T,atm,None if c25 is None or p25 is None else p25-c25))
    return out
def tenor(pts,days,idx):
    tau=days/365; p=[(x[0],x[idx]) for x in pts if x[idx] is not None]
    for (T1,a),(T2,b) in zip(p,p[1:]):
        if T1<=tau<=T2:
            if idx==1: return math.sqrt((a*a*T1+(b*b*T2-a*a*T1)*(tau-T1)/(T2-T1))/tau)
            return a+(b-a)*(tau-T1)/(T2-T1)
    return None
def load(fx,days,series):
    cat={c['symbol']:c for c in json.load(open(fx+'/contracts/DERIBIT/BTC_USDC.options.json'))['contracts']}
    snaps={}
    for d in days:
        for l in gzip.open(f'{fx}/chains/DERIBIT/BTC_USDC/{series}/{d}.csv.gz','rt').read().splitlines()[1:]:
            r=l.split(','); snaps.setdefault(int(r[0]),[]).append(r)
    return cat,snaps
if __name__=='__main__':
    cat,snaps=load('src/test/resources/options/btc-usdc-book-20261001',['2026-10-01'],'book')
    t,rows=next(iter(snaps.items())); pts=metrics(t,rows,cat)
    for d in [1,7,30,90]: print('book atm_iv.%dd'%d, tenor(pts,d,1), 'skew_25d.%dd'%d, tenor(pts,d,2))
    cat,snaps=load('src/test/resources/options/btc-usdc-trade-25sep26',['2026-09-25','2026-09-26'],'trade')
    vals=[(t,tenor(metrics(t,rows,cat),1,1)) for t,rows in sorted(snaps.items())]
    d=[(t,v) for t,v in vals if v is not None]
    print('trade atm_iv.1d defined',len(d),'of',len(vals)); print([(t,round(v,4)) for t,v in d])
```

Output: `atm_iv.1d` 30.58214541001407, `atm_iv.7d` 30.891697558131362, `atm_iv.30d` 33.820839841412294,
`skew_25d.7d` 0.662717312518918, `skew_25d.30d` 1.068997868586103; `skew_25d.1d` and 90-day and longer
tenors have no value.

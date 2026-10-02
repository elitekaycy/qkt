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

An independent Python implementation of the phase 43.4 rules (no qkt code):

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

Output: the metric is defined at 9 of the 48 hourly instants (the others lack quotes on both sides of
the forward within 10%, or fresh quotes at all). The first value above 33.5 is 33.5953 at
1790326800000 (2026-09-25T09:00:00Z).

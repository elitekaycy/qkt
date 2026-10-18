# External references

qkt backtests of strategies that backtesting.py 0.6.6 and backtrader 1.9.78 have already run, compared with
those engines' own outputs: every trade or fill on the same date, side, size and price, and the final value to
the cent (29 cases: backtesting.py SmaCross on GOOG with fixed and equity sizing, with and without commission;
backtrader's Quickstart SMA for all 21 published periods, its 3-lower-closes sample with and without
commission, and its bracket sample on its own data and on GOOG).

```bash
./gradlew installDist
python3 -I tests/references/run.py --refs ../qkt-data-corpus/references   # about 30 s
python3 -I tests/references/run.py --case d_bracket_goog --keep
```

The data (prices, strategies, configs and the other engines' outputs) is `references/` in the private
qkt-data-corpus repository. Each run works on a private copy of it, with a private home and config folder.

Every expectation is written in `CASES` in `run.py`. Where the engines legitimately differ, the case states
the convention and pins the exact count it explains, so a qkt change that moves even one trade fails:

- `a_equity`, `a_equity_comm`: all 93 trades on backtesting.py's dates and prices; 48 and 49 of its size,
  because qkt sizes at the signal-bar close after the exit, backtesting.py at the fill bar's open.
- `d_bracket_goog`: 33 of 48 round trips identical; the other 15 exit on the entry bar, where backtrader
  defers bracket children to the next bar.

A new difference is never absorbed by changing a count without first finding its cause and writing it into
the case's `why`.

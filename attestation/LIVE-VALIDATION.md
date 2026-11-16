# Live validation: the derivatives lane in parallel groups

Offline tests prove the plan, the locks, the gateway assignment and the catalog's scheduling. They cannot prove that
Deribit testnet, three gateway processes and a dozen daemons on one account behave. Run this once, after the release
that is running now has finished and nothing else is trading the account. Delete this file with the PR that confirms it.
(The pool and the lane below touch the Deribit testnet account `g-aVrEeP` and nothing else; the Bybit and cross lanes are
not part of it, except as stated.)

## What changed

- Cases moved to ETH_USDC so groups can overlap: `continuous-roll-refused-leg-resumes` and `-restored-moves-collar` (ETH
  `@front`), `perpetual-drill-kill-switch` (ETH_USDC_PERPETUAL). Every assertion is unchanged. All other cases stay on BTC.
- Each ready case has a `group:`. Six groups run side by side, at most `--deriv-parallel` (default 4) at once.
- A `kill_switch` case runs on a gateway process of its own: `deribit-gateway-pool.sh up` starts two more
  (`qkt-lanes-gw-deribit-drill1/2` on :18454/:18455, same image, key and tokens as the lane gateway on :18444).

| group | gateway | cases (budget s) |
|---|---|---|
| `btc-perp` | :18444 | perpetual-bracket-timed-exit, -drill-flat-restart, -drill-gateway-outage, -drill-qkt-restart (600 each) |
| `btc-front` | :18444 | continuous-roll-carries, -far-book-stops (600 each) |
| `eth-front` | :18454 for -refused-leg-resumes, else :18444 | continuous-roll-refused-leg-resumes, -restored-moves-collar (600 each) |
| `btc-dated` | :18444 | dated-future-bracket-timed-exit, dated-future-margin-floor-refuses-entry (600 each) |
| `eth-perp` | :18455 | perpetual-drill-kill-switch (600) |
| `btc-options` | :18444 | option-put-spread-open-close (420) |

## 1. Before (read only)

```bash
cd /var/tmp/qkt-work/qkt-deribit-parallel            # or the checkout this PR was merged into
python3 attestation/lib/validate.py                  # ok, no ERROR lines
python3 -m unittest discover -s scripts/live-validation/tests   # OK
python3 scripts/live-validation/lib/deriv_parallel.py plan --cases attestation/cases/derivatives \
    --base-url http://127.0.0.1:18444 --drill-urls http://127.0.0.1:18454,http://127.0.0.1:18455
# 12 lines, group<TAB>case<TAB>gateway: the two kill_switch cases on :18454 / :18455, everything else on :18444
```

Public testnet books, GET only, as the PR probed them (BTC and ETH futures: two-sided book, 1 m bars in the last 6 h):

```bash
curl -s 'https://test.deribit.com/api/v2/public/get_order_book?instrument_name=ETH_USDC-PERPETUAL&depth=5'
curl -s 'https://test.deribit.com/api/v2/public/get_order_book?instrument_name=ETH_USDC-7NOV26&depth=5'   # use a listed daily
```

## 2. Start the drill gateways and run the lane

```bash
scripts/live-validation/deribit-gateway-pool.sh up        # started + healthy for drill1 and drill2
scripts/live-validation/deribit-gateway-pool.sh status    # login=g-aVrEeP link=True kill={'all': False, ...} on both
```

Run the derivatives lane alone, through the release wrapper's profile (`deriv_drill_gateway_urls="$(...urls...)"`,
`deriv_parallel=4`) or directly (the tokens from the environment, never printed; the MT5 flags are required by the script and
unused by this lane):

```bash
export QKT_DERIV_GATEWAY_KEY="$(cat ~/qkt-testnet-soak/state/trader.token)" \
       QKT_DERIV_GUARDIAN_KEY="$(cat ~/qkt-testnet-soak/state/guardian.token)" \
       QKT_BROKER_API_KEY=unused QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY
out=/var/tmp/qkt-validation/deriv-parallel-$(date +%s)
bash scripts/live-validation/run-attestation-catalog.sh --out "$out" --lanes derivatives \
    --gateway-url "$GATEWAY_URL" --expected-login "$EXPECTED_LOGIN" --expected-server "$EXPECTED_SERVER" --magic-base 9000 \
    --arm I_UNDERSTAND_DEMO_ORDER_0.01 --cli build/install/qkt/bin/qkt \
    --deriv-gateway-url http://127.0.0.1:18444 --deriv-expected-login g-aVrEeP \
    $(scripts/live-validation/deribit-gateway-pool.sh urls) --deriv-parallel 4 2>&1 | tee "$out.log"
```

Expect first `derivatives: 6 groups, at most 4 at once: btc-perp btc-front eth-front btc-dated eth-perp btc-options`.

## 3. Compare with the serial run

The serial run: `/var/tmp/qkt-validation/attest-9cbac830-catalog/attempts/*.jsonl` (one line per attempt; wall time is
`finishedAtUtc - startedAtUtc`). Passing serial attempts: perpetual-bracket-timed-exit 285 s, -drill-flat-restart 360 s,
-drill-gateway-outage 480 s, -drill-kill-switch 481 s, -drill-qkt-restart 479 s. The BTC roll, dated and option cases
there failed on the venue (locked books, 0 fills; 520-870 s per attempt), so they have no passing time: expect 600-840 s
for a roll, 400-700 s for a dated case, about 500 s for the put spread.

| quantity | expected | where |
|---|---|---|
| verdicts | all 12 `derivatives-*` passed, `status: passed` | `$out/result.json` |
| lane wall clock | 1900-2300 s (about 32-37 min) at 4; ~1800 s at `--deriv-parallel 5` | `wallClockSeconds`; the serial lane's minimum is about 6400 s |
| group overlap | at the start, 4 `run-derivatives-lane-case.py` processes at once | `$out/logs/derivatives-*.log` start times, `ps` |
| assertions | every case's `result.json` has `problems: []`, the same `fills` count as its `case.yaml`, `netGap` within `1e-8 x fills` | `$out/derivatives-*/result.json` |
| ETH cases | `rollLegs`/fills name `ETH_USDC-<daily>`; the kill-switch drill's timeline shows 423 `kill_switch` on its own gateway | the two ETH cases' `result.json` (`drills`) |
| gateways | after the run, kill switch off on all three | `deribit-gateway-pool.sh status`; `curl :18444/v1/health` |

## 4. Interference checks (the point of the exercise)

```bash
grep -l 'too_many_requests\|"code":10028\|error 10028' "$out"/derivatives-*/daemon.log "$out"/logs/*.log   # none
docker logs qkt-lanes-gw-deribit 2>&1 | grep -ci 'too_many_requests\|10028'                              # 0 (also drill1, drill2)
grep -h 'claim .*waiting\|claim .*acquired' "$out"/logs/*.log                                           # none, or only a case waiting on its own group-mate
grep -h 'moved without a fill here' "$out"/derivatives-*/daemon.log                                      # none: no daemon saw another move its code
jq -r '.balanceNote' "$out"/derivatives-*/result.json | sort | uniq -c                                  # null for every case (no ready case asserts the balance)
jq -r '.funding[]?.symbol' "$out"/derivatives-*/result.json | sort | uniq -c                            # each case lists funding only on its own instrument
jq -r '.leftoversSwept // empty' "$out"/derivatives-*/result.json                                       # empty on a clean account
```

- **Rate limits.** The lane's request volume is small: each runner polls about 1 request a second only after its fills,
  each gateway reconciles the venue once a minute, and each case places 3 to 6 orders. At 4 groups that is well under
  1 request a second against Deribit's non-matching limit (an assumption from its docs: 50 000 credits, 500 a request,
  10 000 a second to refill), and the one burst is the entries on a shared bar close: up to 4 x (entry + 2 bracket legs)
  inside a second or two against the matching-engine limit. If a `10028` shows, rerun with `--deriv-parallel 2` and say so.
- **Margin floor.** `dated-future-margin-floor-refuses-entry` must still show `risk rejected atto_margin_floor ... below floor`
  in its `daemon.log` and its second fill the risk-reducing close: its first floor is 0 (a level is never below it) and the
  restart's is 1e15 (more margin used by the other groups only lowers the level further).
- **Foreign deals.** Another group's deals are foreign to each case. `venue-fills-equal-qkt-fills`, `deals-net-equals-realized`
  and `flat-by-strategy` judge only the case's own client order ids, so they must pass unchanged; none of the ready cases
  asserts `account-balance-equals-realized`, which would read "not comparable" beside other groups.
- **Roll pair guard.** A roll case that fails with `venue-untradeable: ... reaches 6+ days out` found the venue pricing too
  few dailies; it is the guard keeping a roll off a dated case's contract, not a regression. Rerun later.
- **Cross lane.** With `--lanes derivatives,cross`, `cross-same-symbol-two-venues` (BTC_USDC_PERPETUAL on Deribit) must
  not overlap a `btc-perp` case: `grep 'claim DERIBIT:BTC_USDC:perpetual' "$out"/logs/*.log`.

## 5. Clean up and fall back

```bash
scripts/live-validation/deribit-gateway-pool.sh down      # removes drill1 and drill2 and their volumes
```

Without `--deriv-drill-gateway-url` the lane runs one case at a time exactly as before (the ETH moves still apply). To
isolate a parallel-only problem, rerun with `--deriv-parallel 1`: the groups then run one after another on the same
gateways.

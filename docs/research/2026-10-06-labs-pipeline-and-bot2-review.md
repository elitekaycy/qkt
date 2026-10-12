# Labs: the research-to-storefront process, and whether bot2 carries it

Written 2026-10-06 after deploying labs.qkt to bot2 and running it as a user. Measured, not assumed; where
something is inferred it says so.

## 1. The process you described

```
research lab ──(you)──> admin upload ──> runner on bot2 ──> Supabase ──> storefront ──> buyer
  (python)              (.qkt file)      (qkt, 10 stages)   (listing)     (public)
```

### What is good about it
- **The evidence is generated, not claimed.** A listing exists only if every gate passes on real ticks, at
  three account sizes, out of sample, walk-forward and under 2x costs. That is the product's moat.
- **Buyers never touch bot2.** The storefront reads only Supabase. Buyer traffic and pipeline load are
  independent: a thousand buyers cost bot2 nothing; only *publishing* costs bot2 CPU.
- **Everything is auditable.** Every artifact carries a sha256 and qkt's own manifest; every version records
  the engine version, cost profile hash and gates version it ran under.
- **Data safety design is sound.** Images hold no data, deploys never remove volumes, snapshots precede every
  deploy, sales are recorded per order and entitlement.

### What is not good, or not yet true
1. **The handoff from your local research is manual.** Nothing connects the local research lab to Labs: you
   export a `.qkt` file and upload it in the admin. That works, because Labs sells `.qkt` strategies. (The
   lab's ML models are out of scope for now, so the earlier concern that they are not `.qkt` files does not
   apply; revisit it if model-backed strategies ever become a product.)
2. **The handoff is manual and loses provenance.** Upload takes a hand-typed trial count. The deflated Sharpe
   is only as honest as that number. The lab already keeps a registry of trials; the bridge should read the
   count from it, not from a form.
3. **Two standards of proof.** The lab has a lockbox, CPCV and pre-registered halts; Labs gates are looser (a
   50% drawdown ceiling, 100 trades, 30 out-of-sample). A strategy that fails the lab's bar can still pass
   Labs's. If the brand is rigor, the lockbox result should be a Labs gate, or at least shown.
4. **Selling is outcome-blind to regime.** The windows are chosen by the uploader (two years or more). A
   listing should say which window and why; the page shows it, but nothing stops cherry-picked windows.
   Consider fixing the window per asset class in the gates.
5. **No post-sale feedback loop.** Buyers run it live; nothing compares live results with the listing's
   evidence. The insights stack could publish aggregate live-vs-backtest drift per listing. That is the
   strongest trust feature available, and it already exists in your tooling.

### Verdict on the process
Good in principle, with one structural fix needed before it is "research -> admin -> storefront" end to end:
**define the artifact the research hands over** (a `.qkt` file plus its trial count and the research's own
out-of-sample result) and automate it as a `labs push` call from the promote step. Until then it is a manual
upload, which is fine for a small catalogue.

## 2. bot2, measured

| Resource | Measured 2026-10-06 | Note |
|---|---|---|
| CPU | 12 vCPU; 4 runners busy -> ~50% idle | load average reads 11 because qkt's JVM threads count as runnable |
| Memory | 47 GB; 20 GB free; each runner 3.5 GB of its 5 GB cap | |
| Disk | 242 GB; 84 GB free (66% used); runner-state 181 MB for 7 versions (~25 MB each) | disk is not the constraint |
| Live stack beside it | mt5-gateway 130-230% CPU, forward qkt 30-150% | real-money trading shares this host |
| Tick archive | 97 GB read-only, page cache 22 GB | shared by all runners |
| Uptime | 112 days | one machine, one disk |

### Throughput
- v3 took 100 minutes end to end on four runners (12:05 -> 13:45). v4, the re-run on the new image and paths, took 114 minutes (09:53 -> 11:47Z) while sharing the host with live trading and my own tests.
- **Determinism proved:** v4 reproduced v3 exactly (price $389, deflated-Sharpe probability identical to 15 digits, 9 of 9 metric rows identical, 19 of 19 gates), so moving qkt into the image changed nothing about the evidence.
- Each runner uses ~1.1 cores. Four runners plus the live stack leave roughly 4-5 cores spare.
- Inference (not measured): about two strategies in flight at once before the live stack feels it, i.e.
  **6-10 new versions a day** is a safe planning number; the theoretical maximum is higher but eats the
  headroom that live trading needs. A startup publishing a few strategies a week is far below this.

### Does it scale "easily"?
- **Up: yes.** Raise `LABS_RUNNERS`, or move to a bigger host; the compose and the deploy script already take it
  as a setting.
- **Out: not without a change.** The queue is a SQLite file next to the runners, and the runner and control
  must stay together. A second pipeline host needs the queue moved to Postgres and a mirror of the 97 GB tick
  archive. That is a project, not a setting.
- **The storefront should not live on bot2 at all.** It shares a host with live prop-trading, so a public
  site's traffic, an attack, or a bad deploy can touch money. The compose files already split: run the web
  container on a small separate VPS (it needs only Supabase and the payment provider), keep pipeline + admin
  on bot2 behind the tailnet.

## 3. State of the system (what I changed and tested)
- Production deploys from `/srv/labs.qkt` with one env file; `deploy/deploy.sh` is the only update path (lock,
  idle-queue check, disk check, 2 snapshots, up -d without removing volumes, health check, rollback,
  `--only web` for site fixes that must not stop research).
- `dev` is the default branch; `main` receives release PRs; CI builds and smoke-tests the images and now
  guards the runtime Supabase settings (a missing one meant nobody could sign in).
- Tested as a user on bot2: browse, sign in, terms gate, checkout, sandbox payment, return, library,
  ownership, download (stamped to the buyer), admin sign-in and live run. Webhook endpoint verified (bad or
  stale signatures 401, correct 200); no webhook can arrive yet (see below).

## 4. Not ready for consumers, and why
| Item | Why it blocks | Who |
|---|---|---|
| Public domain, TLS, storefront off bot2 | site is tailnet-only; webhooks cannot arrive | you (domain) + me |
| Live payment keys and a live webhook | sandbox only; the registered webhook points at a laptop | you |
| Production Supabase project, Google client | dev project in use; its credentials were printed once in a session | you |
| ~~Content-Security-Policy~~ | done 2026-10-06: per-request nonce policy, verified in a browser on 15 page loads, no violations | done |
| Backups | none beyond 2 pre-deploy snapshots; user data lives only in Supabase | you (decision) |
| Legal text | drafted, not reviewed; operator assumed from your name/country | you + a lawyer eventually |
| GitHub controls | no enforced branch protection on the free plan; deploy workflow inert | you (plan, secrets) |
| Research -> Labs bridge | manual upload (section 1) | design decision |
| Live-vs-backtest drift per listing | the strongest trust feature; not built | me |

## 5. Recommendation
Launch order I would follow: (1) move the storefront to its own small VPS on a real domain with Caddy;
(2) production Supabase + live Bachs + Google client; (3) CSP and the legal review; (4) list the first
strategies that are real `.qkt` strategies; (5) build the lab bridge and the live-drift page. bot2 stays the
pipeline: it is good at that, with room for the next several months of a small catalogue.

## 6. Beta (as of 2026-10-06)
The storefront is public at the Tailscale Funnel address (sandbox payments only; no real money moves). It stays
up as long as bot2's Tailscale node is up (key expires 2027-02-17 unless expiry is disabled). Before testers
can sign in, Supabase needs the site URL and redirect URLs set (sign-in currently falls back to localhost),
and Google's consent screen must list testers or be published. The Bachs sandbox webhook must be re-pointed at
the Funnel address. The operator is shown only as "qkt Labs" with a support email; payment-provider and
consumer-law obligations to identify the seller may still apply and are for a lawyer to confirm.

## 7. What the real user-flow test found (and fixed)
Two production bugs that no unit test or CI job caught, both found only by driving the deployed site as a user:
1. The web container never received its Supabase settings at runtime (only as build args), so **no one could
   sign in**. Fixed, and CI now asserts the runtime variables exist.
2. After a Google sign-in the app sets large session cookies; nginx's default header buffers were smaller than
   that response, so it answered **502 and the session was never set**. Reproduced with a stand-in upstream
   (old config 502, new 307), fixed, and CI guards the setting.
Also fixed: the idle-queue check missed scaled runner names; the heartbeat file could be read half-written
(a flaky test and a possible false "runner not alive"). Lesson: a signed-in, paying, multi-page test against
the deployed container belongs in CI; today it lives in the scratchpad.

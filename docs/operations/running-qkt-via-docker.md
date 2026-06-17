# Using qkt with Docker

Say you want to try qkt — write a strategy, backtest it, see how it behaves — but you'd rather
not install Java and a build toolchain on your computer first. Docker lets you do all of it
inside a self-contained box. Nothing to set up, nothing left behind on your machine, and you can
throw the box away whenever you like.

There are two ready-made boxes to choose from:

- **The workbench (`qkt:dev`)** — comes with a text editor and a shell, so you can write, edit,
  and run strategies all in one place. This is the one you want for trying things out.
- **The engine (`qkt:latest`)** — the lean image that actually runs strategies live. It has no
  editor on purpose, so the live trading box stays small and locked down. You'll use this one
  later, when you go to production.

This page is about the workbench. (For the live engine, see the deployment guide.)

---

## Set up your workbench

First, pick a folder on your computer to keep your strategies in. Everything you write will live
here, safe and sound, even after you close or delete the container:

```bash
mkdir -p ~/qkt-lab
```

Now start the workbench. This launches it in the background and connects your folder to it, so
the two share the same files:

```bash
docker run -dit --name qkt-dev -v ~/qkt-lab:/work ghcr.io/elitekaycy/qkt:dev
```

Step inside whenever you want to work:

```bash
docker exec -it qkt-dev bash
```

You're now in the workbench. `qkt` and `nano` are here, and `vim`/`vi` open Neovim with the qkt
language server already wired up — so editing a `.qkt` file gives you live error squiggles,
as-you-type completion, hover help, and snippet templates, not just highlighting. The `/work`
folder is your `~/qkt-lab` from the outside. A typical first session looks like this:

```bash
qkt --help                                        # see what qkt can do
qkt create template mystrat.qkt --kind minimal    # start from a ready-made example
vim mystrat.qkt                                    # edit it (live errors + completion for .qkt)
qkt parse mystrat.qkt                              # check it's valid
qkt backtest mystrat.qkt --from 2024-03-01 --to 2024-03-05
```

That's the whole loop: scaffold, edit, check, backtest — repeat.

Inside the editor, you don't have to remember the syntax. Type a short trigger and press Enter to
drop in a template, then Tab through the blanks: `strategy` (a skeleton), `stratfull` (a full one
with params and rules), `strat-ema` (a complete, runnable EMA crossover to edit), or smaller
pieces like `rule`, `buy`, and `cross`. The same templates show up in VS Code if you install the
qkt extension.

---

## Starting and stopping

The workbench keeps running in the background until you tell it otherwise. You don't have to set
it up again each time — just step back in with `docker exec` whenever you want to work.

```bash
docker stop qkt-dev      # take a break (everything stays as you left it)
docker start qkt-dev     # come back to it
docker rm -f qkt-dev     # throw the box away entirely
```

Throwing the box away is safe: your strategies live in `~/qkt-lab` on your computer, so they're
never lost. If you ever rebuild the workbench, your work is still right there. You can even open
that same folder in your own editor at home (for example VS Code) — it's the same files either
way.

---

## A quicker way for one-off commands

If you just want to run a single qkt command now and then — without keeping a workbench around —
you can teach your terminal to treat `qkt` like a normal command that happens to run in Docker.
Add this line to your shell (it runs qkt against whatever folder you're currently in):

```bash
alias qkt='docker run --rm -it --entrypoint qkt \
  -u "$(id -u):$(id -g)" -v "$PWD:/work" -w /work \
  ghcr.io/elitekaycy/qkt:latest'
```

Now `qkt backtest mystrat.qkt --from ... --to ...` works from any folder. Each command spins up a
fresh box, does the one job, and cleans up after itself. Handy for quick checks; the workbench
above is nicer when you're in the middle of writing something.

---

## Where your data lives

Backtests need price history to run against. To make sure that history is saved between runs (so
you're not re-downloading it every time), keep it inside your `/work` folder:

- `qkt backtest` already saves it to `/work/data` by default — nothing to do.
- `qkt fetch` saves elsewhere unless you tell it otherwise, so point it at the same place:
  ```bash
  qkt fetch BYBIT_SPOT:BTCUSDT --tf 5m --last 30d --data-root /work/data
  ```

Crypto history (the `BYBIT_*` symbols) downloads on its own with no extra setup. Gold and forex
(like `EXNESS:XAUUSD`) currently need a connection to a broker; once seamless auto-download lands,
those will fetch themselves too, and a backtest will need nothing but the command.

---

## Keeping it up to date

The `:dev` and `:latest` names always point at the newest build, but Docker keeps a local copy
and won't refresh it on its own. Grab the latest whenever you want:

```bash
docker pull ghcr.io/elitekaycy/qkt:dev
```

If you'd rather stay on one exact version (so results don't shift under you), use a version name
instead, like `ghcr.io/elitekaycy/qkt:v0.34.0`.

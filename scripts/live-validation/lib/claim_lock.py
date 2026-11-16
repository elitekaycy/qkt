#!/usr/bin/env python3
"""Runs a command while holding one lock per instrument claim, so two cases of one venue account never trade the same
instrument at once, whatever the grouping says (lib/deriv_parallel.py).

    claim_lock.py hold --dir DIR [--claim DERIBIT:BTC_USDC:perpetual ...] -- COMMAND ARG...

Locks are `flock`s on files in DIR, taken in sorted order (no two holders can wait on each other), released when the
command ends or this process dies. The wait, if any, is printed to stderr with who held it. The command's exit code is
this one's; SIGTERM and SIGINT go to the command first. No claim: the command runs at once.
"""
import argparse, fcntl, os, re, signal, subprocess, sys, time

POLL_S = 1.0


def lock_path(directory, claim):
    return os.path.join(directory, re.sub(r"[^A-Za-z0-9_.-]", "_", claim) + ".lock")


def acquire(directory, claims, poll_s=POLL_S, say=lambda text: print(text, file=sys.stderr, flush=True), holder=""):
    """Opens and exclusively locks each claim's file, in sorted order; returns the open files (closing releases)."""
    os.makedirs(directory, exist_ok=True)
    held = []
    for claim in sorted(set(claims)):
        stream = open(lock_path(directory, claim), "a+")
        began, noted = time.time(), False
        while True:
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if not noted:
                    stream.seek(0)
                    say(f"claim {claim}: held by {stream.read().strip() or 'another case'}; waiting")
                    noted = True
                time.sleep(poll_s)
        if noted:
            say(f"claim {claim}: acquired after {time.time() - began:.0f}s")
        stream.seek(0)
        stream.truncate()
        stream.write(f"pid {os.getpid()} {holder}".strip())
        stream.flush()
        held.append(stream)
    return held


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if "--" not in argv:
        sys.exit("claim_lock: usage: claim_lock.py hold --dir DIR [--claim X ...] -- COMMAND ARG...")
    split = argv.index("--")
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["hold"])
    ap.add_argument("--dir", required=True)
    ap.add_argument("--claim", action="append", default=[])
    a = ap.parse_args(argv[:split])
    command = argv[split + 1:]
    if not command:
        sys.exit("claim_lock: no command after --")
    held = acquire(a.dir, a.claim, holder=" ".join(os.path.basename(w) for w in command[:2]))
    child = subprocess.Popen(command)
    for number in (signal.SIGTERM, signal.SIGINT):
        signal.signal(number, lambda n, _: child.send_signal(n))
    code = child.wait()
    for stream in held:
        stream.close()
    return code


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env bash
# The release driver must refuse, before touching GitHub or the gateway, when it is not given
# what an unattended release needs.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/scripts/release-unattended.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

expect_refusal() {  # name expected-text command...
    local name="$1" text="$2"; shift 2
    if out="$("$@" 2>&1)"; then echo "FAIL $name: exited 0"; exit 1; fi
    grep -qF -- "$text" <<<"$out" || { echo "FAIL $name: missing '$text' in: $out"; exit 1; }
    echo "ok $name"
}

touch "$tmp/profile"; printf 'k' > "$tmp/key"; chmod 644 "$tmp/key"
expect_refusal "no arguments" "Usage: release-unattended.sh" bash "$script"
expect_refusal "unknown flag" "unknown argument: --nope" bash "$script" --nope
expect_refusal "missing profile" "profile not found" bash "$script" --profile "$tmp/none" --key-file "$tmp/key"
expect_refusal "missing key file" "key file not found" bash "$script" --profile "$tmp/profile" --key-file "$tmp/none"
expect_refusal "readable key file" "key file must be mode 0600" bash "$script" --profile "$tmp/profile" --key-file "$tmp/key"

# When the wait for testing runs out, a release needs every testing run green, not only docker: a
# check or integration still running must stop it (2026-10-19: the loop ended with check in progress).
git init -q --bare "$tmp/origin.git"
git -c init.defaultBranch=testing init -q "$tmp/work"
( cd "$tmp/work" && echo 0.0.1 > VERSION && git add VERSION && git -c user.name=t -c user.email=t@t commit -qm init &&
    git remote add origin "$tmp/origin.git" && git push -q origin HEAD:testing )
mkdir -p "$tmp/bin"
printf '#!/usr/bin/env bash\ncase "$1 $2" in "run list") echo "check:in_progress:";'\
' echo "integration:in_progress:"; echo "docker:completed:success" ;; esac\n' > "$tmp/bin/gh"
printf '#!/usr/bin/env bash\n' > "$tmp/bin/sleep"
chmod +x "$tmp/bin/gh" "$tmp/bin/sleep"; chmod 600 "$tmp/key"
expect_refusal "testing CI still running" "testing CI did not finish" \
    env PATH="$tmp/bin:$PATH" QKT_RELEASE_REPO_ROOT="$tmp/work" QKT_RELEASE_TESTING_POLLS=2 \
    bash "$script" --profile "$tmp/profile" --key-file "$tmp/key" --repo o/r

#!/usr/bin/env python3
"""What a commit ships, and what an attestation case is defined by, as hashes computed from git objects alone.

The runtime fingerprint of a commit is the hash of every tracked file except an explicit deny-list of paths known not to
affect what `installDist` and the Docker image build produce. Docs, tests, CI and the attestation harness are on that
list, so a commit that touches only those has the same fingerprint as its parent: the same shipped artifact. A path not on
the list is in the fingerprint. Files are identified by git's own blob ids, so the
hash is the same from a clean checkout, from a bare clone and from the verifier, and needs no working tree.

    runtime_fingerprint.py fingerprint --repo D --sha S [--files]    prints the hash (or the file list); exit 1 if it cannot be computed
    runtime_fingerprint.py definition  --repo D --sha S --case NAME  prints the case definition hash; exit 1 if it cannot be computed

Anything that makes the answer uncertain (not a git repo, an unknown commit, no Kotlin sources) yields None / exit 1,
and every caller then falls back to binding evidence to the exact commit.
"""
import argparse, hashlib, json, os, re, subprocess, sys, zipfile

FINGERPRINT_SCHEMA = "qkt-runtime-fingerprint-v1"
DEFINITION_SCHEMA = "qkt-case-definition-v1"

# A DENY-list: every tracked file is in the fingerprint EXCEPT the paths below, each known not to affect what is built
# or shipped. A path nobody has classified (a new top-level directory, a new script, a new config file) is therefore
# in the fingerprint and invalidates carried evidence; it is carried only once someone adds it here, in review.
# attestation/ is out because each case's own files are covered per case (definition_sha), the rest of it is harness.
NOT_SHIPPED_PREFIXES = (
    "src/test/",                # tests
    "tests/",                   # script tests, the data corpus lock, black-box suites
    "docs/", "overrides/", "pygments_qkt/",  # the documentation site and its theme
    ".github/", ".githooks/", ".claude/",    # CI and repo tooling
    "config/",                  # coverage and file-size gates: they gate `check`, they do not shape installDist
    "data/",                    # sample data, not read by the build or copied into the image or the distribution
    "strategies/",              # example strategies
    "examples/",                # all but examples/bot/, which the distribution bundles (see NOT_SHIPPED_EXCEPT)
    "attestation/",             # cases (per case, below), lanes and the validator
    "scripts/live-validation/", "scripts/release-proof/", "scripts/equities/",  # the attestation and proof harness
)
NOT_SHIPPED_EXCEPT = ("examples/bot/",)
NOT_SHIPPED_FILES = (
    "README.md", "QUICKSTART.md", "CONTRIBUTING.md", "CODE_OF_CONDUCT.md", "SECURITY.md", "LICENSE", "AGENTS.md",
    "mkdocs.yml", "requirements-docs.txt", ".gitignore", ".editorconfig", ".env.example", ".mise.toml",
    "qkt.config.yaml.example", "docker-compose.yml",
    # release and promotion tooling: runs from the repo, never inside the artifact
    "scripts/agent-workflow.sh", "scripts/audit_qkt_report_bundle.py", "scripts/check-commit-authors.sh",
    "scripts/check-diff-coverage.sh", "scripts/deadman-watchdog.sh", "scripts/fetch-dukascopy.sh",
    "scripts/install-hooks.sh", "scripts/precheck.sh", "scripts/prepare-main-promotion.sh",
    "scripts/promote-dev-to-testing.sh", "scripts/release-unattended.sh", "scripts/pin-release-branch.sh",
    "scripts/verify-paper-soak-attestation.py", "scripts/verify-version-tag.sh",
    # the installers users download from the repo are not what the attestation exercises
    "scripts/install.sh", "scripts/install.ps1",
)
# Not shipped, but part of what a case runs: the harness that drives every case and the proofs. Carried into each
# case's definition hash, so evidence made by an older harness is never carried to a newer one.
HARNESS_PREFIXES = ("scripts/live-validation/", "scripts/release-proof/", "attestation/lib/")
HARNESS_EXCLUDED = ("scripts/live-validation/tests/",)

BINDING_V2 = "qkt-attestation-binding-v2"
_trees = {}


# The only content of the installed tree that is the commit and the clock rather than the build: gradle's
# generateBuildInfo writes version, gitSha and buildTimestamp here (build-logic/.../qkt.build-info.gradle.kts).
BUILD_INFO = "build-info.properties"


def artifact_hash(cli_root):
    """Content hash of the installed CLI: every file by relative path and bytes, every jar by its entries' names and
    bytes (never their timestamps or order), minus build-info.properties. Two builds of one source tree that differ only in
    time or commit hash equal. "" when the tree has no jar (nothing to say) or cannot be read."""
    h, jars = hashlib.sha256(b"qkt-artifact-v1\n"), 0
    try:
        for folder, dirs, names in os.walk(cli_root):
            dirs.sort()
            for name in sorted(names):
                path = os.path.join(folder, name)
                h.update(os.path.relpath(path, cli_root).encode() + b"\0")
                if name.endswith(".jar"):
                    jars += 1
                    with zipfile.ZipFile(path) as jar:
                        for info in sorted(jar.infolist(), key=lambda i: i.filename):
                            if os.path.basename(info.filename) != BUILD_INFO:
                                h.update(info.filename.encode() + b"\0" + hashlib.sha256(jar.read(info)).digest())
                else:
                    with open(path, "rb") as stream:
                        h.update(hashlib.sha256(stream.read()).digest())
    except (OSError, zipfile.BadZipFile):
        return ""
    return h.hexdigest() if jars else ""


def environment(gateway_images):
    """{java, gatewayImages: {ref: image id}} as JSON-able data, or None when any part cannot be read. Evidence made on a
    different JDK or against a different gateway image is never carried, and an unreadable part is not "the same"."""
    try:
        java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=True)
        version = (java.stderr or java.stdout).strip().splitlines()[0]
        images = {}
        for ref in gateway_images.split():
            images[ref] = subprocess.run(["docker", "image", "inspect", "--format", "{{.Id}}", ref], capture_output=True,
                                         text=True, check=True).stdout.strip()
    except (OSError, IndexError, subprocess.CalledProcessError):
        return None
    if not version or not images or not all(images.values()):
        return None  # no declared gateway identity: nothing to compare, so nothing to carry
    return {"java": version, "gatewayImages": images}


def carry_key(binding):
    """What two builds must share for one's passed case to stand for the other's: the shipped artifact (runtime
    fingerprint), the profile file, the account, and the environment (JDK, gateway images). Not the commit and not the
    CLI's bytes: the CLI embeds the commit and a build timestamp, so no two builds of one tree are byte-equal."""
    return {k: binding.get(k) for k in ("schema", "runtimeFingerprint", "artifactHash", "profileSha256", "account", "environment")}


def evidence_key_sha(binding):
    """The hash every case record carries. A v2 binding hashes its carry key; any other binding (the exact-commit
    binding, which names the commit and the CLI's bytes) hashes whole, as it always did."""
    doc = carry_key(binding) if binding.get("schema") == BINDING_V2 else binding
    return hashlib.sha256(json.dumps(doc, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def tree(repo, sha):
    """{path: (mode, blob id)} for a commit, or None when git cannot say."""
    key = (repo, sha)
    if key not in _trees:
        try:
            out = subprocess.run(["git", "-C", repo, "ls-tree", "-r", "-z", f"{sha}^{{commit}}"], check=True,
                                 capture_output=True).stdout
        except (OSError, subprocess.CalledProcessError):
            _trees[key] = None
        else:
            found = {}
            for entry in out.split(b"\0"):
                if not entry:
                    continue
                meta, _, path = entry.partition(b"\t")
                mode, _, blob = meta.decode().split(" ")
                found[path.decode("utf-8", "surrogateescape")] = (mode, blob)
            _trees[key] = found
    return _trees[key]


def digest(schema, items):
    h = hashlib.sha256(schema.encode() + b"\n")
    for path, (mode, blob) in sorted(items.items()):
        h.update(f"{mode} {blob} {path}\0".encode("utf-8", "surrogateescape"))
    return h.hexdigest()


def not_shipped(path):
    return path in NOT_SHIPPED_FILES or (path.startswith(NOT_SHIPPED_PREFIXES) and not path.startswith(NOT_SHIPPED_EXCEPT))


def runtime_files(repo, sha):
    files = tree(repo, sha)
    if files is None:
        return None
    chosen = {p: v for p, v in files.items() if not not_shipped(p)}
    # A tree with no Kotlin sources or no build file is not a qkt tree: refuse to name a fingerprint for it.
    if not any(p.startswith("src/main/") for p in chosen) or "build.gradle.kts" not in chosen or "VERSION" not in chosen:
        return None
    return chosen


def runtime_fingerprint(repo, sha):
    chosen = runtime_files(repo, sha)
    return None if chosen is None else digest(FINGERPRINT_SCHEMA, chosen)


def lane_entry(repo, sha, lane):
    """The lane's block of attestation/lanes.yaml, verbatim: a change to its budget, concurrency or session is a change to every case in it."""
    try:
        text = subprocess.run(["git", "-C", repo, "show", f"{sha}:attestation/lanes.yaml"], check=True,
                              capture_output=True).stdout.decode("utf-8", "replace")
    except (OSError, subprocess.CalledProcessError):
        return None
    block, inside = [], False
    for line in text.splitlines():
        if re.match(r"^  [A-Za-z0-9_-]+:\s*$", line):
            inside = line.strip() == f"{lane}:"
        elif line and not line.startswith(" "):
            inside = False
        if inside:
            block.append(line.rstrip())
    return "\n".join(block) if block else None


def case_parts(name):
    lane, _, case_id = name.partition("-")
    return lane, case_id


def harness_files(files):
    return {p: v for p, v in files.items() if p.startswith(HARNESS_PREFIXES) and not p.startswith(HARNESS_EXCLUDED)}


def definition_sha(repo, sha, name):
    """The case's own definition: every file under attestation/cases/<lane>/<id>/, its lane's entry in lanes.yaml, and
    the harness that runs it. None when the commit has no such case."""
    files = tree(repo, sha)
    lane, case_id = case_parts(name)
    if files is None or not lane or not case_id:
        return None
    prefix = f"attestation/cases/{lane}/{case_id}/"
    own = {p: v for p, v in files.items() if p.startswith(prefix)}
    if not own:
        return None
    h = hashlib.sha256(DEFINITION_SCHEMA.encode() + b"\n")
    h.update(digest("case-files", own).encode())
    h.update(b"\nlane\0" + (lane_entry(repo, sha, lane) or "").encode())
    h.update(b"\nharness\0" + digest("harness", harness_files(files)).encode())
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)
    p = sub.add_parser("fingerprint")
    p.add_argument("--repo", required=True); p.add_argument("--sha", required=True); p.add_argument("--files", action="store_true")
    p = sub.add_parser("environment"); p.add_argument("--gateway-images", default="")
    p = sub.add_parser("artifact"); p.add_argument("--cli-root", required=True)
    p = sub.add_parser("definition")
    p.add_argument("--repo", required=True); p.add_argument("--sha", required=True); p.add_argument("--case", required=True)
    a = ap.parse_args()
    if a.command == "fingerprint":
        chosen = runtime_files(a.repo, a.sha)
        if chosen is None:
            print("cannot compute the runtime fingerprint", file=sys.stderr)
            return 1
        print("\n".join(sorted(chosen)) if a.files else digest(FINGERPRINT_SCHEMA, chosen))
        return 0
    if a.command == "environment":
        found = environment(a.gateway_images)
        if found is None:
            print("cannot read the environment identity (java, gateway_images)", file=sys.stderr)
            return 1
        print(json.dumps(found, sort_keys=True))
        return 0
    if a.command == "artifact":
        print(artifact_hash(a.cli_root))
        return 0
    value = definition_sha(a.repo, a.sha, a.case)
    if value is None:
        print("cannot compute the case definition", file=sys.stderr)
        return 1
    print(value)
    return 0


if __name__ == "__main__":
    sys.exit(main())

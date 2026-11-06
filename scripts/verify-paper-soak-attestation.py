#!/usr/bin/env python3
"""Validate exact-image live-parity evidence for promotion."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path


SHA_RE = re.compile(r"[0-9a-f]{40}")
DIGEST_RE = re.compile(r"sha256:[0-9a-f]{64}")
RUN_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{7,127}")
REQUIRED_ARTIFACTS = (
    "health",
    "journal",
    "reconciliation",
    "coverage",
    "parity",
    "insights",
    "catalog",
    "trace",
)
# Lanes a release cannot leave out; the rest are the profile's. Shadow runs inside the wave, whose evidence is the bundle.
CORE_LANES = ("orders", "risk", "book", "engine", "daemon", "stress")
MAX_EVIDENCE_AGE_HOURS = 24


def fail(message: str) -> None:
    raise ValueError(message)


def require_int(value: object, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        fail(f"{field} must be an integer")
    return value


def require_string(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        fail(f"{field} must be a non-blank string")
    return value.strip()


def parse_utc(value: object, field: str) -> datetime:
    raw = require_string(value, field)
    try:
        parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError as error:
        fail(f"{field} must be an ISO-8601 timestamp: {error}")
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        fail(f"{field} must be UTC")
    return parsed


def validate(
    document: object,
    expected_git_sha: str,
    expected_image_repository: str,
    min_continuous_hours: int,
    min_trading_days: int,
    max_dropped_ticks: int,
) -> tuple[str, float, int]:
    if not isinstance(document, dict):
        fail("attestation root must be an object")
    if document.get("schemaVersion") != 1:
        fail("schemaVersion must be 1")

    attestation_type = require_string(document.get("attestationType"), "attestationType").lower()
    if attestation_type != "live-parity":
        fail("attestationType must be live-parity")
    run_id = require_string(document.get("runId"), "runId")
    if not RUN_ID_RE.fullmatch(run_id):
        fail("runId must be a fresh opaque run identifier")
    input_fingerprint = require_string(document.get("inputFingerprint"), "inputFingerprint")
    if not re.fullmatch(r"[0-9a-f]{64}", input_fingerprint):
        fail("inputFingerprint must be a lowercase SHA-256")

    git_sha = require_string(document.get("testingSha"), "testingSha")
    if not SHA_RE.fullmatch(git_sha):
        fail("testingSha must be a lowercase 40-character git SHA")
    if git_sha != expected_git_sha:
        fail(f"testingSha {git_sha} does not match current testing {expected_git_sha}")

    image = require_string(document.get("image"), "image")
    prefix = f"{expected_image_repository}@"
    if not image.startswith(prefix) or not DIGEST_RE.fullmatch(image.removeprefix(prefix)):
        fail(f"image must pin {expected_image_repository} by sha256 digest")

    if require_string(document.get("accountMode"), "accountMode").lower() != "demo":
        fail("accountMode must be demo")
    require_string(document.get("canaryStrategy"), "canaryStrategy")
    if require_string(document.get("status"), "status").lower() != "pass":
        fail("status must be pass")

    started_at = parse_utc(document.get("startedAtUtc"), "startedAtUtc")
    completed_at = parse_utc(document.get("completedAtUtc"), "completedAtUtc")
    duration_hours = (completed_at - started_at).total_seconds() / 3600.0
    if duration_hours < 0:
        fail("completedAtUtc must not precede startedAtUtc")
    trading_days = require_int(document.get("tradingDays"), "tradingDays")
    if trading_days < 0:
        fail("tradingDays must be non-negative")
    if duration_hours < min_continuous_hours:
        fail(f"run lasted {duration_hours:.2f}h, below the required {min_continuous_hours}h")
    if trading_days < min_trading_days:
        fail(f"tradingDays {trading_days} is below the required {min_trading_days}")
    metrics = document.get("metrics")
    if not isinstance(metrics, dict):
        fail("metrics must be an object")
    for field in ("unreconciledPositions", "unknownOutcomePlacements"):
        value = require_int(metrics.get(field), f"metrics.{field}")
        if value != 0:
            fail(f"metrics.{field} must be zero, got {value}")
    dropped_ticks = require_int(metrics.get("droppedTicks"), "metrics.droppedTicks")
    if dropped_ticks < 0 or dropped_ticks > max_dropped_ticks:
        fail(f"metrics.droppedTicks must be between 0 and {max_dropped_ticks}, got {dropped_ticks}")
    health_samples = require_int(metrics.get("healthSamples"), "metrics.healthSamples")
    if health_samples <= 0:
        fail("metrics.healthSamples must be positive")

    parity = document.get("parity")
    if not isinstance(parity, dict):
        fail("parity must be an object")
    duration_minutes = require_int(parity.get("durationMinutes"), "parity.durationMinutes")
    if duration_minutes < 10:
        fail("parity.durationMinutes must be at least 10")
    for field in (
        "strategiesTested", "indicatorsTested", "mathScenariosTested",
        "dslScenariosTested", "orderTypesTested", "totalTicks", "totalBars",
        "fills", "parityComparisons", "insightsEvents", "warmupBars", "warmupTicks",
        "barBoundaryTransitions",
    ):
        if require_int(parity.get(field), f"parity.{field}") <= 0:
            fail(f"parity.{field} must be positive")
    for field in ("parityMismatches", "unexplainedRejections", "unexplainedOrderOutcomes"):
        if require_int(parity.get(field), f"parity.{field}") != 0:
            fail(f"parity.{field} must be zero")
    timeframes = parity.get("timeframesTested")
    if not isinstance(timeframes, list) or not timeframes:
        fail("parity.timeframesTested must be a non-empty list")
    for timeframe in timeframes:
        if not isinstance(timeframe, str) or not timeframe.strip():
            fail("parity.timeframesTested entries must be non-blank strings")

    artifacts = document.get("artifacts")
    if not isinstance(artifacts, dict):
        fail("artifacts must be an object")
    artifact_hashes = document.get("artifactSha256")
    if not isinstance(artifact_hashes, dict):
        fail("artifactSha256 must be an object")
    for field in REQUIRED_ARTIFACTS:
        artifact_name = require_string(artifacts.get(field), f"artifacts.{field}")
        if Path(artifact_name).name != artifact_name:
            fail(f"artifacts.{field} must be a sibling filename")
        expected_hash = require_string(artifact_hashes.get(field), f"artifactSha256.{field}")
        if not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
            fail(f"artifactSha256.{field} must be a lowercase SHA-256")

    return image, duration_hours, trading_days


def verify_artifacts(document: dict[str, object], attestation: Path) -> None:
    artifacts = document["artifacts"]
    artifact_hashes = document["artifactSha256"]
    assert isinstance(artifacts, dict)
    assert isinstance(artifact_hashes, dict)
    for field in REQUIRED_ARTIFACTS:
        artifact = attestation.parent / str(artifacts[field])
        if not artifact.is_file():
            fail(f"required {field} artifact is missing: {artifact}")
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        if digest != artifact_hashes[field]:
            fail(f"{field} artifact SHA-256 mismatch")
    verify_trace(document, attestation.parent / str(artifacts["trace"]))


def verify_trace(document: dict[str, object], path: Path) -> None:
    """The causal-chain trace (live against replay, per engine order) is what parity.parityMismatches counts."""
    trace = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(trace, dict) or trace.get("schema") != "qkt-attestation-trace-v1":
        fail("trace artifact has the wrong schema")
    counted = require_int(trace.get("parityMismatches"), "trace.parityMismatches")
    parity = document["parity"]
    assert isinstance(parity, dict)
    if counted != parity.get("parityMismatches"):
        fail(f"trace artifact counts {counted} parity mismatches; parity.parityMismatches is {parity.get('parityMismatches')}")


def binding_sha(binding: object) -> str:
    return hashlib.sha256(json.dumps(binding, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def ready_cases(root: Path, lanes: list[str]) -> set[str]:
    names = set()
    for lane in lanes:
        folder = root / lane
        for case_yaml in sorted(folder.glob("*/case.yaml")) if folder.is_dir() else []:
            if re.search(r"^status:\s*ready\b", case_yaml.read_text(encoding="utf-8"), re.M):
                names.add(f"{lane}-{case_yaml.parent.name}")
    return names


def load_fingerprints():
    """lib/runtime_fingerprint.py, the one definition of the runtime fingerprint and the case definition hash."""
    import importlib.util
    path = Path(__file__).resolve().parent / "live-validation" / "lib" / "runtime_fingerprint.py"
    spec = importlib.util.spec_from_file_location("runtime_fingerprint", path)
    if spec is None or spec.loader is None or not path.is_file():
        fail("runtime fingerprint library is missing: cannot verify a v2 binding")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verify_v2_binding(binding: dict, expected_git_sha: str, repo_root: Path | None):
    """A v2 binding keys evidence by what ships, not by the commit. Nothing it says about that is taken on trust: the fingerprint
    is recomputed from git for the verified commit, and the environment must be named."""
    if repo_root is None:
        fail("a v2 binding (runtime fingerprint) can only be verified with --repo-root")
    lib = load_fingerprints()
    claimed = binding.get("runtimeFingerprint")
    if not isinstance(claimed, str) or not re.fullmatch(r"[0-9a-f]{64}", claimed):
        fail("binding.runtimeFingerprint must be a lowercase SHA-256")
    actual = lib.runtime_fingerprint(str(repo_root), expected_git_sha)
    if actual is None:
        fail(f"cannot compute the runtime fingerprint of {expected_git_sha} in {repo_root}")
    if actual != claimed:
        fail(f"runtime fingerprint mismatch: the evidence is for {claimed[:12]}, {expected_git_sha[:8]} ships {actual[:12]}")
    environment = binding.get("environment")
    if not isinstance(environment, dict) or not environment.get("java") or not environment.get("gatewayImages"):
        fail("a v2 binding must name the JDK and the gateway images")
    if binding.get("carryMode") == "artifact+source":
        if not re.fullmatch(r"[0-9a-f]{64}", str(binding.get("artifactHash"))):
            fail("binding.artifactHash must be a lowercase SHA-256 in artifact+source mode")
    elif binding.get("carryMode") != "source" or binding.get("artifactHash"):
        fail("binding.carryMode must be artifact+source or source, matching artifactHash")
    return lib


def verify_carry(item: dict, name: str, binding: dict, expected_git_sha: str, repo_root: Path, lib) -> None:
    """A case that ran on another commit stands only when that commit ships the same fingerprint and the case's definition
    is unchanged. Both are recomputed from git for the commit the record says it ran on."""
    ran_on = item.get("ranOnSha")
    if not isinstance(ran_on, str) or not SHA_RE.fullmatch(ran_on):
        fail(f"case {name} does not say which commit it ran on")
    if lib.runtime_fingerprint(str(repo_root), ran_on) != binding["runtimeFingerprint"]:
        fail(f"case {name} ran on {ran_on[:8]}, which does not ship the fingerprint of {expected_git_sha[:8]}")
    recorded = item.get("definitionSha256")
    here = lib.definition_sha(str(repo_root), expected_git_sha, name)
    there = lib.definition_sha(str(repo_root), ran_on, name)
    if here is None or recorded != here or recorded != there:
        fail(f"case {name} was defined differently when it ran on {ran_on[:8]}: it must run again")
    carried = item.get("carriedFrom")
    if ran_on != expected_git_sha and not carried:
        fail(f"case {name} ran on {ran_on[:8]} but the bundle does not log it as carried")
    if carried and carried.get("sha") != ran_on:
        fail(f"case {name} carry record disagrees with the commit it ran on")


def verify_catalog_evidence(document: dict[str, object], attestation: Path, expected_git_sha: str, cases_root: Path | None,
                            repo_root: Path | None = None) -> None:
    """A release needs every required case passed on this exact build, however many runs it took to get there."""
    artifacts = document["artifacts"]
    assert isinstance(artifacts, dict)
    evidence = json.loads((attestation.parent / str(artifacts["catalog"])).read_text(encoding="utf-8"))
    if not isinstance(evidence, dict) or evidence.get("schema") != "qkt-attestation-catalog-evidence-v1":
        fail("catalog evidence has the wrong schema")
    if evidence.get("testingSha") != expected_git_sha:
        fail(f"catalog evidence is for {evidence.get('testingSha')}, not {expected_git_sha}")
    binding = evidence.get("binding")
    if not isinstance(binding, dict) or binding.get("testingSha") != expected_git_sha:
        fail("catalog evidence is not bound to this commit")
    if expected_git_sha not in str(binding.get("cliVersion")):
        fail(f"catalog evidence's CLI is not this commit's build: {binding.get('cliVersion')}")
    expected_binding = binding_sha(binding)
    if evidence.get("bindingSha256") != expected_binding:
        fail("catalog evidence's binding hash does not match its binding")
    v2 = binding.get("schema") == "qkt-attestation-binding-v2"
    lib = verify_v2_binding(binding, expected_git_sha, repo_root) if v2 else None
    # Every case record carries the hash of its key: the whole exact-commit binding (v1), or for v2 only what ships, the
    # profile, the account and the environment (never the commit, never the CLI's bytes).
    case_key = lib.evidence_key_sha(binding) if v2 else expected_binding
    if evidence.get("waveRunId") != document.get("runId"):
        fail("catalog evidence belongs to another wave")
    assembled = parse_utc(evidence.get("assembledAtUtc"), "catalog.assembledAtUtc")
    if evidence.get("maxEvidenceAgeHours") != MAX_EVIDENCE_AGE_HOURS:
        fail(f"catalog evidence must use the {MAX_EVIDENCE_AGE_HOURS}h limit")

    def fresh(moment: object, label: str) -> None:
        age = (assembled - parse_utc(moment, label)).total_seconds() / 3600.0
        if age < 0 or age > MAX_EVIDENCE_AGE_HOURS:
            fail(f"{label} is {age:.1f}h old at assembly; the limit is {MAX_EVIDENCE_AGE_HOURS}h")

    stages = evidence.get("stages")
    if not isinstance(stages, dict):
        fail("catalog evidence has no stages")
    for name in ("proofs", "wave", "insights"):
        stage = stages.get(name)
        if not isinstance(stage, dict) or stage.get("status") != "passed":
            fail(f"stage {name} did not pass")
        fresh(stage.get("completedAtUtc"), f"stage {name}")

    lanes = evidence.get("lanes")
    if not isinstance(lanes, list) or not set(CORE_LANES) <= set(lanes):
        fail(f"catalog evidence must cover the lanes {', '.join(CORE_LANES)}")
    seen = {}
    for item in evidence.get("cases") or []:
        name = require_string(item.get("name"), "catalog case name")
        if item.get("status") != "passed":
            fail(f"case {name} did not pass")
        if item.get("bindingSha256") != case_key:
            fail(f"case {name} passed on another build, profile or account")
        if v2:
            verify_carry(item, name, binding, expected_git_sha, repo_root, lib)
        attempts = item.get("attempts")
        if not isinstance(attempts, list) or not attempts or attempts[-1].get("status") != "passed":
            fail(f"case {name} does not end on a passed attempt")
        if bool(item.get("flaky")) != any(a.get("status") != "passed" for a in attempts):
            fail(f"case {name} hides a failed attempt")
        if not re.fullmatch(r"[0-9a-f]{64}", str(item.get("resultSha256"))):
            fail(f"case {name} has no evidence hash")
        fresh(item.get("passedAtUtc"), f"case {name}")
        seen[name] = item
    if cases_root is not None:
        missing = ready_cases(cases_root, [str(lane) for lane in lanes]) - set(seen)
        if missing:
            fail(f"required cases have no pass on this build: {', '.join(sorted(missing))}")
    if not seen:
        fail("catalog evidence holds no case")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("attestation", type=Path)
    parser.add_argument("--expected-git-sha", required=True)
    parser.add_argument("--expected-image-repository", required=True)
    parser.add_argument("--min-continuous-hours", type=int, default=0, help=argparse.SUPPRESS)
    parser.add_argument("--min-trading-days", type=int, default=0, help=argparse.SUPPRESS)
    parser.add_argument("--max-dropped-ticks", type=int, default=0)
    parser.add_argument("--repo-root", type=Path, help="a git checkout holding the verified commit (and any commit evidence ran on): required to verify a v2 binding")
    parser.add_argument("--cases-root", type=Path, help="attestation/cases at this commit: every ready case must be in the evidence")
    args = parser.parse_args()

    try:
        document = json.loads(args.attestation.read_text(encoding="utf-8"))
        image, duration_hours, trading_days = validate(
            document,
            args.expected_git_sha,
            args.expected_image_repository,
            args.min_continuous_hours,
            args.min_trading_days,
            args.max_dropped_ticks,
        )
        assert isinstance(document, dict)
        verify_artifacts(document, args.attestation)
        verify_catalog_evidence(document, args.attestation, args.expected_git_sha, args.cases_root, args.repo_root)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"paper-soak attestation invalid: {error}", file=sys.stderr)
        return 1

    print(
        f"paper-soak attestation valid: image={image} "
        f"duration={duration_hours:.2f}h tradingDays={trading_days} type=live-parity"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

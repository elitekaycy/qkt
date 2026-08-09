#!/usr/bin/env python3
"""Validate the paper-soak evidence required for testing-to-main promotion."""

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
    if duration_hours < min_continuous_hours and trading_days < min_trading_days:
        fail(
            f"soak duration is {duration_hours:.2f}h/{trading_days} trading days; "
            f"require at least {min_continuous_hours}h continuous or {min_trading_days} trading days"
        )

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

    artifacts = document.get("artifacts")
    if not isinstance(artifacts, dict):
        fail("artifacts must be an object")
    artifact_hashes = document.get("artifactSha256")
    if not isinstance(artifact_hashes, dict):
        fail("artifactSha256 must be an object")
    for field in ("health", "journal", "reconciliation"):
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
    for field in ("health", "journal", "reconciliation"):
        artifact = attestation.parent / str(artifacts[field])
        if not artifact.is_file():
            fail(f"required {field} artifact is missing: {artifact}")
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        if digest != artifact_hashes[field]:
            fail(f"{field} artifact SHA-256 mismatch")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("attestation", type=Path)
    parser.add_argument("--expected-git-sha", required=True)
    parser.add_argument("--expected-image-repository", required=True)
    parser.add_argument("--min-continuous-hours", type=int, default=48)
    parser.add_argument("--min-trading-days", type=int, default=5)
    parser.add_argument("--max-dropped-ticks", type=int, default=0)
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
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"paper-soak attestation invalid: {error}", file=sys.stderr)
        return 1

    print(
        f"paper-soak attestation valid: image={image} "
        f"duration={duration_hours:.2f}h tradingDays={trading_days}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

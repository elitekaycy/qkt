#!/usr/bin/env python3
"""Verify a qkt backtest report bundle before trusting its numbers."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal, ROUND_HALF_EVEN, localcontext
from pathlib import Path
from typing import Any


MONEY_SCALE = Decimal("0.00000000")
RESULT_SCHEMA = "qkt-backtest-result-v1"
RESULT_SCHEMA_VERSION = 1
REQUIRED_ARTIFACTS = {
    "result.json",
    "trades.csv",
    "rejections.csv",
    "pnl_components.csv",
    "equity_global.csv",
    "report.html",
}


@dataclass
class AuditResult:
    ok: bool
    errors: list[str]
    warnings: list[str]
    artifacts: list[str]
    trade_summary: dict[str, str | int | Decimal | None]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_dir", type=Path, help="Directory produced by qkt backtest --report")
    parser.add_argument("--json", action="store_true", help="Print machine-readable audit result")
    args = parser.parse_args(argv)

    result = audit_report_bundle(args.report_dir)
    if args.json:
        print(
            json.dumps(
                {
                    "ok": result.ok,
                    "errors": result.errors,
                    "warnings": result.warnings,
                    "artifacts": result.artifacts,
                    "tradeSummary": json_ready(result.trade_summary),
                },
                sort_keys=True,
            ),
        )
    elif result.ok:
        print(f"ok: verified {len(result.artifacts)} artifacts in {args.report_dir}")
        for warning in result.warnings:
            print(f"warning: {warning}")
    else:
        print(f"failed: {args.report_dir}", file=sys.stderr)
        for error in result.errors:
            print(f"error: {error}", file=sys.stderr)
        for warning in result.warnings:
            print(f"warning: {warning}", file=sys.stderr)
    return 0 if result.ok else 1


def audit_report_bundle(report_dir: Path) -> AuditResult:
    errors: list[str] = []
    warnings: list[str] = []
    artifacts: list[str] = []
    trade_summary: dict[str, str | int | Decimal | None] = {}

    if not report_dir.is_dir():
        return AuditResult(False, [f"not a directory: {report_dir}"], warnings, artifacts, trade_summary)

    manifest_path = report_dir / "manifest.json"
    result_path = report_dir / "result.json"
    manifest = load_json(manifest_path, errors)
    result_json = load_json(result_path, errors)
    if manifest is None or result_json is None:
        return AuditResult(False, errors, warnings, artifacts, trade_summary)

    artifacts = verify_manifest(report_dir, manifest, errors)
    missing_required = REQUIRED_ARTIFACTS.difference(artifacts)
    for path in sorted(missing_required):
        errors.append(f"manifest missing required artifact: {path}")
    verify_result_contract(result_json, errors)
    verify_result_artifacts(result_json, artifacts, errors)
    verify_equity_curves(report_dir, result_json, errors)
    verify_pnl_components(report_dir, result_json, errors)

    trade_summary = compute_trade_summary(report_dir, errors)
    expected = result_json.get("tradeSummary")
    if not isinstance(expected, dict):
        errors.append("result.json missing object tradeSummary")
    else:
        compare_trade_summary(expected, trade_summary, errors)
    verify_global_trade_count(result_json, trade_summary, errors)

    return AuditResult(not errors, errors, warnings, artifacts, trade_summary)


def load_json(path: Path, errors: list[str]) -> Any | None:
    try:
        with path.open("r", encoding="utf-8") as fh:
            return json.load(fh)
    except FileNotFoundError:
        errors.append(f"missing JSON file: {path.name}")
    except json.JSONDecodeError as exc:
        errors.append(f"malformed JSON in {path.name}: {exc}")
    return None


def verify_manifest(report_dir: Path, manifest: Any, errors: list[str]) -> list[str]:
    if not isinstance(manifest, dict):
        errors.append("manifest.json root is not an object")
        return []
    if manifest.get("schema") != "qkt-report-bundle-v1":
        errors.append(f"unsupported manifest schema: {manifest.get('schema')!r}")
    if manifest.get("schemaVersion") != 1:
        errors.append(f"unsupported manifest schemaVersion: {manifest.get('schemaVersion')!r}")
    if manifest.get("selfHashIncluded") is not False:
        errors.append("manifest selfHashIncluded must be false")

    entries = manifest.get("artifacts")
    if not isinstance(entries, list):
        errors.append("manifest artifacts must be an array")
        return []

    seen: set[str] = set()
    artifacts: list[str] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            errors.append(f"manifest artifact #{index} is not an object")
            continue
        rel = entry.get("path")
        if not isinstance(rel, str):
            errors.append(f"manifest artifact #{index} has non-string path")
            continue
        if rel in seen:
            errors.append(f"manifest artifact duplicated: {rel}")
            continue
        seen.add(rel)
        artifacts.append(rel)
        if rel == "manifest.json":
            errors.append("manifest must not hash itself")
        if Path(rel).is_absolute() or ".." in Path(rel).parts:
            errors.append(f"unsafe manifest artifact path: {rel}")
            continue
        artifact_path = report_dir / rel
        if not artifact_path.is_file():
            errors.append(f"manifest artifact missing on disk: {rel}")
            continue
        expected_hash = entry.get("sha256")
        actual_hash = sha256(artifact_path)
        if expected_hash != actual_hash:
            errors.append(f"hash mismatch for {rel}: expected {expected_hash}, got {actual_hash}")
        expected_bytes = entry.get("bytes")
        actual_bytes = artifact_path.stat().st_size
        if expected_bytes != actual_bytes:
            errors.append(f"byte-size mismatch for {rel}: expected {expected_bytes}, got {actual_bytes}")
    return artifacts


def verify_result_artifacts(result_json: Any, manifest_artifacts: list[str], errors: list[str]) -> None:
    if not isinstance(result_json, dict):
        errors.append("result.json root is not an object")
        return
    artifact_obj = result_json.get("artifacts")
    if not isinstance(artifact_obj, dict):
        errors.append("result.json missing object artifacts")
        return
    manifest_set = set(manifest_artifacts)
    expected_fields = {
        "resultJson": "result.json",
        "tradesCsv": "trades.csv",
        "rejectionsCsv": "rejections.csv",
        "pnlComponentsCsv": "pnl_components.csv",
        "manifestJson": "manifest.json",
        "equityGlobalCsv": "equity_global.csv",
        "html": "report.html",
    }
    for field, expected_path in expected_fields.items():
        actual = artifact_obj.get(field)
        if actual != expected_path:
            errors.append(f"result.json artifacts.{field} mismatch: expected {expected_path}, got {actual!r}")
    for field, value in artifact_obj.items():
        if field == "manifestJson":
            continue
        if field == "equityStrategyCsv":
            if not isinstance(value, dict):
                errors.append("result.json artifacts.equityStrategyCsv must be an object")
                continue
            for strategy_id, rel in value.items():
                if rel not in manifest_set:
                    errors.append(f"result.json artifacts.equityStrategyCsv[{strategy_id!r}] not in manifest: {rel!r}")
            continue
        if isinstance(value, str) and value not in manifest_set:
            errors.append(f"result.json artifacts.{field} not in manifest: {value!r}")


def verify_result_contract(result_json: Any, errors: list[str]) -> None:
    if not isinstance(result_json, dict):
        errors.append("result.json root is not an object")
        return
    if result_json.get("schema") != RESULT_SCHEMA:
        errors.append(f"unsupported result.json schema: {result_json.get('schema')!r}")
    if result_json.get("schemaVersion") != RESULT_SCHEMA_VERSION:
        errors.append(f"unsupported result.json schemaVersion: {result_json.get('schemaVersion')!r}")

    global_report = result_json.get("global")
    if isinstance(global_report, dict):
        verify_report_invariants(global_report, "global", errors)
    else:
        errors.append("result.json missing object global")

    per_strategy = result_json.get("perStrategy")
    if not isinstance(per_strategy, dict):
        errors.append("result.json perStrategy is not an object")
        return
    for strategy_id, report in per_strategy.items():
        if not isinstance(report, dict):
            errors.append(f"result.json perStrategy[{strategy_id!r}] is not an object")
            continue
        verify_report_invariants(report, f"strategy {strategy_id}", errors)
    if isinstance(global_report, dict) and per_strategy:
        verify_global_matches_strategy_sum(global_report, per_strategy, errors)


def verify_report_invariants(report: dict[str, Any], label: str, errors: list[str]) -> None:
    realized = parse_summary_decimal(report.get("realizedTotal"), f"{label}.realizedTotal", errors)
    unrealized = parse_summary_decimal(report.get("unrealizedTotal"), f"{label}.unrealizedTotal", errors)
    total = parse_summary_decimal(report.get("totalPnL"), f"{label}.totalPnL", errors)
    if realized is not None and unrealized is not None and total is not None:
        expected_total = money(realized + unrealized)
        if expected_total != total:
            errors.append(f"{label}.totalPnL mismatch: realized + unrealized = {expected_total}, got {total}")

    trade_count = report.get("tradeCount")
    if not isinstance(trade_count, int):
        errors.append(f"{label}.tradeCount is not an integer")
    elif trade_count < 0:
        errors.append(f"{label}.tradeCount is negative: {trade_count}")

    win_rate = parse_summary_decimal(report.get("winRate"), f"{label}.winRate", errors)
    if win_rate is not None and (win_rate < Decimal("0") or win_rate > Decimal("1")):
        errors.append(f"{label}.winRate outside [0, 1]: {win_rate}")
    for field in ("maxDrawdown", "maxDailyDrawdown", "turnover", "commissionPaid"):
        value = parse_summary_decimal(report.get(field), f"{label}.{field}", errors)
        if value is not None and value < Decimal("0"):
            errors.append(f"{label}.{field} is negative: {value}")


def verify_global_matches_strategy_sum(
    global_report: dict[str, Any],
    per_strategy: dict[str, Any],
    errors: list[str],
) -> None:
    decimal_fields = ("realizedTotal", "unrealizedTotal", "totalPnL", "commissionPaid", "swapPaid")
    for field in decimal_fields:
        global_value = parse_summary_decimal(global_report.get(field), f"global.{field}", errors)
        strategy_sum = Decimal("0")
        complete = True
        for strategy_id, report in per_strategy.items():
            if not isinstance(report, dict):
                complete = False
                continue
            value = parse_summary_decimal(report.get(field), f"strategy {strategy_id}.{field}", errors)
            if value is None:
                complete = False
            else:
                strategy_sum += value
        if complete and global_value is not None and money(strategy_sum) != global_value:
            errors.append(f"global.{field} mismatch: per-strategy sum {money(strategy_sum)}, got {global_value}")

    global_trade_count = global_report.get("tradeCount")
    strategy_trade_count = sum(report.get("tradeCount", 0) for report in per_strategy.values() if isinstance(report, dict))
    if isinstance(global_trade_count, int) and global_trade_count != strategy_trade_count:
        errors.append(f"global.tradeCount mismatch: per-strategy sum {strategy_trade_count}, got {global_trade_count}")

    global_daily = daily_pnl_decimals(global_report, "global", errors)
    strategy_daily: dict[str, Decimal] = {}
    complete_daily = True
    for strategy_id, report in per_strategy.items():
        if not isinstance(report, dict):
            complete_daily = False
            continue
        daily = daily_pnl_decimals(report, f"strategy {strategy_id}", errors)
        if daily is None:
            complete_daily = False
            continue
        for date, value in daily.items():
            strategy_daily[date] = strategy_daily.get(date, Decimal("0")) + value
    if complete_daily and global_daily is not None:
        if set(global_daily) != set(strategy_daily):
            errors.append(
                f"global.dailyPnL dates mismatch: per-strategy dates {sorted(strategy_daily)}, "
                f"global dates {sorted(global_daily)}",
            )
        for date in sorted(set(global_daily) & set(strategy_daily)):
            expected = money(strategy_daily[date])
            if expected != global_daily[date]:
                errors.append(f"global.dailyPnL[{date}] mismatch: per-strategy sum {expected}, got {global_daily[date]}")


def daily_pnl_decimals(report: dict[str, Any], label: str, errors: list[str]) -> dict[str, Decimal] | None:
    daily_pnl = report.get("dailyPnL")
    if not isinstance(daily_pnl, dict):
        errors.append(f"{label}.dailyPnL is not an object")
        return None
    values: dict[str, Decimal] = {}
    for date, raw in daily_pnl.items():
        value = parse_summary_decimal(raw, f"{label}.dailyPnL[{date}]", errors)
        if value is not None:
            values[date] = value
    return values


def verify_global_trade_count(
    result_json: Any,
    trade_summary: dict[str, str | int | Decimal | None],
    errors: list[str],
) -> None:
    if not isinstance(result_json, dict):
        return
    global_report = result_json.get("global")
    if not isinstance(global_report, dict):
        errors.append("result.json missing object global")
        return
    trade_count = global_report.get("tradeCount")
    fills = trade_summary.get("fills")
    if trade_count != fills:
        errors.append(f"global.tradeCount mismatch: expected {trade_count}, computed fills {fills}")


def verify_equity_curves(report_dir: Path, result_json: Any, errors: list[str]) -> None:
    if not isinstance(result_json, dict):
        return
    artifacts = result_json.get("artifacts")
    if not isinstance(artifacts, dict):
        return
    global_report = result_json.get("global")
    if isinstance(global_report, dict):
        global_rel = artifacts.get("equityGlobalCsv", "equity_global.csv")
        if isinstance(global_rel, str):
            verify_one_equity_curve(report_dir, global_rel, global_report.get("equityCurve"), "global", errors)
    per_strategy = result_json.get("perStrategy")
    strategy_artifacts = artifacts.get("equityStrategyCsv")
    if not isinstance(per_strategy, dict) or not isinstance(strategy_artifacts, dict):
        return
    for strategy_id, report in per_strategy.items():
        rel = strategy_artifacts.get(strategy_id)
        if not isinstance(rel, str):
            errors.append(f"result.json missing equity CSV artifact for strategy {strategy_id!r}")
            continue
        if not isinstance(report, dict):
            errors.append(f"result.json perStrategy[{strategy_id!r}] is not an object")
            continue
        verify_one_equity_curve(report_dir, rel, report.get("equityCurve"), f"strategy {strategy_id}", errors)


def verify_one_equity_curve(
    report_dir: Path,
    rel: str,
    json_curve: Any,
    label: str,
    errors: list[str],
) -> None:
    if not isinstance(json_curve, list):
        errors.append(f"{label} equityCurve is not an array")
        return
    if Path(rel).is_absolute() or ".." in Path(rel).parts:
        errors.append(f"{label} equity CSV path is unsafe: {rel}")
        return
    csv_path = report_dir / rel
    csv_rows = read_csv(csv_path, errors)
    if len(csv_rows) != len(json_curve):
        errors.append(f"{label} equity row count mismatch: json {len(json_curve)}, csv {len(csv_rows)}")
        return
    for index, (row, sample) in enumerate(zip(csv_rows, json_curve), start=2):
        if not isinstance(sample, dict):
            errors.append(f"{label} equityCurve sample #{index - 1} is not an object")
            continue
        csv_timestamp = row.get("timestamp")
        json_timestamp = sample.get("timestamp")
        if str(json_timestamp) != csv_timestamp:
            errors.append(
                f"{label} equity row {index} timestamp mismatch: json {json_timestamp}, csv {csv_timestamp}",
            )
        csv_equity = parse_decimal(row.get("equity", ""), f"{label} equity row {index} equity", errors)
        json_equity = parse_summary_decimal(sample.get("equity"), f"{label}.equityCurve[{index - 2}].equity", errors)
        if json_equity is not None and json_equity != csv_equity:
            errors.append(f"{label} equity row {index} mismatch: json {json_equity}, csv {csv_equity}")


def verify_pnl_components(report_dir: Path, result_json: Any, errors: list[str]) -> None:
    if not isinstance(result_json, dict):
        return
    trades = read_csv(report_dir / "trades.csv", errors)
    rows = read_csv(report_dir / "pnl_components.csv", errors)
    components: dict[tuple[str, str, str], dict[str, str]] = {}
    expected_keys: set[tuple[str, str, str]] = set()
    for row_index, row in enumerate(rows, start=2):
        key = (row.get("scope", ""), row.get("strategy", ""), row.get("date", ""))
        if key in components:
            errors.append(f"pnl_components.csv row {row_index}: duplicate component key {key!r}")
            continue
        components[key] = row

    trade_daily = compute_trade_daily(trades, errors)
    global_report = result_json.get("global")
    if isinstance(global_report, dict):
        expected_keys.update(
            verify_one_pnl_component_scope(
                components,
                trade_daily,
                global_report,
                scope="global",
                strategy_id="",
                errors=errors,
            ),
        )
    else:
        errors.append("result.json missing object global")

    per_strategy = result_json.get("perStrategy")
    if isinstance(per_strategy, dict):
        for strategy_id, report in per_strategy.items():
            if not isinstance(report, dict):
                errors.append(f"result.json perStrategy[{strategy_id!r}] is not an object")
                continue
            scoped_trade_daily = {
                date: by_strategy[strategy_id]
                for date, by_strategy in trade_daily.items()
                if strategy_id in by_strategy
            }
            expected_keys.update(
                verify_one_pnl_component_scope(
                    components,
                    scoped_trade_daily,
                    report,
                    scope="strategy",
                    strategy_id=strategy_id,
                    errors=errors,
                ),
            )

    for key in sorted(set(components) - expected_keys):
        errors.append(f"pnl_components.csv contains unexpected component key: {key!r}")


def verify_one_pnl_component_scope(
    components: dict[tuple[str, str, str], dict[str, str]],
    trade_daily: dict[str, dict[str, Decimal] | Decimal],
    report: dict[str, Any],
    scope: str,
    strategy_id: str,
    errors: list[str],
) -> set[tuple[str, str, str]]:
    label = "global" if scope == "global" else f"strategy {strategy_id}"
    daily_pnl = report.get("dailyPnL")
    if not isinstance(daily_pnl, dict):
        errors.append(f"{label} dailyPnL is not an object")
        return set()

    trade_by_date = {
        date: (
            sum(value.values(), Decimal("0"))
            if isinstance(value, dict)
            else value
        )
        for date, value in trade_daily.items()
    }
    expected_dates = set(trade_by_date) | set(daily_pnl.keys())
    expected_keys = {(scope, strategy_id, date) for date in expected_dates}
    actual_dates = {date for row_scope, row_strategy, date in components if row_scope == scope and row_strategy == strategy_id}
    if actual_dates != expected_dates:
        errors.append(
            f"{label} pnl component dates mismatch: expected {sorted(expected_dates)}, got {sorted(actual_dates)}",
        )

    daily_total = Decimal("0")
    for date in sorted(expected_dates):
        row = components.get((scope, strategy_id, date))
        if row is None:
            continue
        trade_realized = parse_decimal(row.get("tradeRealized", ""), f"{label} pnl {date} tradeRealized", errors)
        adjustment = parse_decimal(row.get("adjustment", ""), f"{label} pnl {date} adjustment", errors)
        component_daily = parse_decimal(row.get("dailyPnL", ""), f"{label} pnl {date} dailyPnL", errors)
        expected_trade = money(trade_by_date.get(date, Decimal("0")))
        if trade_realized != expected_trade:
            errors.append(
                f"{label} pnl {date} tradeRealized mismatch: component {trade_realized}, computed {expected_trade}",
            )
        if money(trade_realized + adjustment) != component_daily:
            errors.append(
                f"{label} pnl {date} dailyPnL mismatch: tradeRealized + adjustment = "
                f"{money(trade_realized + adjustment)}, component {component_daily}",
            )
        json_daily = parse_summary_decimal(daily_pnl.get(date), f"{label}.dailyPnL[{date}]", errors)
        if json_daily is not None and json_daily != component_daily:
            errors.append(f"{label} pnl {date} mismatch: result.json {json_daily}, component {component_daily}")
        daily_total += component_daily

    realized_total = parse_summary_decimal(report.get("realizedTotal"), f"{label}.realizedTotal", errors)
    if realized_total is not None and money(daily_total) != realized_total:
        errors.append(
            f"{label} realizedTotal mismatch: dailyPnL sum {money(daily_total)}, result.json {realized_total}",
        )
    return expected_keys


def compute_trade_daily(
    trades: list[dict[str, str]],
    errors: list[str],
) -> dict[str, dict[str, Decimal]]:
    daily: dict[str, dict[str, Decimal]] = {}
    for row_index, row in enumerate(trades, start=2):
        date = trade_utc_date(row.get("timestamp", ""), row_index, errors)
        strategy_id = row.get("strategy", "")
        realized = net_realized_field(row, row_index, errors)
        by_strategy = daily.setdefault(date, {})
        by_strategy[strategy_id] = by_strategy.get(strategy_id, Decimal("0")) + realized
    return daily


def trade_utc_date(raw_timestamp: str, row_index: int, errors: list[str]) -> str:
    try:
        timestamp_ms = int(raw_timestamp)
    except Exception:
        errors.append(f"trades.csv row {row_index} timestamp: invalid epoch millis {raw_timestamp!r}")
        return "invalid"
    return datetime.fromtimestamp(timestamp_ms / 1000, UTC).date().isoformat()


def compute_trade_summary(report_dir: Path, errors: list[str]) -> dict[str, str | int | Decimal | None]:
    trades = read_csv(report_dir / "trades.csv", errors)
    rejections = read_csv(report_dir / "rejections.csv", errors)
    buy_realized = Decimal("0")
    sell_realized = Decimal("0")
    gross_profit = Decimal("0")
    gross_loss = Decimal("0")
    risks: list[Decimal] = []
    notionals: list[Decimal] = []
    buy_fills = 0
    sell_fills = 0
    position_effects: list[str] = []

    for row_index, row in enumerate(trades, start=2):
        side = row.get("side", "")
        realized = net_realized_field(row, row_index, errors)
        if realized > 0:
            gross_profit += realized
        elif realized < 0:
            gross_loss += realized
        if side == "BUY":
            buy_fills += 1
            buy_realized += realized
        elif side == "SELL":
            sell_fills += 1
            sell_realized += realized
        else:
            errors.append(f"trades.csv row {row_index}: unknown side {side!r}")

        position_effects.append(position_effect(row, row_index, errors))

        risk_raw = row.get("riskUsd", "")
        if risk_raw:
            risks.append(parse_decimal(risk_raw, f"trades.csv row {row_index} riskUsd", errors))

        price = decimal_field(row, "price", row_index, errors)
        quantity = decimal_field(row, "quantity", row_index, errors).copy_abs()
        contract_size_raw = row.get("contractSize", "")
        contract_size = parse_decimal(contract_size_raw, f"trades.csv row {row_index} contractSize", errors) if contract_size_raw else Decimal("1")
        computed_notional = price * quantity * contract_size
        notional_raw = row.get("fillNotional", "")
        if notional_raw:
            csv_notional = parse_decimal(notional_raw, f"trades.csv row {row_index} fillNotional", errors)
            if csv_notional != computed_notional:
                errors.append(
                    f"trades.csv row {row_index}: fillNotional {csv_notional} != "
                    f"price*abs(quantity)*contractSize {computed_notional}",
                )
        notionals.append(computed_notional)

    fills = len(trades)
    rejection_count = len(rejections)
    rejection_rate = None
    if fills + rejection_count > 0:
        with localcontext() as ctx:
            ctx.prec = 16
            ctx.rounding = ROUND_HALF_EVEN
            rejection_rate = money(Decimal(rejection_count) / Decimal(fills + rejection_count))

    return {
        "fills": fills,
        "buyFills": buy_fills,
        "sellFills": sell_fills,
        "sideAttribution": "fill_side",
        "longEntryFills": sum(
            effect in {"OPEN_LONG", "INCREASE_LONG", "REVERSE_TO_LONG"}
            for effect in position_effects
        ),
        "shortEntryFills": sum(
            effect in {"OPEN_SHORT", "INCREASE_SHORT", "REVERSE_TO_SHORT"}
            for effect in position_effects
        ),
        "longExitFills": sum(
            effect in {"REDUCE_LONG", "CLOSE_LONG", "REVERSE_TO_SHORT"}
            for effect in position_effects
        ),
        "shortExitFills": sum(
            effect in {"REDUCE_SHORT", "CLOSE_SHORT", "REVERSE_TO_LONG"}
            for effect in position_effects
        ),
        "unknownPositionFills": position_effects.count("UNKNOWN"),
        "positionAttribution": "strategy_position_transition",
        "buyRealized": money(buy_realized),
        "sellRealized": money(sell_realized),
        "grossProfit": money(gross_profit),
        "grossLoss": money(gross_loss),
        "rejections": rejection_count,
        "rejectionRate": rejection_rate,
        "riskAuditedFills": len(risks),
        "minRiskUsd": min(risks) if risks else None,
        "avgRiskUsd": money(sum(risks, Decimal("0")) / Decimal(len(risks))) if risks else None,
        "maxRiskUsd": max(risks) if risks else None,
        "tradedNotional": money(sum(notionals, Decimal("0"))),
        "maxFillNotional": money(max(notionals)) if notionals else None,
    }


def position_effect(row: dict[str, str], row_index: int, errors: list[str]) -> str:
    declared = row.get("positionEffect", "")
    valid = {
        "OPEN_LONG", "OPEN_SHORT", "INCREASE_LONG", "INCREASE_SHORT",
        "REDUCE_LONG", "REDUCE_SHORT", "CLOSE_LONG", "CLOSE_SHORT",
        "REVERSE_TO_LONG", "REVERSE_TO_SHORT", "UNKNOWN",
    }
    if declared not in valid:
        errors.append(f"trades.csv row {row_index}: invalid positionEffect {declared!r}")
        return "UNKNOWN"
    before_raw = row.get("strategyPositionQtyBefore", "")
    after_raw = row.get("strategyPositionQtyAfter", "")
    if not before_raw and not after_raw:
        computed = "UNKNOWN"
    else:
        before = parse_decimal(before_raw, f"trades.csv row {row_index} strategyPositionQtyBefore", errors) if before_raw else Decimal("0")
        after = parse_decimal(after_raw, f"trades.csv row {row_index} strategyPositionQtyAfter", errors) if after_raw else Decimal("0")
        if before == 0 and after > 0:
            computed = "OPEN_LONG"
        elif before == 0 and after < 0:
            computed = "OPEN_SHORT"
        elif before > 0 and after > before:
            computed = "INCREASE_LONG"
        elif before < 0 and after < before:
            computed = "INCREASE_SHORT"
        elif before > after > 0:
            computed = "REDUCE_LONG"
        elif before < after < 0:
            computed = "REDUCE_SHORT"
        elif before > 0 and after == 0:
            computed = "CLOSE_LONG"
        elif before < 0 and after == 0:
            computed = "CLOSE_SHORT"
        elif before < 0 < after:
            computed = "REVERSE_TO_LONG"
        elif before > 0 > after:
            computed = "REVERSE_TO_SHORT"
        else:
            computed = "UNKNOWN"
    if declared != computed:
        errors.append(
            f"trades.csv row {row_index}: positionEffect {declared} does not match "
            f"strategy position transition {computed}",
        )
    return computed


def net_realized_field(row: dict[str, str], row_index: int, errors: list[str]) -> Decimal:
    realized = decimal_field(row, "realized", row_index, errors)
    validate_gross_account_alias(row, row_index, errors)
    net_raw = row.get("netAccountRealized", "")
    if net_raw:
        net = parse_decimal(net_raw, f"trades.csv row {row_index} netAccountRealized", errors)
        if net != realized:
            errors.append(
                f"trades.csv row {row_index}: realized alias {realized} does not match "
                f"netAccountRealized {net}",
            )
        return net
    return realized


def validate_gross_account_alias(row: dict[str, str], row_index: int, errors: list[str]) -> None:
    gross_raw = row.get("grossAccountRealized", "")
    account_raw = row.get("accountRealized", "")
    if not gross_raw or not account_raw:
        return
    gross = parse_decimal(gross_raw, f"trades.csv row {row_index} grossAccountRealized", errors)
    account = parse_decimal(account_raw, f"trades.csv row {row_index} accountRealized", errors)
    if gross != account:
        errors.append(
            f"trades.csv row {row_index}: accountRealized alias {account} does not match "
            f"grossAccountRealized {gross}",
        )


def read_csv(path: Path, errors: list[str]) -> list[dict[str, str]]:
    try:
        with path.open("r", encoding="utf-8", newline="") as fh:
            reader = csv.DictReader(fh)
            if reader.fieldnames is None:
                errors.append(f"CSV has no header: {path.name}")
                return []
            return list(reader)
    except FileNotFoundError:
        errors.append(f"missing CSV file: {path.name}")
    except csv.Error as exc:
        errors.append(f"malformed CSV in {path.name}: {exc}")
    return []


def decimal_field(row: dict[str, str], field: str, row_index: int, errors: list[str]) -> Decimal:
    return parse_decimal(row.get(field, ""), f"trades.csv row {row_index} {field}", errors)


def parse_decimal(raw: str, label: str, errors: list[str]) -> Decimal:
    try:
        return Decimal(raw)
    except Exception:
        errors.append(f"{label}: invalid decimal {raw!r}")
        return Decimal("0")


def compare_trade_summary(
    expected: dict[str, Any],
    actual: dict[str, str | int | Decimal | None],
    errors: list[str],
) -> None:
    for key, actual_value in actual.items():
        if key not in expected:
            errors.append(f"result.json tradeSummary missing key: {key}")
            continue
        expected_value = expected[key]
        if isinstance(actual_value, int):
            if expected_value != actual_value:
                errors.append(f"tradeSummary.{key} mismatch: expected {expected_value}, computed {actual_value}")
        elif actual_value is None:
            if expected_value is not None:
                errors.append(f"tradeSummary.{key} mismatch: expected {expected_value}, computed null")
        elif key in {"sideAttribution", "positionAttribution"}:
            if expected_value != actual_value:
                errors.append(f"tradeSummary.{key} mismatch: expected {expected_value}, computed {actual_value}")
        else:
            expected_decimal = parse_summary_decimal(expected_value, f"tradeSummary.{key}", errors)
            if expected_decimal is not None and expected_decimal != actual_value:
                errors.append(f"tradeSummary.{key} mismatch: expected {expected_decimal}, computed {actual_value}")


def parse_summary_decimal(value: Any, key: str, errors: list[str]) -> Decimal | None:
    if value is None:
        return None
    try:
        return Decimal(str(value))
    except Exception:
        errors.append(f"{key}: invalid decimal {value!r}")
        return None


def money(value: Decimal) -> Decimal:
    return value.quantize(MONEY_SCALE, rounding=ROUND_HALF_EVEN)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return f"sha256:{digest.hexdigest()}"


def json_ready(values: dict[str, str | int | Decimal | None]) -> dict[str, str | int | None]:
    return {key: str(value) if isinstance(value, Decimal) else value for key, value in values.items()}


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

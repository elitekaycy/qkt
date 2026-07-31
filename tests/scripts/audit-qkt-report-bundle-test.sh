#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 -m py_compile "$ROOT/scripts/audit_qkt_report_bundle.py"

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
files = {
    "result.json": json.dumps(
        {
            "schema": "qkt-backtest-result-v1",
            "schemaVersion": 1,
            "artifacts": {
                "resultJson": "result.json",
                "tradesCsv": "trades.csv",
                "rejectionsCsv": "rejections.csv",
                "manifestJson": "manifest.json",
                "equityGlobalCsv": "equity_global.csv",
                "equityStrategyCsv": {},
                "html": "report.html",
                "pnlComponentsCsv": "pnl_components.csv",
            },
            "global": {
                "realizedTotal": "18.00000000",
                "unrealizedTotal": "2.00000000",
                "totalPnL": "20.00000000",
                "winRate": "0.50000000",
                "maxDrawdown": "0.00000000",
                "maxDailyDrawdown": "0.00000000",
                "turnover": "0.00000000",
                "commissionPaid": "0.00000000",
                "swapPaid": "0.00000000",
                "dailyPnL": {"1970-01-01": "18.00000000"},
                "tradeCount": 2,
                "equityCurve": [
                    {"timestamp": 0, "equity": "10000"},
                    {"timestamp": 1, "equity": "10020"}
                ]
            },
            "tradeSummary": {
                "fills": 2,
                "buyFills": 1,
                "sellFills": 1,
                "sideAttribution": "fill_side",
                "longEntryFills": 1,
                "shortEntryFills": 0,
                "longExitFills": 1,
                "shortExitFills": 0,
                "unknownPositionFills": 0,
                "positionAttribution": "strategy_position_transition",
                "buyRealized": "25.00000000",
                "sellRealized": "-5.00000000",
                "grossProfit": "25.00000000",
                "grossLoss": "-5.00000000",
                "rejections": 1,
                "rejectionRate": "0.33333333",
                "riskAuditedFills": 2,
                "minRiskUsd": "10.00",
                "avgRiskUsd": "15.00000000",
                "maxRiskUsd": "20.00",
                "tradedNotional": "31000.00000000",
                "maxFillNotional": "20000.00000000",
            },
            "perStrategy": {},
        },
        sort_keys=True,
    )
    + "\n",
    "equity_global.csv": "timestamp,equity\n0,10000\n1,10020\n",
    "pnl_components.csv": (
        "scope,strategy,date,tradeRealized,adjustment,dailyPnL\n"
        "global,,1970-01-01,20.00000000,-2.00000000,18.00000000\n"
    ),
    "trades.csv": (
        "timestamp,strategy,symbol,side,positionEffect,orderType,quantity,price,realized,netAccountRealized,"
        "grossAccountRealized,accountRealized,riskUsd,strategyPositionQtyBefore,"
        "strategyPositionQtyAfter,contractSize,fillNotional\n"
        "1,s1,XAUUSD,BUY,OPEN_LONG,Market,2,100.00,25.00,25.00,25.50,25.50,10.00,,2,100,20000.00\n"
        "2,s1,XAUUSD,SELL,REDUCE_LONG,Market,1,110.00,-5.00,-5.00,-4.50,-4.50,20.00,2,1,100,11000.00\n"
    ),
    "rejections.csv": "timestamp,reason,strategy,symbol\n3,max notional,s1,XAUUSD\n",
    "report.html": "<!doctype html><title>report</title>\n",
}
for name, content in files.items():
    (root / name).write_text(content, encoding="utf-8")

artifacts = []
for name in ["result.json", "equity_global.csv", "pnl_components.csv", "trades.csv", "rejections.csv", "report.html"]:
    data = (root / name).read_bytes()
    artifacts.append(
        {
            "path": name,
            "sha256": "sha256:" + hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
        }
    )
(root / "manifest.json").write_text(
    json.dumps(
        {
            "schema": "qkt-report-bundle-v1",
            "schemaVersion": 1,
            "selfHashIncluded": False,
            "generatedAt": "2026-06-25T00:00:00Z",
            "qktVersion": "test",
            "gitSha": "abc123",
            "artifacts": artifacts,
        },
        sort_keys=True,
    )
    + "\n",
    encoding="utf-8",
)
PY

"$ROOT/scripts/audit_qkt_report_bundle.py" "$TMP" --json | grep -q '"ok": true'

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
csv_path = root / "trades.csv"
csv_path.write_text(csv_path.read_text(encoding="utf-8").replace("25.00,25.00,25.50", "25.00,24.00,25.50"), encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "trades.csv":
        data = csv_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

if "$ROOT/scripts/audit_qkt_report_bundle.py" "$TMP" --json >"$TMP/net-realized-fail.json"; then
    echo "expected report bundle with mismatched net realized alias to fail" >&2
    exit 1
fi
grep -q 'realized alias' "$TMP/net-realized-fail.json"

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
csv_path = root / "trades.csv"
csv_path.write_text(csv_path.read_text(encoding="utf-8").replace("25.00,24.00,25.50", "25.00,25.00,25.50"), encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "trades.csv":
        data = csv_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
result_path = root / "result.json"
result = json.loads(result_path.read_text(encoding="utf-8"))
result["schemaVersion"] = 999
result_path.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "result.json":
        data = result_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

if "$ROOT/scripts/audit_qkt_report_bundle.py" "$TMP" --json >"$TMP/schema-fail.json"; then
    echo "expected report bundle with unsupported result schemaVersion to fail" >&2
    exit 1
fi
grep -q 'unsupported result.json schemaVersion' "$TMP/schema-fail.json"

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
result_path = root / "result.json"
result = json.loads(result_path.read_text(encoding="utf-8"))
result["schemaVersion"] = 1
result_path.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "result.json":
        data = result_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
result_path = root / "result.json"
result = json.loads(result_path.read_text(encoding="utf-8"))
result["global"]["totalPnL"] = "19.00000000"
result_path.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "result.json":
        data = result_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

if "$ROOT/scripts/audit_qkt_report_bundle.py" "$TMP" --json >"$TMP/total-pnl-fail.json"; then
    echo "expected report bundle with inconsistent total PnL to fail" >&2
    exit 1
fi
grep -q 'global.totalPnL mismatch' "$TMP/total-pnl-fail.json"

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
result_path = root / "result.json"
result = json.loads(result_path.read_text(encoding="utf-8"))
result["global"]["totalPnL"] = "20.00000000"
result_path.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "result.json":
        data = result_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
csv_path = root / "equity_global.csv"
csv_path.write_text(csv_path.read_text(encoding="utf-8").replace("10020", "10019"), encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "equity_global.csv":
        data = csv_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

if "$ROOT/scripts/audit_qkt_report_bundle.py" "$TMP" --json >"$TMP/equity-fail.json"; then
    echo "expected report bundle with mismatched equity curve to fail" >&2
    exit 1
fi
grep -q 'global equity row' "$TMP/equity-fail.json"

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
csv_path = root / "equity_global.csv"
csv_path.write_text(csv_path.read_text(encoding="utf-8").replace("10019", "10020"), encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "equity_global.csv":
        data = csv_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
csv_path = root / "pnl_components.csv"
csv_path.write_text(csv_path.read_text(encoding="utf-8").replace("20.00000000", "19.00000000"), encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "pnl_components.csv":
        data = csv_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

if "$ROOT/scripts/audit_qkt_report_bundle.py" "$TMP" --json >"$TMP/pnl-fail.json"; then
    echo "expected report bundle with mismatched pnl components to fail" >&2
    exit 1
fi
grep -q 'tradeRealized mismatch' "$TMP/pnl-fail.json"

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
csv_path = root / "pnl_components.csv"
csv_path.write_text(csv_path.read_text(encoding="utf-8").replace("19.00000000", "20.00000000"), encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "pnl_components.csv":
        data = csv_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
csv_path = root / "pnl_components.csv"
csv_path.write_text(
    csv_path.read_text(encoding="utf-8") + "strategy,ghost,1970-01-01,0.00000000,0.00000000,0.00000000\n",
    encoding="utf-8",
)
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "pnl_components.csv":
        data = csv_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

if "$ROOT/scripts/audit_qkt_report_bundle.py" "$TMP" --json >"$TMP/pnl-extra-fail.json"; then
    echo "expected report bundle with extra pnl component rows to fail" >&2
    exit 1
fi
grep -q 'unexpected component key' "$TMP/pnl-extra-fail.json"

python3 - "$TMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
csv_path = root / "pnl_components.csv"
lines = csv_path.read_text(encoding="utf-8").splitlines()
csv_path.write_text("\n".join(lines[:-1]) + "\n", encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for artifact in manifest["artifacts"]:
    if artifact["path"] == "pnl_components.csv":
        data = csv_path.read_bytes()
        artifact["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
        artifact["bytes"] = len(data)
manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
PY

python3 - "$TMP/trades.csv" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
path.write_text(path.read_text(encoding="utf-8").replace("20000.00", "20001.00"), encoding="utf-8")
PY

if "$ROOT/scripts/audit_qkt_report_bundle.py" "$TMP" --json >"$TMP/fail.json"; then
    echo "expected tampered report bundle to fail" >&2
    exit 1
fi
grep -Eq 'hash mismatch|fillNotional' "$TMP/fail.json"

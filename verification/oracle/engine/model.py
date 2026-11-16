"""Scenario and result types for the execution oracle. Decimal everywhere; no qkt imports."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Optional


def d(value) -> Decimal:
    return value if isinstance(value, Decimal) else Decimal(str(value))


@dataclass(frozen=True)
class Instrument:
    """One `instruments.yaml` entry (docs/how-to/backtest-data.md "Contract specs, commission, and swap")."""

    contract_size: Decimal
    volume_step: Decimal
    volume_min: Decimal
    point_size: Decimal
    digits: int
    commission_per_lot: Decimal = Decimal(0)
    slippage_points: int = 0
    spread_points: Optional[int] = None
    tick_size: Optional[Decimal] = None

    @property
    def grid(self) -> Decimal:
        """bracket.md:40-42: the price grid is the tick size where coarser than a point, else the point size."""
        return self.tick_size if self.tick_size is not None else self.point_size

    @staticmethod
    def parse(raw: dict) -> "Instrument":
        return Instrument(
            contract_size=d(raw["contractSize"]),
            volume_step=d(raw["volumeStep"]),
            volume_min=d(raw["volumeMin"]),
            point_size=d(raw["pointSize"]),
            digits=int(raw["digits"]),
            commission_per_lot=d(raw.get("commissionPerLot", 0)),
            slippage_points=int(raw.get("slippagePoints", 0)),
            spread_points=raw.get("spreadPoints"),
            tick_size=d(raw["tickSize"]) if raw.get("tickSize") is not None else None,
        )


@dataclass(frozen=True)
class Tick:
    ts: int
    bid: Decimal
    ask: Decimal

    @property
    def mid(self) -> Decimal:
        return (self.bid + self.ask) / 2


@dataclass(frozen=True)
class Level:
    """A bracket leg: exactly one of `by` (points), `pct` (percent) or `at` (absolute price)."""

    by: Optional[Decimal] = None
    pct: Optional[Decimal] = None
    at: Optional[Decimal] = None

    @staticmethod
    def parse(raw: dict) -> "Level":
        return Level(
            by=d(raw["by"]) if "by" in raw else None,
            pct=d(raw["pct"]) if "pct" in raw else None,
            at=d(raw["at"]) if "at" in raw else None,
        )


@dataclass(frozen=True)
class Exit:
    """`BRACKET { STOP_LOSS, TAKE_PROFIT }` (bracket.md) or `OCO { STOP AT, LIMIT AT }` (actions.md:127-136)."""

    kind: str  # "bracket" | "oco"
    stop: Level
    target: Level

    @staticmethod
    def parse(raw: Optional[dict]) -> Optional["Exit"]:
        if raw is None:
            return None
        if raw["kind"] == "oco":
            return Exit("oco", Level(at=d(raw["stop"])), Level(at=d(raw["limit"])))
        return Exit("bracket", Level.parse(raw["stop"]), Level.parse(raw["target"]))


@dataclass(frozen=True)
class OrderType:
    kind: str  # MARKET | LIMIT | STOP | STOP_LIMIT
    price: Optional[Decimal] = None  # LIMIT / STOP trigger price
    limit: Optional[Decimal] = None  # STOP_LIMIT resting limit price

    @staticmethod
    def parse(raw: Optional[dict]) -> "OrderType":
        if raw is None:
            return OrderType("MARKET")
        kind = raw["type"]
        if kind == "STOP_LIMIT":
            return OrderType(kind, d(raw["stop"]), d(raw["limit"]))
        return OrderType(kind, d(raw["price"]) if "price" in raw else None)


@dataclass(frozen=True)
class Sizing:
    """One SIZING form (sizing.md "The forms available today")."""

    form: str  # fixed | usd | pctEquity | pctBalance | pctRisk | riskUsd
    value: Decimal

    @staticmethod
    def parse(raw: dict) -> "Sizing":
        return Sizing(raw["form"], d(raw["value"]))


@dataclass(frozen=True)
class Action:
    """What one rule does when the bar whose close equals `bar_close` closes (edge-triggered)."""

    bar_close: Decimal
    kind: str  # entry | close | reduce
    side: Optional[str] = None
    order_type: OrderType = OrderType("MARKET")
    sizing: Optional[Sizing] = None
    exit: Optional[Exit] = None
    fraction: Optional[Decimal] = None  # reduce: fraction of POSITION
    requires: Optional[str] = None  # flat | long | short

    @staticmethod
    def parse(raw: dict) -> "Action":
        do = raw["do"]
        return Action(
            bar_close=d(raw["when"]["barClose"]),
            kind=do["kind"],
            side=do.get("side"),
            order_type=OrderType.parse(do.get("orderType")),
            sizing=Sizing.parse(do["sizing"]) if "sizing" in do else None,
            exit=Exit.parse(do.get("exit")),
            fraction=d(do["fraction"]) if "fraction" in do else None,
            requires=raw["when"].get("position"),
        )


@dataclass(frozen=True)
class Scenario:
    name: str
    proves: str
    dimensions: dict
    t0_ms: int
    timeframe_ms: int
    symbol: str
    instrument: Instrument
    broker: str  # paper | mt5-sim
    fill_mode: str  # ticks | bars-synthetic | bars-ticks
    position_mode: str  # hedging | netting
    starting_balance: Decimal
    ticks: list
    actions: list
    notes: list = field(default_factory=list)

    @staticmethod
    def load(path: Path) -> "Scenario":
        raw = json.loads(path.read_text())
        t0 = int(datetime.fromisoformat(raw["t0"].replace("Z", "+00:00")).astimezone(timezone.utc).timestamp() * 1000)
        tf_ms = int(raw["timeframeSec"]) * 1000
        ticks = [Tick(t0 + int(sec) * 1000, d(bid), d(ask)) for sec, bid, ask in raw["ticks"]]
        return Scenario(
            name=raw["name"],
            proves=raw["proves"],
            dimensions=raw["dimensions"],
            t0_ms=t0,
            timeframe_ms=tf_ms,
            symbol=raw["symbol"],
            instrument=Instrument.parse(raw["instrument"]),
            broker=raw["broker"],
            fill_mode=raw["fillMode"],
            position_mode=raw.get("positionMode", "hedging"),
            starting_balance=d(raw.get("startingBalance", "10000")),
            ticks=ticks,
            actions=[Action.parse(a) for a in raw["actions"]],
            notes=list(raw.get("notes", [])),
        )


@dataclass
class Fill:
    """One row of the expected trade tape."""

    ts: int
    side: str
    qty: Decimal
    price: Decimal
    realized: Decimal  # net of this fill's commission (config-schema.md:739-742)
    reason: str  # entry | stop | target | close
    order_type: str  # MARKET | LIMIT | STOP | STOP_LIMIT | CLOSE
    bar_start: Optional[int] = None  # synthetic bar path only: the bar the print belongs to
    stop: Optional[Decimal] = None  # entry rows: the protective levels armed by the fill
    target: Optional[Decimal] = None

    def to_json(self) -> dict:
        row = {
            "ts": self.ts,
            "side": self.side,
            "qty": str(self.qty),
            "price": str(self.price),
            "realized": str(self.realized),
            "reason": self.reason,
            "orderType": self.order_type,
        }
        if self.bar_start is not None:
            row["barStart"] = self.bar_start
        if self.stop is not None:
            row["stopLoss"] = str(self.stop)
            row["takeProfit"] = str(self.target)
        return row

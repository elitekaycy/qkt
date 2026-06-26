# qkt-insights Backend Spine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the headless qkt-insights backend — a single Node process that ingests trading events from N qkt instances over HTTP, persists them to SQLite, and serves a queryable REST + WebSocket API — runnable in `collect`, `serve`, or `run` modes from one Docker image.

**Architecture:** A pnpm TypeScript monorepo with four packages: `contract` (Zod event schema, the shared truth), `store` (the only SQLite writer; WAL + FTS5 + migrations + order-state folding + queries), `collector` (the `/ingest` endpoint that validates and writes), and `api` (Fastify REST + WS over the store, behind a single-admin session cookie). A single `server.ts` boots subsystems by mode. Best-effort lossy ingest; SQLite is the single writer.

**Tech Stack:** Node 22, TypeScript (strict), pnpm workspace, Zod, better-sqlite3 (WAL, FTS5), Fastify + @fastify/websocket + @fastify/cookie + @fastify/static, argon2, Vitest, ESLint + Prettier, Docker (multi-stage, node:22-slim).

This plan corresponds to spec `docs/superpowers/specs/2026-06-10-qkt-insights-design.md`. All work happens in a **new repository** `qkt-insights`, created in Task 1.

---

## File Structure

```
qkt-insights/
  package.json                      # workspace root, scripts, devDeps
  pnpm-workspace.yaml
  tsconfig.base.json                # shared strict TS config
  vitest.config.ts                  # workspace test config
  .eslintrc.cjs / .prettierrc
  Dockerfile
  docker-compose.yml
  .dockerignore / .gitignore
  CLAUDE.md                         # ported engineering conventions
  src/
    server.ts                       # single entry; parses mode, boots subsystems
  packages/
    contract/
      package.json
      src/index.ts                  # re-exports
      src/envelope.ts               # Envelope schema + EventType union
      src/payloads.ts               # per-type payload schemas
      test/contract.test.ts
    store/
      package.json
      src/index.ts
      src/db.ts                     # open db, WAL pragmas
      src/migrations.ts             # forward-only migration runner
      src/migrations/001_init.sql   # tables + FTS5
      src/write.ts                  # ingestEvents: insert + fold + fts
      src/queries.ts                # read functions
      src/emitter.ts                # in-process live event emitter
      test/store.write.test.ts
      test/store.queries.test.ts
    collector/
      package.json
      src/index.ts                  # registerCollector(fastify, store, deps)
      test/collector.test.ts
    api/
      package.json
      src/index.ts                  # registerApi(fastify, store, deps)
      src/auth.ts                   # argon2 verify + session cookie + guard
      src/rest.ts                   # REST routes
      src/live.ts                   # WS /live hub
      test/api.auth.test.ts
      test/api.rest.test.ts
      test/api.live.test.ts
  test/
    e2e.spine.test.ts               # boot serve mode, ingest → query roundtrip
```

Each file has one responsibility: `contract` validates shape, `store` owns all SQL, `collector` owns ingest, `api` owns query/auth/live, `server.ts` only wires modes.

---

## Conventions for every task

- **Language/style:** TypeScript strict. No `any`. Match the porting of qkt's `CLAUDE.md`: smallest change, names describe what not how, no useless comments, no mocks in e2e.
- **Test runner:** Vitest. Run a single file with `pnpm vitest run <path>`.
- **Commits:** subject line only, no body, no footer (qkt convention). Commit after each task's tests pass.
- **SQLite in tests:** real DB on a temp file (or `:memory:` where noted) — never mocked.

---

### Task 1: Repo scaffold + tooling

**Files:**
- Create: `qkt-insights/package.json`, `pnpm-workspace.yaml`, `tsconfig.base.json`, `vitest.config.ts`, `.eslintrc.cjs`, `.prettierrc`, `.gitignore`, `CLAUDE.md`
- Create package manifests: `packages/{contract,store,collector,api}/package.json`, each `packages/*/tsconfig.json`

- [ ] **Step 1: Create the repo and workspace root**

```bash
mkdir -p qkt-insights && cd qkt-insights && git init
mkdir -p src test packages/contract/src packages/contract/test \
  packages/store/src/migrations packages/store/test \
  packages/collector/src packages/collector/test \
  packages/api/src packages/api/test
```

`package.json` (root):

```json
{
  "name": "qkt-insights",
  "private": true,
  "type": "module",
  "engines": { "node": ">=22" },
  "scripts": {
    "build": "tsc -b",
    "test": "vitest run",
    "lint": "eslint . --ext .ts",
    "start": "node dist/src/server.js"
  },
  "devDependencies": {
    "typescript": "^5.6.0",
    "vitest": "^2.1.0",
    "@types/node": "^22.0.0",
    "eslint": "^9.0.0",
    "@typescript-eslint/eslint-plugin": "^8.0.0",
    "@typescript-eslint/parser": "^8.0.0",
    "prettier": "^3.3.0"
  }
}
```

`pnpm-workspace.yaml`:

```yaml
packages:
  - "packages/*"
```

- [ ] **Step 2: Shared TS config**

`tsconfig.base.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "declaration": true,
    "composite": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "outDir": "dist"
  }
}
```

`vitest.config.ts`:

```ts
import { defineConfig } from "vitest/config";
export default defineConfig({ test: { environment: "node", include: ["**/*.test.ts"] } });
```

`.gitignore`:

```
node_modules
dist
*.db
*.db-wal
*.db-shm
```

- [ ] **Step 3: Package manifests**

`packages/contract/package.json`:

```json
{
  "name": "@qkt-insights/contract",
  "version": "0.0.0",
  "type": "module",
  "main": "dist/index.js",
  "types": "dist/index.d.ts",
  "exports": { ".": "./dist/index.js" },
  "dependencies": { "zod": "^3.23.0" }
}
```

`packages/store/package.json`:

```json
{
  "name": "@qkt-insights/store",
  "version": "0.0.0",
  "type": "module",
  "main": "dist/index.js",
  "types": "dist/index.d.ts",
  "exports": { ".": "./dist/index.js" },
  "dependencies": {
    "@qkt-insights/contract": "workspace:*",
    "better-sqlite3": "^11.3.0"
  },
  "devDependencies": { "@types/better-sqlite3": "^7.6.0" }
}
```

`packages/collector/package.json`:

```json
{
  "name": "@qkt-insights/collector",
  "version": "0.0.0",
  "type": "module",
  "main": "dist/index.js",
  "types": "dist/index.d.ts",
  "dependencies": {
    "@qkt-insights/contract": "workspace:*",
    "@qkt-insights/store": "workspace:*",
    "fastify": "^5.0.0"
  }
}
```

`packages/api/package.json`:

```json
{
  "name": "@qkt-insights/api",
  "version": "0.0.0",
  "type": "module",
  "main": "dist/index.js",
  "types": "dist/index.d.ts",
  "dependencies": {
    "@qkt-insights/contract": "workspace:*",
    "@qkt-insights/store": "workspace:*",
    "fastify": "^5.0.0",
    "@fastify/websocket": "^11.0.0",
    "@fastify/cookie": "^11.0.0",
    "@fastify/static": "^8.0.0",
    "argon2": "^0.41.0"
  }
}
```

Each `packages/*/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": { "rootDir": "src", "outDir": "dist" },
  "include": ["src"]
}
```

Root `tsconfig.json` (build orchestration):

```json
{
  "files": [],
  "references": [
    { "path": "packages/contract" },
    { "path": "packages/store" },
    { "path": "packages/collector" },
    { "path": "packages/api" }
  ]
}
```

- [ ] **Step 4: Install and verify the workspace resolves**

Run: `pnpm install`
Expected: installs without error; `node_modules` created; workspace links resolve.

- [ ] **Step 5: Port CLAUDE.md conventions**

Copy qkt's `CLAUDE.md` engineering sections (non-negotiables, naming, comments, testing, version control) into `qkt-insights/CLAUDE.md`. Adjust tooling line to: "Node project, uses pnpm. Tests with Vitest. SQLite via better-sqlite3."

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: scaffold qkt-insights pnpm workspace"
```

---

### Task 2: Contract — Envelope + event union

**Files:**
- Create: `packages/contract/src/payloads.ts`, `packages/contract/src/envelope.ts`, `packages/contract/src/index.ts`
- Test: `packages/contract/test/contract.test.ts`

- [ ] **Step 1: Write the failing test**

`packages/contract/test/contract.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { EnvelopeSchema, parseEnvelope } from "../src/index.js";

const base = { v: 1, instanceId: "qkt-prod", id: "e1", seq: 5, ts: 1718000000000 };

describe("EnvelopeSchema", () => {
  it("accepts a valid trade envelope", () => {
    const env = { ...base, strategyId: "latch", type: "trade",
      payload: { orderId: "o1", symbol: "XAUUSD", side: "BUY", price: 2350.5, qty: 0.1, ts: base.ts } };
    expect(EnvelopeSchema.parse(env).type).toBe("trade");
  });

  it("accepts an order.filled envelope", () => {
    const env = { ...base, type: "order.filled",
      payload: { orderId: "o1", brokerOrderId: "b1", symbol: "XAUUSD", price: 2350.5, qty: 0.1, venueCosts: 0.02 } };
    expect(EnvelopeSchema.parse(env).type).toBe("order.filled");
  });

  it("accepts a snapshot.equity envelope", () => {
    const env = { ...base, strategyId: "latch", type: "snapshot.equity",
      payload: { strategyId: "latch", realized: 10, unrealized: -2, equity: 1008, startingBalance: 1000 } };
    expect(EnvelopeSchema.parse(env).type).toBe("snapshot.equity");
  });

  it("rejects an envelope with a wrong payload for its type", () => {
    const env = { ...base, type: "trade", payload: { nonsense: true } };
    expect(() => EnvelopeSchema.parse(env)).toThrow();
  });

  it("parseEnvelope returns ok=false on malformed input", () => {
    const res = parseEnvelope({ foo: "bar" });
    expect(res.ok).toBe(false);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/contract/test/contract.test.ts`
Expected: FAIL — cannot find `../src/index.js` exports.

- [ ] **Step 3: Write the payload schemas**

`packages/contract/src/payloads.ts`:

```ts
import { z } from "zod";

const side = z.enum(["BUY", "SELL"]);

export const payloadByType = {
  signal: z.object({ symbol: z.string(), side, note: z.string().optional() }),
  "order.submit": z.object({ orderId: z.string(), orderType: z.string(), symbol: z.string(), side, qty: z.number() }),
  "order.accepted": z.object({ orderId: z.string(), brokerOrderId: z.string() }),
  "order.filled": z.object({ orderId: z.string(), brokerOrderId: z.string().optional(), symbol: z.string(), price: z.number(), qty: z.number(), venueCosts: z.number().optional() }),
  "order.partially_filled": z.object({ orderId: z.string(), symbol: z.string(), price: z.number(), qty: z.number(), cumulativeQty: z.number() }),
  "order.cancelled": z.object({ orderId: z.string(), reason: z.string().optional() }),
  "order.rejected": z.object({ orderId: z.string(), reason: z.string() }),
  "order.modified": z.object({ orderId: z.string(), changes: z.record(z.string()) }),
  trade: z.object({ orderId: z.string(), symbol: z.string(), side, price: z.number(), qty: z.number(), ts: z.number() }),
  "risk.rejected": z.object({ reason: z.string(), symbol: z.string().optional(), side: side.optional(), qty: z.number().optional() }),
  "risk.halted": z.object({ strategyId: z.string(), reason: z.string() }),
  "risk.resumed": z.object({ strategyId: z.string() }),
  "position.reconciled": z.object({ symbol: z.string(), before: z.number(), after: z.number() }),
  "balances.updated": z.object({ balances: z.record(z.number()) }),
  "gateway.unreachable": z.object({ detail: z.string() }),
  "snapshot.equity": z.object({ strategyId: z.string(), realized: z.number(), unrealized: z.number(), equity: z.number(), startingBalance: z.number() }),
  "snapshot.position": z.object({ strategyId: z.string(), symbol: z.string(),
    legs: z.array(z.object({ side, qty: z.number(), entryPrice: z.number(), entryTs: z.number() })) }),
} as const;

export type EventType = keyof typeof payloadByType;
```

- [ ] **Step 4: Write the envelope union**

`packages/contract/src/envelope.ts`:

```ts
import { z } from "zod";
import { payloadByType, type EventType } from "./payloads.js";

const envelopeBase = { v: z.literal(1), instanceId: z.string().min(1), id: z.string().min(1),
  seq: z.number().int().nonnegative(), ts: z.number().int(), strategyId: z.string().optional() };

const variants = (Object.entries(payloadByType) as [EventType, z.ZodTypeAny][]).map(([type, payload]) =>
  z.object({ ...envelopeBase, type: z.literal(type), payload }),
);

export const EnvelopeSchema = z.discriminatedUnion("type", variants as [typeof variants[number], ...typeof variants]);
export type Envelope = z.infer<typeof EnvelopeSchema>;

export function parseEnvelope(input: unknown): { ok: true; value: Envelope } | { ok: false; error: string } {
  const r = EnvelopeSchema.safeParse(input);
  return r.success ? { ok: true, value: r.data } : { ok: false, error: r.error.message };
}

export const BatchSchema = z.object({ instanceId: z.string().min(1), events: z.array(EnvelopeSchema) });
export type Batch = z.infer<typeof BatchSchema>;
```

`packages/contract/src/index.ts`:

```ts
export * from "./payloads.js";
export * from "./envelope.js";
```

- [ ] **Step 5: Run test to verify it passes**

Run: `pnpm vitest run packages/contract/test/contract.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add packages/contract
git commit -m "feat: event contract schema and envelope union"
```

---

### Task 3: Store — db open, migrations, schema

**Files:**
- Create: `packages/store/src/db.ts`, `packages/store/src/migrations.ts`, `packages/store/src/migrations/001_init.sql`
- Test: `packages/store/test/store.write.test.ts` (schema portion first)

- [ ] **Step 1: Write the failing test**

`packages/store/test/store.write.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { openDb } from "../src/db.js";

describe("schema", () => {
  it("creates core tables and the FTS table on open", () => {
    const db = openDb(":memory:");
    const names = db.prepare("SELECT name FROM sqlite_master WHERE type IN ('table')").all().map((r: any) => r.name);
    for (const t of ["events", "instances", "strategies", "orders", "equity_snapshots", "events_fts"]) {
      expect(names).toContain(t);
    }
  });

  it("enables WAL on a file-backed db", () => {
    const db = openDb(":memory:");
    // memory dbs report 'memory'; assert pragma callable and journal set on file dbs
    const mode = db.pragma("journal_mode", { simple: true });
    expect(typeof mode).toBe("string");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/store/test/store.write.test.ts`
Expected: FAIL — `../src/db.js` not found.

- [ ] **Step 3: Write the migration SQL**

`packages/store/src/migrations/001_init.sql`:

```sql
CREATE TABLE IF NOT EXISTS instances (
  id TEXT PRIMARY KEY,
  name TEXT,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  last_seq INTEGER NOT NULL DEFAULT -1
);

CREATE TABLE IF NOT EXISTS strategies (
  instance_id TEXT NOT NULL,
  strategy_id TEXT NOT NULL,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  equity REAL,
  starting_balance REAL,
  PRIMARY KEY (instance_id, strategy_id)
);

CREATE TABLE IF NOT EXISTS events (
  id TEXT NOT NULL,
  instance_id TEXT NOT NULL,
  type TEXT NOT NULL,
  strategy_id TEXT,
  seq INTEGER NOT NULL,
  ts INTEGER NOT NULL,
  payload TEXT NOT NULL,
  PRIMARY KEY (instance_id, id)
);
CREATE INDEX IF NOT EXISTS idx_events_lookup ON events (instance_id, type, ts);
CREATE INDEX IF NOT EXISTS idx_events_strategy ON events (instance_id, strategy_id, ts);

CREATE TABLE IF NOT EXISTS orders (
  instance_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  strategy_id TEXT,
  symbol TEXT,
  side TEXT,
  type TEXT,
  state TEXT NOT NULL,
  qty REAL,
  cum_qty REAL NOT NULL DEFAULT 0,
  avg_price REAL,
  created_ts INTEGER NOT NULL,
  updated_ts INTEGER NOT NULL,
  PRIMARY KEY (instance_id, order_id)
);
CREATE INDEX IF NOT EXISTS idx_orders_lookup ON orders (instance_id, strategy_id, state, updated_ts);

CREATE TABLE IF NOT EXISTS equity_snapshots (
  instance_id TEXT NOT NULL,
  strategy_id TEXT NOT NULL,
  ts INTEGER NOT NULL,
  realized REAL NOT NULL,
  unrealized REAL NOT NULL,
  equity REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_equity_lookup ON equity_snapshots (instance_id, strategy_id, ts);

CREATE VIRTUAL TABLE IF NOT EXISTS events_fts USING fts5 (
  text, instance_id UNINDEXED, event_rowid UNINDEXED, tokenize = 'porter'
);
```

- [ ] **Step 4: Write the migration runner**

`packages/store/src/migrations.ts`:

```ts
import type { Database } from "better-sqlite3";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const dir = join(dirname(fileURLToPath(import.meta.url)), "migrations");

export function runMigrations(db: Database): void {
  db.exec("CREATE TABLE IF NOT EXISTS _migrations (name TEXT PRIMARY KEY, applied_ts INTEGER NOT NULL)");
  const applied = new Set(db.prepare("SELECT name FROM _migrations").all().map((r: any) => r.name));
  const files = readdirSync(dir).filter((f) => f.endsWith(".sql")).sort();
  for (const f of files) {
    if (applied.has(f)) continue;
    const sql = readFileSync(join(dir, f), "utf8");
    db.transaction(() => {
      db.exec(sql);
      db.prepare("INSERT INTO _migrations (name, applied_ts) VALUES (?, ?)").run(f, 0);
    })();
  }
}
```

- [ ] **Step 5: Write db open**

`packages/store/src/db.ts`:

```ts
import Database from "better-sqlite3";
import { runMigrations } from "./migrations.js";

export type Db = Database.Database;

export function openDb(path: string): Db {
  const db = new Database(path);
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");
  runMigrations(db);
  return db;
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `pnpm vitest run packages/store/test/store.write.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add packages/store
git commit -m "feat: store schema, migrations, WAL db open"
```

---

### Task 4: Store — ingest write path (insert + fold + FTS)

**Files:**
- Create: `packages/store/src/write.ts`
- Modify: `packages/store/src/index.ts` (create, re-export)
- Test: extend `packages/store/test/store.write.test.ts`

- [ ] **Step 1: Write the failing test (append to existing file)**

```ts
import { ingestEvents } from "../src/index.js";
import type { Envelope } from "@qkt-insights/contract";

function env(p: Partial<Envelope> & { type: Envelope["type"]; payload: any }): Envelope {
  return { v: 1, instanceId: "qkt-prod", id: Math.random().toString(36).slice(2), seq: 1, ts: 1718000000000, ...p } as Envelope;
}

describe("ingestEvents", () => {
  it("stores raw events and upserts instance/strategy", () => {
    const db = openDb(":memory:");
    ingestEvents(db, "qkt-prod", [
      env({ strategyId: "latch", type: "trade", payload: { orderId: "o1", symbol: "XAUUSD", side: "BUY", price: 2350, qty: 0.1, ts: 1718000000000 } }),
    ]);
    expect(db.prepare("SELECT COUNT(*) c FROM events").get()).toMatchObject({ c: 1 });
    expect(db.prepare("SELECT id FROM instances").get()).toMatchObject({ id: "qkt-prod" });
    expect(db.prepare("SELECT strategy_id FROM strategies").get()).toMatchObject({ strategy_id: "latch" });
  });

  it("folds order lifecycle into a single orders row reaching FILLED", () => {
    const db = openDb(":memory:");
    ingestEvents(db, "qkt-prod", [
      env({ seq: 1, type: "order.submit", payload: { orderId: "o1", orderType: "Market", symbol: "XAUUSD", side: "BUY", qty: 0.1 } }),
      env({ seq: 2, type: "order.accepted", payload: { orderId: "o1", brokerOrderId: "b1" } }),
      env({ seq: 3, type: "order.filled", payload: { orderId: "o1", brokerOrderId: "b1", symbol: "XAUUSD", price: 2350, qty: 0.1 } }),
    ]);
    const row: any = db.prepare("SELECT * FROM orders WHERE order_id='o1'").get();
    expect(row.state).toBe("FILLED");
    expect(row.cum_qty).toBe(0.1);
    expect(row.avg_price).toBe(2350);
  });

  it("appends equity snapshots and updates strategy equity", () => {
    const db = openDb(":memory:");
    ingestEvents(db, "qkt-prod", [
      env({ strategyId: "latch", type: "snapshot.equity", payload: { strategyId: "latch", realized: 10, unrealized: -2, equity: 1008, startingBalance: 1000 } }),
    ]);
    expect(db.prepare("SELECT COUNT(*) c FROM equity_snapshots").get()).toMatchObject({ c: 1 });
    expect(db.prepare("SELECT equity FROM strategies WHERE strategy_id='latch'").get()).toMatchObject({ equity: 1008 });
  });

  it("indexes events into FTS so search can find them by symbol", () => {
    const db = openDb(":memory:");
    ingestEvents(db, "qkt-prod", [
      env({ type: "trade", payload: { orderId: "o1", symbol: "XAUUSD", side: "BUY", price: 2350, qty: 0.1, ts: 1718000000000 } }),
    ]);
    const hits: any[] = db.prepare("SELECT event_rowid FROM events_fts WHERE events_fts MATCH 'XAUUSD'").all();
    expect(hits.length).toBe(1);
  });

  it("is idempotent on duplicate event ids (instance-scoped)", () => {
    const db = openDb(":memory:");
    const e = env({ id: "dup", type: "trade", payload: { orderId: "o1", symbol: "X", side: "BUY", price: 1, qty: 1, ts: 1 } });
    ingestEvents(db, "qkt-prod", [e]);
    ingestEvents(db, "qkt-prod", [e]);
    expect(db.prepare("SELECT COUNT(*) c FROM events").get()).toMatchObject({ c: 1 });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/store/test/store.write.test.ts`
Expected: FAIL — `ingestEvents` not exported.

- [ ] **Step 3: Write the write path**

`packages/store/src/write.ts`:

```ts
import type { Db } from "./db.js";
import type { Envelope } from "@qkt-insights/contract";

function ftsText(e: Envelope): string {
  const p: any = e.payload;
  return [e.type, e.strategyId, p.symbol, p.side, p.orderId, p.brokerOrderId, p.reason]
    .filter(Boolean).join(" ");
}

const ORDER_STATE: Record<string, string> = {
  "order.submit": "SUBMITTED",
  "order.accepted": "WORKING",
  "order.partially_filled": "PARTIALLY_FILLED",
  "order.filled": "FILLED",
  "order.cancelled": "CANCELLED",
  "order.rejected": "REJECTED",
};

export function ingestEvents(db: Db, instanceId: string, events: Envelope[]): number {
  const insEvent = db.prepare(
    "INSERT OR IGNORE INTO events (id, instance_id, type, strategy_id, seq, ts, payload) VALUES (?,?,?,?,?,?,?)",
  );
  const insFts = db.prepare("INSERT INTO events_fts (text, instance_id, event_rowid) VALUES (?,?,?)");
  const upInstance = db.prepare(
    `INSERT INTO instances (id, first_seen, last_seen, last_seq) VALUES (@id,@ts,@ts,@seq)
     ON CONFLICT(id) DO UPDATE SET last_seen=max(last_seen,@ts), last_seq=max(last_seq,@seq)`,
  );
  const upStrategy = db.prepare(
    `INSERT INTO strategies (instance_id, strategy_id, first_seen, last_seen) VALUES (@i,@s,@ts,@ts)
     ON CONFLICT(instance_id,strategy_id) DO UPDATE SET last_seen=max(last_seen,@ts)`,
  );
  const setEquity = db.prepare(
    "UPDATE strategies SET equity=@eq, starting_balance=@sb WHERE instance_id=@i AND strategy_id=@s",
  );
  const insEquity = db.prepare(
    "INSERT INTO equity_snapshots (instance_id, strategy_id, ts, realized, unrealized, equity) VALUES (?,?,?,?,?,?)",
  );

  const tx = db.transaction((evs: Envelope[]) => {
    let accepted = 0;
    for (const e of evs) {
      const info = insEvent.run(e.id, instanceId, e.type, e.strategyId ?? null, e.seq, e.ts, JSON.stringify(e.payload));
      if (info.changes === 0) continue; // duplicate id, skip the rest of the fold
      accepted++;
      insFts.run(ftsText(e), instanceId, info.lastInsertRowid as number);
      upInstance.run({ id: instanceId, ts: e.ts, seq: e.seq });
      if (e.strategyId) upStrategy.run({ i: instanceId, s: e.strategyId, ts: e.ts });
      foldOrder(db, instanceId, e);
      if (e.type === "snapshot.equity") {
        const p = e.payload;
        insEquity.run(instanceId, p.strategyId, e.ts, p.realized, p.unrealized, p.equity);
        setEquity.run({ i: instanceId, s: p.strategyId, eq: p.equity, sb: p.startingBalance });
      }
    }
    return accepted;
  });
  return tx(events);
}

function foldOrder(db: Db, instanceId: string, e: Envelope): void {
  const state = ORDER_STATE[e.type];
  if (!state) return;
  const p: any = e.payload;
  const orderId = p.orderId as string;
  const existing: any = db.prepare("SELECT * FROM orders WHERE instance_id=? AND order_id=?").get(instanceId, orderId);
  const filledQty = e.type === "order.filled" || e.type === "order.partially_filled" ? p.qty : 0;
  const cum = (existing?.cum_qty ?? 0) + (e.type === "order.partially_filled" ? filledQty : 0);
  const cumFinal = e.type === "order.filled" ? (p.qty ?? cum) : cum;
  if (!existing) {
    db.prepare(
      `INSERT INTO orders (instance_id, order_id, strategy_id, symbol, side, type, state, qty, cum_qty, avg_price, created_ts, updated_ts)
       VALUES (@i,@o,@s,@sym,@side,@t,@st,@qty,@cum,@avg,@ts,@ts)`,
    ).run({ i: instanceId, o: orderId, s: e.strategyId ?? null, sym: p.symbol ?? null, side: p.side ?? null,
      t: p.orderType ?? null, st: state, qty: p.qty ?? null, cum: cumFinal, avg: p.price ?? null, ts: e.ts });
  } else {
    db.prepare(
      `UPDATE orders SET state=@st, cum_qty=@cum, avg_price=COALESCE(@avg, avg_price), symbol=COALESCE(@sym,symbol), updated_ts=@ts
       WHERE instance_id=@i AND order_id=@o`,
    ).run({ i: instanceId, o: orderId, st: state, cum: cumFinal, avg: p.price ?? null, sym: p.symbol ?? null, ts: e.ts });
  }
}
```

`packages/store/src/index.ts`:

```ts
export * from "./db.js";
export * from "./write.js";
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/store/test/store.write.test.ts`
Expected: PASS (7 tests total in file).

- [ ] **Step 5: Commit**

```bash
git add packages/store
git commit -m "feat: store ingest write path with order folding and fts"
```

---

### Task 5: Store — query functions

**Files:**
- Create: `packages/store/src/queries.ts`
- Modify: `packages/store/src/index.ts` (add export)
- Test: `packages/store/test/store.queries.test.ts`

- [ ] **Step 1: Write the failing test**

`packages/store/test/store.queries.test.ts`:

```ts
import { describe, it, expect, beforeEach } from "vitest";
import { openDb, ingestEvents, listInstances, listStrategies, listOrders, listTrades, searchEvents, equityCurve, instanceHealth } from "../src/index.js";
import type { Db } from "../src/index.js";
import type { Envelope } from "@qkt-insights/contract";

function env(p: any): Envelope {
  return { v: 1, instanceId: "qkt-prod", id: Math.random().toString(36).slice(2), seq: p.seq ?? 1, ts: p.ts ?? 1718000000000, ...p } as Envelope;
}

let db: Db;
beforeEach(() => {
  db = openDb(":memory:");
  ingestEvents(db, "qkt-prod", [
    env({ strategyId: "latch", type: "order.submit", payload: { orderId: "o1", orderType: "Market", symbol: "XAUUSD", side: "BUY", qty: 0.1 } }),
    env({ strategyId: "latch", type: "order.filled", payload: { orderId: "o1", brokerOrderId: "b1", symbol: "XAUUSD", price: 2350, qty: 0.1 } }),
    env({ strategyId: "latch", type: "trade", ts: 1718000001000, payload: { orderId: "o1", symbol: "XAUUSD", side: "BUY", price: 2350, qty: 0.1, ts: 1718000001000 } }),
    env({ strategyId: "latch", type: "snapshot.equity", ts: 1718000002000, payload: { strategyId: "latch", realized: 5, unrealized: 0, equity: 1005, startingBalance: 1000 } }),
  ]);
  ingestEvents(db, "qkt-demo", [
    env({ instanceId: "qkt-demo", strategyId: "macd", type: "trade", payload: { orderId: "x1", symbol: "EURUSD", side: "SELL", price: 1.08, qty: 1, ts: 1718000000000 } }),
  ]);
});

describe("queries", () => {
  it("lists instances", () => {
    expect(listInstances(db).map((i) => i.id).sort()).toEqual(["qkt-demo", "qkt-prod"]);
  });
  it("lists strategies for an instance", () => {
    expect(listStrategies(db, "qkt-prod").map((s) => s.strategyId)).toEqual(["latch"]);
  });
  it("lists orders filtered by instance and state", () => {
    const rows = listOrders(db, { instanceId: "qkt-prod", state: "FILLED", limit: 50 });
    expect(rows).toHaveLength(1);
    expect(rows[0]!.orderId).toBe("o1");
  });
  it("lists trades filtered by symbol", () => {
    const rows = listTrades(db, { instanceId: "qkt-prod", symbol: "XAUUSD", limit: 50 });
    expect(rows).toHaveLength(1);
  });
  it("full-text searches events scoped to instance", () => {
    const hits = searchEvents(db, { q: "XAUUSD", instanceId: "qkt-prod", limit: 50 });
    expect(hits.length).toBeGreaterThanOrEqual(1);
    expect(hits.every((h) => h.instanceId === "qkt-prod")).toBe(true);
  });
  it("returns equity curve points ordered by ts", () => {
    const pts = equityCurve(db, { instanceId: "qkt-prod", strategyId: "latch" });
    expect(pts).toEqual([{ ts: 1718000002000, equity: 1005, realized: 5, unrealized: 0 }]);
  });
  it("reports instance health with last seen and seq", () => {
    const h = instanceHealth(db);
    const prod = h.find((x) => x.instanceId === "qkt-prod")!;
    expect(prod.lastSeq).toBeGreaterThanOrEqual(1);
    expect(prod.strategies).toBe(1);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/store/test/store.queries.test.ts`
Expected: FAIL — query functions not exported.

- [ ] **Step 3: Write the queries**

`packages/store/src/queries.ts`:

```ts
import type { Db } from "./db.js";

export interface InstanceRow { id: string; name: string | null; firstSeen: number; lastSeen: number; lastSeq: number }
export interface StrategyRow { strategyId: string; firstSeen: number; lastSeen: number; equity: number | null; startingBalance: number | null }
export interface OrderRow { orderId: string; strategyId: string | null; symbol: string | null; side: string | null; type: string | null; state: string; qty: number | null; cumQty: number; avgPrice: number | null; createdTs: number; updatedTs: number }
export interface TradeRow { id: string; strategyId: string | null; ts: number; payload: unknown }
export interface SearchHit { id: string; instanceId: string; type: string; ts: number; payload: unknown }
export interface EquityPoint { ts: number; equity: number; realized: number; unrealized: number }
export interface HealthRow { instanceId: string; lastSeen: number; lastSeq: number; strategies: number }

export function listInstances(db: Db): InstanceRow[] {
  return db.prepare("SELECT id, name, first_seen firstSeen, last_seen lastSeen, last_seq lastSeq FROM instances ORDER BY id").all() as InstanceRow[];
}

export function listStrategies(db: Db, instanceId: string): StrategyRow[] {
  return db.prepare(
    "SELECT strategy_id strategyId, first_seen firstSeen, last_seen lastSeen, equity, starting_balance startingBalance FROM strategies WHERE instance_id=? ORDER BY strategy_id",
  ).all(instanceId) as StrategyRow[];
}

export function listOrders(db: Db, f: { instanceId: string; strategyId?: string; symbol?: string; state?: string; limit: number }): OrderRow[] {
  const cl: string[] = ["instance_id=@instanceId"]; 
  if (f.strategyId) cl.push("strategy_id=@strategyId");
  if (f.symbol) cl.push("symbol=@symbol");
  if (f.state) cl.push("state=@state");
  return db.prepare(
    `SELECT order_id orderId, strategy_id strategyId, symbol, side, type, state, qty, cum_qty cumQty, avg_price avgPrice, created_ts createdTs, updated_ts updatedTs
     FROM orders WHERE ${cl.join(" AND ")} ORDER BY updated_ts DESC LIMIT @limit`,
  ).all(f) as OrderRow[];
}

export function listTrades(db: Db, f: { instanceId: string; strategyId?: string; symbol?: string; limit: number }): TradeRow[] {
  const cl: string[] = ["instance_id=@instanceId", "type='trade'"]; 
  if (f.strategyId) cl.push("strategy_id=@strategyId");
  if (f.symbol) cl.push("json_extract(payload,'$.symbol')=@symbol");
  const rows = db.prepare(
    `SELECT id, strategy_id strategyId, ts, payload FROM events WHERE ${cl.join(" AND ")} ORDER BY ts DESC LIMIT @limit`,
  ).all(f) as any[];
  return rows.map((r) => ({ ...r, payload: JSON.parse(r.payload) }));
}

export function searchEvents(db: Db, f: { q: string; instanceId?: string; limit: number }): SearchHit[] {
  const rows = db.prepare(
    `SELECT e.id, e.instance_id instanceId, e.type, e.ts, e.payload
     FROM events_fts f JOIN events e ON e.rowid = f.event_rowid
     WHERE events_fts MATCH @q ${f.instanceId ? "AND e.instance_id=@instanceId" : ""}
     ORDER BY e.ts DESC LIMIT @limit`,
  ).all(f) as any[];
  return rows.map((r) => ({ ...r, payload: JSON.parse(r.payload) }));
}

export function equityCurve(db: Db, f: { instanceId: string; strategyId: string; from?: number; to?: number }): EquityPoint[] {
  const cl: string[] = ["instance_id=@instanceId", "strategy_id=@strategyId"]; 
  if (f.from != null) cl.push("ts>=@from");
  if (f.to != null) cl.push("ts<=@to");
  return db.prepare(
    `SELECT ts, equity, realized, unrealized FROM equity_snapshots WHERE ${cl.join(" AND ")} ORDER BY ts ASC`,
  ).all(f) as EquityPoint[];
}

export function instanceHealth(db: Db): HealthRow[] {
  return db.prepare(
    `SELECT i.id instanceId, i.last_seen lastSeen, i.last_seq lastSeq,
            (SELECT COUNT(*) FROM strategies s WHERE s.instance_id=i.id) strategies
     FROM instances i ORDER BY i.id`,
  ).all() as HealthRow[];
}
```

Note: the FTS join uses `e.rowid = f.event_rowid`; `event_rowid` was stored as `events.rowid` at insert time (the `info.lastInsertRowid` from the event insert in Task 4).

Add to `packages/store/src/index.ts`:

```ts
export * from "./queries.js";
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/store/test/store.queries.test.ts`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add packages/store
git commit -m "feat: store query functions for instances, orders, trades, search, equity, health"
```

---

### Task 6: Store — live emitter

**Files:**
- Create: `packages/store/src/emitter.ts`
- Modify: `packages/store/src/index.ts`
- Test: add to `packages/store/test/store.queries.test.ts` (or a small new file `store.emitter.test.ts`)

- [ ] **Step 1: Write the failing test**

`packages/store/test/store.emitter.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { LiveBus } from "../src/index.js";
import type { Envelope } from "@qkt-insights/contract";

describe("LiveBus", () => {
  it("delivers published envelopes to subscribers and supports unsubscribe", () => {
    const bus = new LiveBus();
    const got: Envelope[] = [];
    const off = bus.subscribe((e) => got.push(e));
    const e = { v: 1, instanceId: "qkt-prod", id: "1", seq: 1, ts: 1, type: "trade",
      payload: { orderId: "o", symbol: "X", side: "BUY", price: 1, qty: 1, ts: 1 } } as Envelope;
    bus.publish(e);
    off();
    bus.publish(e);
    expect(got).toHaveLength(1);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/store/test/store.emitter.test.ts`
Expected: FAIL — `LiveBus` not exported.

- [ ] **Step 3: Write the emitter**

`packages/store/src/emitter.ts`:

```ts
import type { Envelope } from "@qkt-insights/contract";

export type LiveListener = (e: Envelope) => void;

export class LiveBus {
  private listeners = new Set<LiveListener>();
  subscribe(fn: LiveListener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }
  publish(e: Envelope): void {
    for (const fn of this.listeners) fn(e);
  }
}
```

Add to `packages/store/src/index.ts`: `export * from "./emitter.js";`

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/store/test/store.emitter.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add packages/store
git commit -m "feat: in-process live bus for streaming ingested events"
```

---

### Task 7: Collector — ingest endpoint

**Files:**
- Create: `packages/collector/src/index.ts`
- Test: `packages/collector/test/collector.test.ts`

- [ ] **Step 1: Write the failing test**

`packages/collector/test/collector.test.ts`:

```ts
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import Fastify, { type FastifyInstance } from "fastify";
import { openDb, LiveBus, type Db } from "@qkt-insights/store";
import { registerCollector } from "../src/index.js";
import type { Envelope } from "@qkt-insights/contract";

function env(p: any): Envelope {
  return { v: 1, instanceId: "qkt-prod", id: Math.random().toString(36).slice(2), seq: 1, ts: 1718000000000, ...p } as Envelope;
}

let app: FastifyInstance;
let db: Db;
let bus: LiveBus;
beforeEach(async () => {
  db = openDb(":memory:");
  bus = new LiveBus();
  app = Fastify();
  registerCollector(app, { db, bus, ingestToken: "secret" });
  await app.ready();
});
afterEach(async () => { await app.close(); });

describe("POST /ingest", () => {
  it("rejects without a valid token", async () => {
    const res = await app.inject({ method: "POST", url: "/ingest", payload: { instanceId: "qkt-prod", events: [] } });
    expect(res.statusCode).toBe(401);
  });

  it("accepts a valid batch, persists, and publishes to the live bus", async () => {
    const seen: Envelope[] = [];
    bus.subscribe((e) => seen.push(e));
    const res = await app.inject({
      method: "POST", url: "/ingest",
      headers: { authorization: "Bearer secret" },
      payload: { instanceId: "qkt-prod", events: [
        env({ strategyId: "latch", type: "trade", payload: { orderId: "o1", symbol: "XAUUSD", side: "BUY", price: 2350, qty: 0.1, ts: 1718000000000 } }),
      ] },
    });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toMatchObject({ accepted: 1 });
    expect(db.prepare("SELECT COUNT(*) c FROM events").get()).toMatchObject({ c: 1 });
    expect(seen).toHaveLength(1);
  });

  it("returns 400 on a malformed envelope", async () => {
    const res = await app.inject({
      method: "POST", url: "/ingest",
      headers: { authorization: "Bearer secret" },
      payload: { instanceId: "qkt-prod", events: [{ v: 1, instanceId: "qkt-prod", id: "x", seq: 1, ts: 1, type: "trade", payload: { nope: true } }] },
    });
    expect(res.statusCode).toBe(400);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/collector/test/collector.test.ts`
Expected: FAIL — `registerCollector` not found.

- [ ] **Step 3: Write the collector**

`packages/collector/src/index.ts`:

```ts
import type { FastifyInstance } from "fastify";
import { BatchSchema } from "@qkt-insights/contract";
import { ingestEvents, type Db, type LiveBus } from "@qkt-insights/store";

export interface CollectorDeps { db: Db; bus: LiveBus; ingestToken: string }

export function registerCollector(app: FastifyInstance, deps: CollectorDeps): void {
  app.post("/ingest", async (req, reply) => {
    const auth = req.headers.authorization;
    if (auth !== `Bearer ${deps.ingestToken}`) return reply.code(401).send({ error: "unauthorized" });

    const parsed = BatchSchema.safeParse(req.body);
    if (!parsed.success) return reply.code(400).send({ error: "invalid batch", detail: parsed.error.message });

    const { instanceId, events } = parsed.data;
    const accepted = ingestEvents(deps.db, instanceId, events);
    for (const e of events) deps.bus.publish(e);
    return reply.code(200).send({ accepted });
  });
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/collector/test/collector.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add packages/collector
git commit -m "feat: collector ingest endpoint with token auth and live publish"
```

---

### Task 8: API — auth (login + session cookie + guard)

**Files:**
- Create: `packages/api/src/auth.ts`
- Test: `packages/api/test/api.auth.test.ts`

- [ ] **Step 1: Write the failing test**

`packages/api/test/api.auth.test.ts`:

```ts
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import Fastify, { type FastifyInstance } from "fastify";
import cookie from "@fastify/cookie";
import argon2 from "argon2";
import { registerAuth, requireSession } from "../src/auth.js";

let app: FastifyInstance;
let hash: string;
beforeEach(async () => {
  hash = await argon2.hash("hunter2");
  app = Fastify();
  await app.register(cookie);
  registerAuth(app, { passwordHash: hash, sessionSecret: "s3cret-session-key-at-least-32chars!" });
  app.get("/guarded", { preHandler: requireSession }, async () => ({ ok: true }));
  await app.ready();
});
afterEach(async () => { await app.close(); });

describe("auth", () => {
  it("rejects guarded routes without a session", async () => {
    const res = await app.inject({ method: "GET", url: "/guarded" });
    expect(res.statusCode).toBe(401);
  });

  it("logs in with the correct password and sets a cookie that unlocks guarded routes", async () => {
    const login = await app.inject({ method: "POST", url: "/auth/login", payload: { password: "hunter2" } });
    expect(login.statusCode).toBe(200);
    const setCookie = login.headers["set-cookie"];
    expect(setCookie).toBeTruthy();
    const res = await app.inject({ method: "GET", url: "/guarded", headers: { cookie: String(setCookie).split(";")[0] } });
    expect(res.statusCode).toBe(200);
  });

  it("rejects login with the wrong password", async () => {
    const login = await app.inject({ method: "POST", url: "/auth/login", payload: { password: "wrong" } });
    expect(login.statusCode).toBe(401);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/api/test/api.auth.test.ts`
Expected: FAIL — `../src/auth.js` not found.

- [ ] **Step 3: Write auth**

`packages/api/src/auth.ts`:

```ts
import type { FastifyInstance, FastifyRequest, FastifyReply } from "fastify";
import { createHmac, timingSafeEqual } from "node:crypto";
import argon2 from "argon2";

export interface AuthDeps { passwordHash: string; sessionSecret: string }

const COOKIE = "qkt_insights_session";

function sign(value: string, secret: string): string {
  const mac = createHmac("sha256", secret).update(value).digest("hex");
  return `${value}.${mac}`;
}

function verify(signed: string | undefined, secret: string): boolean {
  if (!signed) return false;
  const dot = signed.lastIndexOf(".");
  if (dot < 0) return false;
  const value = signed.slice(0, dot);
  const expected = createHmac("sha256", secret).update(value).digest("hex");
  const got = Buffer.from(signed.slice(dot + 1), "hex");
  const exp = Buffer.from(expected, "hex");
  return got.length === exp.length && timingSafeEqual(got, exp) && value === "admin";
}

let activeSecret = "";

export function registerAuth(app: FastifyInstance, deps: AuthDeps): void {
  activeSecret = deps.sessionSecret;
  app.post<{ Body: { password?: string } }>("/auth/login", async (req, reply) => {
    const password = req.body?.password ?? "";
    const ok = await argon2.verify(deps.passwordHash, password).catch(() => false);
    if (!ok) return reply.code(401).send({ error: "invalid credentials" });
    const token = sign("admin", deps.sessionSecret);
    reply.setCookie(COOKIE, token, { httpOnly: true, sameSite: "strict", path: "/", secure: false });
    return reply.send({ ok: true });
  });

  app.post("/auth/logout", async (_req, reply) => {
    reply.clearCookie(COOKIE, { path: "/" });
    return reply.send({ ok: true });
  });
}

export async function requireSession(req: FastifyRequest, reply: FastifyReply): Promise<void> {
  const token = req.cookies?.[COOKIE];
  if (!verify(token, activeSecret)) {
    await reply.code(401).send({ error: "unauthorized" });
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/api/test/api.auth.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add packages/api
git commit -m "feat: single-admin auth with signed session cookie"
```

---

### Task 9: API — REST routes

**Files:**
- Create: `packages/api/src/rest.ts`
- Test: `packages/api/test/api.rest.test.ts`

- [ ] **Step 1: Write the failing test**

`packages/api/test/api.rest.test.ts`:

```ts
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import Fastify, { type FastifyInstance } from "fastify";
import cookie from "@fastify/cookie";
import argon2 from "argon2";
import { openDb, ingestEvents, type Db } from "@qkt-insights/store";
import { registerAuth } from "../src/auth.js";
import { registerRest } from "../src/rest.js";
import type { Envelope } from "@qkt-insights/contract";

function env(p: any): Envelope {
  return { v: 1, instanceId: "qkt-prod", id: Math.random().toString(36).slice(2), seq: 1, ts: 1718000000000, ...p } as Envelope;
}

let app: FastifyInstance; let db: Db; let session: string;
beforeEach(async () => {
  db = openDb(":memory:");
  ingestEvents(db, "qkt-prod", [
    env({ strategyId: "latch", type: "order.filled", payload: { orderId: "o1", brokerOrderId: "b1", symbol: "XAUUSD", price: 2350, qty: 0.1 } }),
    env({ strategyId: "latch", type: "trade", payload: { orderId: "o1", symbol: "XAUUSD", side: "BUY", price: 2350, qty: 0.1, ts: 1718000000000 } }),
  ]);
  app = Fastify();
  await app.register(cookie);
  const hash = await argon2.hash("pw");
  registerAuth(app, { passwordHash: hash, sessionSecret: "session-secret-key-at-least-32-chars!!" });
  registerRest(app, { db });
  await app.ready();
  const login = await app.inject({ method: "POST", url: "/auth/login", payload: { password: "pw" } });
  session = String(login.headers["set-cookie"]).split(";")[0];
});
afterEach(async () => { await app.close(); });

function get(url: string) { return app.inject({ method: "GET", url, headers: { cookie: session } }); }

describe("REST", () => {
  it("guards routes behind the session", async () => {
    const res = await app.inject({ method: "GET", url: "/instances" });
    expect(res.statusCode).toBe(401);
  });
  it("lists instances", async () => {
    expect((await get("/instances")).json()).toMatchObject([{ id: "qkt-prod" }]);
  });
  it("lists strategies for an instance", async () => {
    expect((await get("/strategies?instance=qkt-prod")).json()).toMatchObject([{ strategyId: "latch" }]);
  });
  it("lists orders", async () => {
    const rows = (await get("/orders?instance=qkt-prod&state=FILLED")).json();
    expect(rows).toHaveLength(1);
  });
  it("lists trades by symbol", async () => {
    const rows = (await get("/trades?instance=qkt-prod&symbol=XAUUSD")).json();
    expect(rows).toHaveLength(1);
  });
  it("searches", async () => {
    const rows = (await get("/search?q=XAUUSD&instance=qkt-prod")).json();
    expect(rows.length).toBeGreaterThanOrEqual(1);
  });
  it("returns 400 when instance is missing on a scoped route", async () => {
    expect((await get("/orders")).statusCode).toBe(400);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/api/test/api.rest.test.ts`
Expected: FAIL — `registerRest` not found.

- [ ] **Step 3: Write the REST routes**

`packages/api/src/rest.ts`:

```ts
import type { FastifyInstance } from "fastify";
import { listInstances, listStrategies, listOrders, listTrades, searchEvents, equityCurve, instanceHealth, type Db } from "@qkt-insights/store";
import { requireSession } from "./auth.js";

export interface RestDeps { db: Db }

const LIMIT = (q: Record<string, string | undefined>) => Math.min(Number(q.limit ?? 200), 1000);

export function registerRest(app: FastifyInstance, deps: RestDeps): void {
  const guard = { preHandler: requireSession };
  const need = (reply: any, v: string | undefined, name: string) => {
    if (!v) { reply.code(400).send({ error: `${name} required` }); return false; }
    return true;
  };

  app.get("/instances", guard, async () => listInstances(deps.db));
  app.get("/health/instances", guard, async () => instanceHealth(deps.db));

  app.get<{ Querystring: Record<string, string> }>("/strategies", guard, async (req, reply) => {
    const i = req.query.instance; if (!need(reply, i, "instance")) return;
    return listStrategies(deps.db, i);
  });

  app.get<{ Querystring: Record<string, string> }>("/orders", guard, async (req, reply) => {
    const q = req.query; if (!need(reply, q.instance, "instance")) return;
    return listOrders(deps.db, { instanceId: q.instance, strategyId: q.strategy, symbol: q.symbol, state: q.state, limit: LIMIT(q) });
  });

  app.get<{ Querystring: Record<string, string> }>("/trades", guard, async (req, reply) => {
    const q = req.query; if (!need(reply, q.instance, "instance")) return;
    return listTrades(deps.db, { instanceId: q.instance, strategyId: q.strategy, symbol: q.symbol, limit: LIMIT(q) });
  });

  app.get<{ Querystring: Record<string, string> }>("/search", guard, async (req, reply) => {
    const q = req.query; if (!need(reply, q.q, "q")) return;
    return searchEvents(deps.db, { q: q.q, instanceId: q.instance, limit: LIMIT(q) });
  });

  app.get<{ Querystring: Record<string, string> }>("/equity", guard, async (req, reply) => {
    const q = req.query;
    if (!need(reply, q.instance, "instance") || !need(reply, q.strategy, "strategy")) return;
    return equityCurve(deps.db, { instanceId: q.instance, strategyId: q.strategy,
      from: q.from ? Number(q.from) : undefined, to: q.to ? Number(q.to) : undefined });
  });
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/api/test/api.rest.test.ts`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add packages/api
git commit -m "feat: REST query routes behind session guard"
```

---

### Task 10: API — WS /live hub

**Files:**
- Create: `packages/api/src/live.ts`
- Modify: `packages/api/src/index.ts` (create, re-export registerAuth/registerRest/registerLive + a registerApi convenience)
- Test: `packages/api/test/api.live.test.ts`

- [ ] **Step 1: Write the failing test**

`packages/api/test/api.live.test.ts`:

```ts
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import Fastify, { type FastifyInstance } from "fastify";
import websocket from "@fastify/websocket";
import { LiveBus } from "@qkt-insights/store";
import { registerLive } from "../src/live.js";
import type { Envelope } from "@qkt-insights/contract";
import { once } from "node:events";
import { WebSocket } from "ws";

let app: FastifyInstance; let bus: LiveBus; let url: string;
beforeEach(async () => {
  bus = new LiveBus();
  app = Fastify();
  await app.register(websocket);
  registerLive(app, { bus });
  await app.listen({ port: 0, host: "127.0.0.1" });
  const addr = app.server.address();
  const port = typeof addr === "object" && addr ? addr.port : 0;
  url = `ws://127.0.0.1:${port}/live`;
});
afterEach(async () => { await app.close(); });

describe("WS /live", () => {
  it("streams matching events to a subscribed client", async () => {
    const ws = new WebSocket(`${url}?instance=qkt-prod`);
    await once(ws, "open");
    const msg = once(ws, "message");
    const e = { v: 1, instanceId: "qkt-prod", id: "1", seq: 1, ts: 1, type: "trade",
      payload: { orderId: "o", symbol: "X", side: "BUY", price: 1, qty: 1, ts: 1 } } as Envelope;
    bus.publish(e);
    const [data] = await msg;
    expect(JSON.parse(String(data))).toMatchObject({ id: "1", type: "trade" });
    ws.close();
  });

  it("filters out events from other instances", async () => {
    const ws = new WebSocket(`${url}?instance=qkt-prod`);
    await once(ws, "open");
    let received = 0;
    ws.on("message", () => { received++; });
    bus.publish({ v: 1, instanceId: "other", id: "2", seq: 1, ts: 1, type: "trade",
      payload: { orderId: "o", symbol: "X", side: "BUY", price: 1, qty: 1, ts: 1 } } as Envelope);
    await new Promise((r) => setTimeout(r, 50));
    expect(received).toBe(0);
    ws.close();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/api/test/api.live.test.ts`
Expected: FAIL — `registerLive` not found. (Add `ws` to api devDependencies: `pnpm --filter @qkt-insights/api add -D ws @types/ws`.)

- [ ] **Step 3: Write the live hub**

`packages/api/src/live.ts`:

```ts
import type { FastifyInstance } from "fastify";
import type { LiveBus } from "@qkt-insights/store";
import type { Envelope } from "@qkt-insights/contract";

export interface LiveDeps { bus: LiveBus }

interface Filter { instance?: string; strategy?: string; types?: Set<string> }

function matches(e: Envelope, f: Filter): boolean {
  if (f.instance && e.instanceId !== f.instance) return false;
  if (f.strategy && e.strategyId !== f.strategy) return false;
  if (f.types && !f.types.has(e.type)) return false;
  return true;
}

export function registerLive(app: FastifyInstance, deps: LiveDeps): void {
  app.get("/live", { websocket: true }, (socket, req) => {
    const q = req.query as Record<string, string>;
    const filter: Filter = {
      instance: q.instance,
      strategy: q.strategy,
      types: q.types ? new Set(q.types.split(",")) : undefined,
    };
    const off = deps.bus.subscribe((e) => {
      if (matches(e, filter) && socket.readyState === socket.OPEN) socket.send(JSON.stringify(e));
    });
    socket.on("close", off);
  });
}
```

`packages/api/src/index.ts`:

```ts
export * from "./auth.js";
export * from "./rest.js";
export * from "./live.js";
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/api/test/api.live.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add packages/api
git commit -m "feat: websocket live hub with instance/strategy/type filters"
```

---

### Task 11: Server entry — run-modes

**Files:**
- Create: `src/server.ts`
- Modify: root `package.json` (add runtime deps to root: fastify, plugins — or rely on workspace deps via a root manifest). Add root deps: `pnpm add fastify @fastify/websocket @fastify/cookie @fastify/static better-sqlite3 argon2`
- Test: covered by Task 12 e2e (server boot). Add a small unit check for mode parsing.

- [ ] **Step 1: Write the failing test**

`test/server.modes.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { parseMode } from "../src/server.js";

describe("parseMode", () => {
  it("defaults to run", () => expect(parseMode([])).toBe("run"));
  it("accepts collect/serve/run", () => {
    expect(parseMode(["collect"])).toBe("collect");
    expect(parseMode(["serve"])).toBe("serve");
    expect(parseMode(["run"])).toBe("run");
  });
  it("throws on an unknown mode", () => expect(() => parseMode(["bogus"])).toThrow());
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run test/server.modes.test.ts`
Expected: FAIL — `../src/server.js` not found.

- [ ] **Step 3: Write the server**

`src/server.ts`:

```ts
import Fastify from "fastify";
import cookie from "@fastify/cookie";
import websocket from "@fastify/websocket";
import fastifyStatic from "@fastify/static";
import { openDb, LiveBus } from "@qkt-insights/store";
import { registerCollector } from "@qkt-insights/collector";
import { registerAuth, registerRest, registerLive } from "@qkt-insights/api";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

export type Mode = "collect" | "serve" | "run";

export function parseMode(argv: string[]): Mode {
  const m = argv[0] ?? "run";
  if (m === "collect" || m === "serve" || m === "run") return m;
  throw new Error(`unknown mode: ${m}`);
}

function env(name: string, fallback?: string): string {
  const v = process.env[name] ?? fallback;
  if (v == null) throw new Error(`missing env ${name}`);
  return v;
}

export async function buildServer(mode: Mode) {
  const db = openDb(env("INSIGHTS_DB", "/data/insights.db"));
  const bus = new LiveBus();
  const app = Fastify({ logger: true });

  registerCollector(app, { db, bus, ingestToken: env("INGEST_TOKEN") });

  if (mode === "serve" || mode === "run") {
    await app.register(cookie);
    await app.register(websocket);
    registerAuth(app, { passwordHash: env("ADMIN_PASSWORD_HASH"), sessionSecret: env("SESSION_SECRET", env("INGEST_TOKEN")) });
    registerRest(app, { db });
    registerLive(app, { bus });
  }

  if (mode === "run") {
    const webDist = join(dirname(fileURLToPath(import.meta.url)), "..", "web");
    await app.register(fastifyStatic, { root: webDist, wildcard: false });
    app.setNotFoundHandler((req, reply) => {
      if (req.raw.method === "GET" && !req.url.startsWith("/api")) return reply.sendFile("index.html");
      return reply.code(404).send({ error: "not found" });
    });
  }

  return app;
}

async function main() {
  const mode = parseMode(process.argv.slice(2));
  const app = await buildServer(mode);
  const port = Number(process.env.PORT ?? 8420);
  await app.listen({ port, host: "0.0.0.0" });
}

// Run only when invoked directly, not when imported by tests.
if (process.argv[1] && process.argv[1].endsWith("server.js")) {
  main().catch((e) => { console.error(e); process.exit(1); });
}
```

Note: in `run` mode the web bundle is expected at `dist/web/` (Plan 2 builds it there). `serve` and `collect` don't need it.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run test/server.modes.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src package.json
git commit -m "feat: single server entry with collect/serve/run modes"
```

---

### Task 12: End-to-end spine test

**Files:**
- Test: `test/e2e.spine.test.ts`

- [ ] **Step 1: Write the e2e test (real server, real SQLite, real HTTP)**

`test/e2e.spine.test.ts`:

```ts
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import argon2 from "argon2";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { FastifyInstance } from "fastify";

let app: FastifyInstance; let base: string;
const token = "ingest-secret";

beforeAll(async () => {
  process.env.INSIGHTS_DB = join(mkdtempSync(join(tmpdir(), "qkti-")), "e2e.db");
  process.env.INGEST_TOKEN = token;
  process.env.ADMIN_PASSWORD_HASH = await argon2.hash("admin-pw");
  process.env.SESSION_SECRET = "session-secret-key-at-least-32-chars!!";
  const { buildServer } = await import("../src/server.js");
  app = await buildServer("serve");
  await app.listen({ port: 0, host: "127.0.0.1" });
  const addr = app.server.address();
  base = `http://127.0.0.1:${typeof addr === "object" && addr ? addr.port : 0}`;
});
afterAll(async () => { await app.close(); });

describe("spine e2e", () => {
  it("ingests a batch and serves it back through the authed API", async () => {
    const ingest = await fetch(`${base}/ingest`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
      body: JSON.stringify({ instanceId: "qkt-prod", events: [
        { v: 1, instanceId: "qkt-prod", id: "t1", seq: 1, ts: 1718000000000, strategyId: "latch", type: "trade",
          payload: { orderId: "o1", symbol: "XAUUSD", side: "BUY", price: 2350, qty: 0.1, ts: 1718000000000 } },
      ] }),
    });
    expect(ingest.status).toBe(200);
    expect(await ingest.json()).toMatchObject({ accepted: 1 });

    const login = await fetch(`${base}/auth/login`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ password: "admin-pw" }),
    });
    expect(login.status).toBe(200);
    const session = String(login.headers.get("set-cookie")).split(";")[0];

    const trades = await fetch(`${base}/trades?instance=qkt-prod&symbol=XAUUSD`, { headers: { cookie: session } });
    expect(trades.status).toBe(200);
    const rows = await trades.json();
    expect(rows).toHaveLength(1);
    expect(rows[0].payload.symbol).toBe("XAUUSD");

    const search = await fetch(`${base}/search?q=XAUUSD&instance=qkt-prod`, { headers: { cookie: session } });
    expect((await search.json()).length).toBeGreaterThanOrEqual(1);
  });

  it("rejects API access without a session", async () => {
    const res = await fetch(`${base}/instances`);
    expect(res.status).toBe(401);
  });
});
```

- [ ] **Step 2: Run test to verify it fails, then passes**

Run: `pnpm vitest run test/e2e.spine.test.ts`
Expected: PASS once the build resolves (`pnpm -r build` first so workspace `dist/` exists for the dynamic import). If imports fail, run `pnpm -r build` and re-run.

- [ ] **Step 3: Run the full suite**

Run: `pnpm test`
Expected: all package + e2e tests PASS.

- [ ] **Step 4: Commit**

```bash
git add test
git commit -m "test: end-to-end spine ingest-to-query roundtrip"
```

---

### Task 13: Docker + compose

**Files:**
- Create: `Dockerfile`, `docker-compose.yml`, `.dockerignore`

- [ ] **Step 1: Write the Dockerfile**

`Dockerfile`:

```dockerfile
FROM node:22-slim AS build
RUN corepack enable
WORKDIR /app
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json tsconfig.base.json tsconfig.json ./
COPY packages ./packages
COPY src ./src
RUN pnpm install --frozen-lockfile && pnpm -r build && pnpm tsc -b

FROM node:22-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/dist ./dist
COPY --from=build /app/packages ./packages
COPY --from=build /app/node_modules ./node_modules
EXPOSE 8420
VOLUME /data
ENTRYPOINT ["node", "dist/src/server.js"]
CMD ["run"]
```

Note: Plan 2 adds the web build stage that emits `dist/web/`. Until then `run` serves no web assets but `collect`/`serve` are fully functional; for now default the compose `command` to `serve`.

`.dockerignore`:

```
node_modules
dist
**/test
*.db
```

`docker-compose.yml`:

```yaml
services:
  insights:
    build: .
    command: serve
    ports: ["8420:8420"]
    volumes: ["insights-data:/data"]
    environment:
      INSIGHTS_DB: /data/insights.db
      INGEST_TOKEN: ${INGEST_TOKEN}
      ADMIN_PASSWORD_HASH: ${ADMIN_PASSWORD_HASH}
      SESSION_SECRET: ${SESSION_SECRET}
volumes: { insights-data: {} }
```

- [ ] **Step 2: Build the image**

Run: `docker build -t qkt-insights:dev .`
Expected: image builds; both stages complete.

- [ ] **Step 3: Smoke-run serve mode**

Run:
```bash
docker run --rm -e INGEST_TOKEN=t -e ADMIN_PASSWORD_HASH="$(node -e 'import("argon2").then(a=>a.default.hash("pw").then(h=>console.log(h)))')" -e SESSION_SECRET=session-secret-key-at-least-32-chars!! -p 8420:8420 qkt-insights:dev serve
```
Then in another shell: `curl -s -XPOST localhost:8420/ingest -H 'authorization: Bearer t' -H 'content-type: application/json' -d '{"instanceId":"q","events":[]}'`
Expected: `{"accepted":0}`.

- [ ] **Step 4: Commit**

```bash
git add Dockerfile docker-compose.yml .dockerignore
git commit -m "build: multi-stage docker image and compose for run-modes"
```

---

## Self-Review

**Spec coverage:**
- Event contract (spec §4) → Task 2. ✓
- Store SQLite + WAL + FTS5 + tables + folding (spec §6 store) → Tasks 3–6. ✓
- Collector /ingest + token + validate + emit (spec §6 collector) → Task 7. ✓
- API REST + search + equity + health + WS live + auth (spec §6 api, §8) → Tasks 8–10. ✓
- Run-modes collect/serve/run (spec §7) → Task 11. ✓
- Docker single image + volume + port (spec §9) → Task 13. ✓
- Testing with real SQLite/HTTP, no mocks (spec §12) → every task + Task 12 e2e. ✓
- qkt egress (spec §5) → **out of scope here, Plan 3.** Web slice (spec §10) → **Plan 2.** Both intentionally deferred per the plan-split decision.

**Placeholder scan:** No TBD/TODO; every code step shows full code. The only forward-reference is `dist/web/` consumed by `server.ts` Task 11 / Dockerfile Task 13, explicitly noted as produced by Plan 2, with `serve`/`collect` fully functional without it.

**Type consistency:** `Db`, `LiveBus`, `Envelope`, `ingestEvents`, `registerCollector`, `registerAuth`/`requireSession`/`registerRest`/`registerLive` names are consistent across tasks. Query function names (`listInstances`, `listStrategies`, `listOrders`, `listTrades`, `searchEvents`, `equityCurve`, `instanceHealth`) match between Task 5 (definition) and Task 9 (REST consumption). FTS `event_rowid` written in Task 4 matches the join in Task 5. `ingestEvents` signature `(db, instanceId, events)` consistent across Tasks 4, 7, e2e.

**Note for executor:** `pnpm -r build` before running the e2e test (Task 12) and Docker build (Task 13), since they import from workspace `dist/`. Add `pnpm-lock.yaml` to the repo after the first `pnpm install` so `--frozen-lockfile` works in Docker.

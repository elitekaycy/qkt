#!/usr/bin/env bash
set -euo pipefail

# Prepare the live portfolio validation matrix: every book SHAPE crossed with every RISK profile.
#
# Nothing in scripts/live-validation covered portfolios before this. The existing matrices
# (risk-rejection, stateful-risk) each deploy a single strategy, so none of them exercise what only
# appears once N children share one account: per-child attribution and magic assignment, capital
# split by WEIGHT, book-level exposure caps that count every child at once, two children on one
# symbol, and children on opposing sides of the same symbol.
#
# Shapes are grouped by how ordinary they are:
#   normal-*   what a book usually looks like: a few children on their own symbols
#   average-*  what forge actually promotes: five or more children, uneven weights, unallocated cash
#   edge-*     the cases that break naive implementations: one symbol shared, opposing sides, a
#              silent child, a single-child book
#
# Risk profiles run from "no book risk at all" (the control that proves a later divergence came from
# the profile) through book exposure caps to per-order caps that must reject deterministically.
#
# Generated books are parsed with the real `qkt parse`, so a case that cannot compile never reaches
# the runner. No credential is accepted as an argument or written to any artifact: the config
# resolves ${QKT_BROKER_API_KEY} at execution time, exactly as the sibling preparers do.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: prepare-portfolio-matrix.sh --output DIR --id ID --gateway-url http://127.0.0.1:PORT \
  --expected-login N --expected-server NAME --expected-balance DECIMAL \
  --expected-leverage N --magic-base N [--cli PATH] [--shapes LIST] [--profiles LIST]

Prepares one case directory per (shape, risk profile) pair under DIR/cases/.
--shapes and --profiles take comma-separated subsets; default is the full cross.
EOF
}

fail() { printf 'prepare-portfolio-matrix: %s\n' "$1" >&2; exit 1; }

output=""
matrix_id=""
gateway_url=""
expected_login=""
expected_server=""
expected_balance=""
expected_leverage=""
magic_base=""
cli="$repo_root/build/install/qkt/bin/qkt"
shapes_arg=""
profiles_arg=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) output="${2:-}"; shift 2 ;;
        --id) matrix_id="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --expected-balance) expected_balance="${2:-}"; shift 2 ;;
        --expected-leverage) expected_leverage="${2:-}"; shift 2 ;;
        --magic-base) magic_base="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --shapes) shapes_arg="${2:-}"; shift 2 ;;
        --profiles) profiles_arg="${2:-}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$output" ] || fail "--output is required"
[ -n "$matrix_id" ] || fail "--id is required"
[ -n "$gateway_url" ] || fail "--gateway-url is required"
[ -n "$expected_login" ] || fail "--expected-login is required"
[ -n "$expected_server" ] || fail "--expected-server is required"
[ -n "$expected_balance" ] || fail "--expected-balance is required"
[ -n "$expected_leverage" ] || fail "--expected-leverage is required"
[ -n "$magic_base" ] || fail "--magic-base is required"
[ -x "$cli" ] || fail "qkt CLI not executable at $cli (run ./gradlew installDist)"

# The harness is local-demo only. A remote or tunnelled gateway is refused outright, matching the
# sibling preparers: these cases are allowed to arm real orders, so the target must be unambiguous.
case "$gateway_url" in
    http://127.0.0.1:*|http://localhost:*) : ;;
    *) fail "--gateway-url must be a loopback address, got $gateway_url" ;;
esac
[ -e "$output" ] && fail "--output $output already exists; use a fresh directory"

mkdir -m 700 -p "$output/cases"

# ------------------------------------------------------------------ symbols
# The DSL names the base symbol; the `extends: exness` broker profile appends the account's `m`
# suffix (EXNESS:EURUSD -> EURUSDm), so these are the same instruments the gateway lists.
SYM1="EXNESS:EURUSD"
SYM2="EXNESS:GBPUSD"
SYM3="EXNESS:AUDUSD"

all_shapes="normal-two-symbols normal-three-uneven average-five-children average-cash-weight edge-same-symbol edge-opposing-sides edge-single-child edge-silent-child"
all_profiles="no-book-risk book-gross-cap book-concentration-cap per-order-qty-cap margin-floor"

selected_shapes="$all_shapes"
selected_profiles="$all_profiles"
[ -n "$shapes_arg" ] && selected_shapes="$(printf '%s' "$shapes_arg" | tr ',' ' ')"
[ -n "$profiles_arg" ] && selected_profiles="$(printf '%s' "$profiles_arg" | tr ',' ' ')"

# ------------------------------------------------------------------ config

write_config() {
    local case_dir="$1" magic="$2" profile="$3"
    local max_qty="0.5" collar="100" margin_floor="0" book_block=""

    case "$profile" in
        no-book-risk)
            # Control: every cap wide open and no book_risk section at all. A book must behave
            # identically here or nothing measured under the other profiles can be attributed.
            book_block=""
            ;;
        book-gross-cap)
            book_block=$(cat <<YAML

book_risk:
  capital: "$expected_balance"
  limits:
    max_gross_exposure: "0.02"
  allocation:
    method: FIXED
    max_leverage: "1"
YAML
)
            ;;
        book-concentration-cap)
            book_block=$(cat <<YAML

book_risk:
  capital: "$expected_balance"
  limits:
    max_symbol_concentration: "0.01"
  allocation:
    method: FIXED
    max_leverage: "1"
YAML
)
            ;;
        per-order-qty-cap)
            # Deliberately below the children's 0.01 lot, so every entry must be refused before it
            # reaches the venue. This is the profile that proves refusals happen live, not just in
            # the backtest.
            max_qty="0.005"
            ;;
        margin-floor)
            # MarginFloor approves every entry while margin level is 0 (a flat account) by design,
            # and at 1000:1 leverage one 0.01-lot position already puts the level in the millions of
            # percent -- the first version of this profile used 100000% and could never refuse
            # anything. 1e9% binds as soon as any position is open, so later entries are refused and
            # the refusal arithmetic can be checked against the engine's own reason.
            margin_floor="1000000000"
            ;;
        *) fail "unknown risk profile: $profile" ;;
    esac

    mkdir -m 700 -p "$case_dir/strategies/book" "$case_dir/data" "$case_dir/state" \
        "$case_dir/logs" "$case_dir/journal" "$case_dir/evidence"

    cat > "$case_dir/qkt.config.yaml" <<EOF
source: local
data_root: "/work/data"
starting_balance: "$expected_balance"
log_level: info

runtime:
  mode: dev

account:
  currency: USD

brokers:
  exness:
    type: mt5
    extends: exness
    gateway_url: $gateway_url
    api_key: \${QKT_BROKER_API_KEY}
    magic: $magic
    server_time_zone: Etc/UTC
    expected_account_login: $expected_login
    expected_account_server: $expected_server
    expected_trade_mode: demo
    expected_account_currency: USD
    tick_poll_interval_ms: 100
    poll_interval_ms: 1000
    http_timeout_ms: 5000
    retry_attempts: 3

risk:
  max_daily_loss: "999999999"
  max_order_qty: "$max_qty"
  max_order_notional: "100000000"
  price_collar_pct: "$collar"
  margin_floor_pct: "$margin_floor"
  measured_usage_hours: "0"
  measured_usage_max_qty: "1"
  max_round_trips_10m: 1000
  max_broker_rejections_1m: 1000
  max_drawdown_pct: "100"
  max_daily_drawdown_pct: "100"
  live_equity_basis: venue
$book_block

state:
  enabled: true
  async: true

insights:
  enabled: false
EOF
}

# ------------------------------------------------------------------ DSL builders

# A child that enters once while flat and holds. Entries are what the risk caps act on, so every
# shape below is built from this and its short twin.
write_long_child() {
    local dir="$1" name="$2" symbol="$3"
    cat > "$dir/$name.qkt" <<EOF
STRATEGY $name VERSION 1
SYMBOLS
    x = $symbol EVERY 1m
RULES
    WHEN x.close > 0 AND POSITION.x = 0
    THEN BUY x SIZING 0.01
EOF
}

write_short_child() {
    local dir="$1" name="$2" symbol="$3"
    cat > "$dir/$name.qkt" <<EOF
STRATEGY $name VERSION 1
SYMBOLS
    x = $symbol EVERY 1m
RULES
    WHEN x.close > 0 AND POSITION.x = 0
    THEN SELL x SIZING 0.01
EOF
}

# A child whose entry condition no real quote satisfies. Present so a book can be proved to keep
# running, and keep its siblings trading, when one member never fires.
write_silent_child() {
    local dir="$1" name="$2" symbol="$3"
    cat > "$dir/$name.qkt" <<EOF
STRATEGY $name VERSION 1
SYMBOLS
    x = $symbol EVERY 1m
RULES
    WHEN x.close > 1000000 AND POSITION.x = 0
    THEN BUY x SIZING 0.01
EOF
}

# ------------------------------------------------------------------ shapes

# Emits the portfolio file and its children; echoes the expected child count.
write_shape() {
    local case_dir="$1" shape="$2"
    local dir="$case_dir/strategies/book"
    local book="$dir/book.qkt"

    case "$shape" in
        normal-two-symbols)
            write_long_child "$dir" "pf_a" "$SYM1"
            write_long_child "$dir" "pf_b" "$SYM2"
            cat > "$book" <<EOF
PORTFOLIO pf_normal_two VERSION 1 CAPITAL $expected_balance
SYMBOLS
    mkt = $SYM1 EVERY 1m
IMPORT 'pf_a.qkt' AS a
IMPORT 'pf_b.qkt' AS b
RULES
    RUN a WEIGHT 0.5
    RUN b WEIGHT 0.5
EOF
            echo 2 ;;
        normal-three-uneven)
            write_long_child "$dir" "pf_a" "$SYM1"
            write_long_child "$dir" "pf_b" "$SYM2"
            write_long_child "$dir" "pf_c" "$SYM3"
            cat > "$book" <<EOF
PORTFOLIO pf_uneven_three VERSION 1 CAPITAL $expected_balance
SYMBOLS
    mkt = $SYM1 EVERY 1m
IMPORT 'pf_a.qkt' AS a
IMPORT 'pf_b.qkt' AS b
IMPORT 'pf_c.qkt' AS c
RULES
    RUN a WEIGHT 0.6
    RUN b WEIGHT 0.3
    RUN c WEIGHT 0.1
EOF
            echo 3 ;;
        average-five-children)
            write_long_child "$dir" "pf_a" "$SYM1"
            write_long_child "$dir" "pf_b" "$SYM2"
            write_long_child "$dir" "pf_c" "$SYM3"
            write_long_child "$dir" "pf_d" "$SYM1"
            write_long_child "$dir" "pf_e" "$SYM2"
            cat > "$book" <<EOF
PORTFOLIO pf_five VERSION 1 CAPITAL $expected_balance
SYMBOLS
    mkt = $SYM1 EVERY 1m
IMPORT 'pf_a.qkt' AS a
IMPORT 'pf_b.qkt' AS b
IMPORT 'pf_c.qkt' AS c
IMPORT 'pf_d.qkt' AS d
IMPORT 'pf_e.qkt' AS e
RULES
    RUN a WEIGHT 0.2
    RUN b WEIGHT 0.2
    RUN c WEIGHT 0.2
    RUN d WEIGHT 0.2
    RUN e WEIGHT 0.2
EOF
            echo 5 ;;
        average-cash-weight)
            # Weights sum to 0.5: half the declared capital is deliberately never allocated, so the
            # per-child capital must be half of an equal split, not a rescaled full book.
            write_long_child "$dir" "pf_a" "$SYM1"
            write_long_child "$dir" "pf_b" "$SYM2"
            cat > "$book" <<EOF
PORTFOLIO pf_cash VERSION 1 CAPITAL $expected_balance
SYMBOLS
    mkt = $SYM1 EVERY 1m
IMPORT 'pf_a.qkt' AS a
IMPORT 'pf_b.qkt' AS b
RULES
    RUN a WEIGHT 0.25
    RUN b WEIGHT 0.25
EOF
            echo 2 ;;
        edge-same-symbol)
            # Two children on ONE instrument. Each must own its own position: one child's fill must
            # not satisfy the other's POSITION test, and the venue must attribute both by magic.
            write_long_child "$dir" "pf_a" "$SYM1"
            write_long_child "$dir" "pf_b" "$SYM1"
            cat > "$book" <<EOF
PORTFOLIO pf_same_symbol VERSION 1 CAPITAL $expected_balance
SYMBOLS
    mkt = $SYM1 EVERY 1m
IMPORT 'pf_a.qkt' AS a
IMPORT 'pf_b.qkt' AS b
RULES
    RUN a WEIGHT 0.5
    RUN b WEIGHT 0.5
EOF
            echo 2 ;;
        edge-opposing-sides)
            # A long and a short child on the same instrument. On the hedging demo account these
            # must stack as two tickets rather than net to nothing.
            write_long_child "$dir" "pf_a" "$SYM1"
            write_short_child "$dir" "pf_b" "$SYM1"
            cat > "$book" <<EOF
PORTFOLIO pf_opposing VERSION 1 CAPITAL $expected_balance
SYMBOLS
    mkt = $SYM1 EVERY 1m
IMPORT 'pf_a.qkt' AS a
IMPORT 'pf_b.qkt' AS b
RULES
    RUN a WEIGHT 0.5
    RUN b WEIGHT 0.5
EOF
            echo 2 ;;
        edge-single-child)
            write_long_child "$dir" "pf_a" "$SYM1"
            cat > "$book" <<EOF
PORTFOLIO pf_single VERSION 1 CAPITAL $expected_balance
SYMBOLS
    mkt = $SYM1 EVERY 1m
IMPORT 'pf_a.qkt' AS a
RULES
    RUN a WEIGHT 1.0
EOF
            echo 1 ;;
        edge-silent-child)
            write_long_child "$dir" "pf_a" "$SYM1"
            write_silent_child "$dir" "pf_b" "$SYM2"
            cat > "$book" <<EOF
PORTFOLIO pf_silent VERSION 1 CAPITAL $expected_balance
SYMBOLS
    mkt = $SYM1 EVERY 1m
IMPORT 'pf_a.qkt' AS a
IMPORT 'pf_b.qkt' AS b
RULES
    RUN a WEIGHT 0.5
    RUN b WEIGHT 0.5
EOF
            echo 2 ;;
        *) fail "unknown shape: $shape" ;;
    esac
}

# ------------------------------------------------------------------ contract

write_case_contract() {
    local case_dir="$1" case_id="$2" shape="$3" profile="$4" children="$5" magic="$6"
    # Whether entries are expected to reach the venue at all under this profile.
    # How entries should resolve under this profile. Book caps are "cap-dependent": whether a given
    # child gets in depends on live prices and on which sibling reached the book first, so the
    # runner judges those arithmetically from the engine's own refusal lines instead of from a
    # pre-baked yes/no. (The first contract said book-cap entries would reach the venue; a 0.01-lot
    # EURUSD position is ~1,163 of notional against a 999 concentration limit, so the engine was
    # right to refuse and the contract was wrong.)
    local entries_reach_venue=true entry_policy=open
    case "$profile" in
        per-order-qty-cap) entries_reach_venue=false; entry_policy=refuse ;;
        book-gross-cap|book-concentration-cap) entry_policy=cap-dependent ;;
        margin-floor) entry_policy=floor-dependent ;;
    esac
    # The silent child never fires, so one fewer child is expected to attempt an entry.
    local attempting="$children"
    [ "$shape" = "edge-silent-child" ] && attempting=$((children - 1))

    jq -n \
        --arg caseId "$case_id" \
        --arg shape "$shape" \
        --arg profile "$profile" \
        --argjson children "$children" \
        --argjson attempting "$attempting" \
        --argjson magic "$magic" \
        --argjson entriesReachVenue "$entries_reach_venue" \
        --arg entryPolicy "$entry_policy" \
        '{
          schema: "qkt-live-portfolio-case-v1",
          caseId: $caseId,
          shape: $shape,
          riskProfile: $profile,
          entryPolicy: $entryPolicy,
          children: $children,
          childrenAttemptingEntry: $attempting,
          magicBase: $magic,
          required: {
            portfolioParses: true,
            childrenDeployed: $children,
            distinctChildMagics: $children,
            entriesReachVenue: $entriesReachVenue,
            riskRejectionsExpected: (if $entriesReachVenue then false else true end),
            accountFlatAtEnd: true,
            unattributedVenuePositions: 0
          }
        }' > "$case_dir/expected.json"
}

# ------------------------------------------------------------------ generate

count=0
for shape in $selected_shapes; do
    for profile in $selected_profiles; do
        case_id="$shape--$profile"
        case_dir="$output/cases/$case_id"
        magic=$((magic_base + count * 100))
        write_config "$case_dir" "$magic" "$profile"
        children="$(write_shape "$case_dir" "$shape")"
        write_case_contract "$case_dir" "$case_id" "$shape" "$profile" "$children" "$magic"
        # The real parser is the gate: a book that cannot compile never reaches the runner, and
        # parsing the portfolio parses every imported child with it.
        "$cli" parse "$case_dir/strategies/book/book.qkt" >/dev/null \
            || fail "generated book failed to parse: $case_id"
        count=$((count + 1))
    done
done

jq -n \
    --arg id "$matrix_id" \
    --arg gateway "$gateway_url" \
    --argjson cases "$count" \
    --arg shapes "$selected_shapes" \
    --arg profiles "$selected_profiles" \
    '{
      schema: "qkt-live-portfolio-matrix-v1",
      id: $id,
      gatewayUrl: $gateway,
      cases: $cases,
      shapes: ($shapes | split(" ")),
      riskProfiles: ($profiles | split(" "))
    }' > "$output/matrix.json"

find "$output" -type f -exec sha256sum {} + | sed "s|$output/||" | sort -k2 > "$output/sources.sha256"

printf 'prepared %d portfolio cases under %s\n' "$count" "$output/cases"
printf 'shapes:   %s\n' "$selected_shapes"
printf 'profiles: %s\n' "$selected_profiles"

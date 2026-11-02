# A strategy's deals in an account-wide venue history (`qkt bot history --json`). A closing deal carries no
# comment, so ownership goes by position ticket: every ticket whose opening deal carries one of the strategy's
# order-id prefixes (the venue truncates comments, hence the two-way prefix match), then every deal on it.
# The engine names DSL-managed orders `dsl-<strategy>-` and a session's plain orders `ORD-<strategy>-`.
def owned($prefixes):
    ([.[] | select(.entry == "IN") | select((.comment // "") as $c | ($c | length) > 4 and
        ($prefixes | any(. as $p | ($c | startswith($p)) or ($p | startswith($c))))) | .positionTicket] | unique)
        as $tickets |
    [.[] | select(.positionTicket as $t | $tickets | index($t))];

def deal_net($prefixes): owned($prefixes) | map((.profit // 0) + (.commission // 0) + (.swap // 0) + (.fee // 0)) | add // 0;

def closing_deals($prefixes): owned($prefixes) | map(select(.entry != "IN")) | length;

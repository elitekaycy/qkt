# Shared by compare-golden-replay.sh and tests/scripts/replay-entry-match-test.sh.

def near($a; $b): (($a | tonumber) - ($b | tonumber) | fabs) < 0.000000001;

# A BY, PCT or RR target is resolved from the fill price, so an attach venue does not send it with
# the entry: it sets it by modifying the position at fill. The live request then carries no target,
# and the target itself is checked against the fill by the protection comparison. An absolute AT
# target is still sent with the entry and must equal the replay intent's.
def relative_target($intent): ($intent.takeProfitAst.type // "") as $t | ($t == "By" or $t == "Pct" or $t == "Rr");

def sent_target_matches($intent; $request):
    if relative_target($intent) then
        ($request.takeProfitPrice == null or $request.takeProfitPrice == "null")
    else
        near($intent.takeProfit; $request.takeProfitPrice)
    end;

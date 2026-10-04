# Perpetual funding soak on Deribit

Holds 50 SOL_USDC-PERPETUAL for 50 minutes across Deribit's 08:00 UTC daily settlement, where Deribit
realizes the funding it accrued, then closes it. Start it between 07:15 and 07:45 UTC on a flat Deribit
testnet account behind a gateway running the `deribit` adapter. The case turns qkt's measured-usage window
off (`config:`), a demo-only setting, so the funding is large enough to read. It passes when qkt's realized
P&L equals the venue's deals net less the funding Deribit realized, to the last digit, and a replay of the
gateway's bars makes the same fills. Run it as the paper soak, with `--expected-login` the API key's client
id and `--budget-seconds 4200`.

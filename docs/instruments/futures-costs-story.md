# The Nine Tolls: a futures trader's first year

A story-form explainer of every cost involved in trading futures, and how each maps to qkt's model.

## Prologue — the woman who wanted more than the stock

Amara had traded stocks for three years and she was bored in the way that precedes either wisdom or ruin. She owned forty shares of an index fund, and every morning she watched the same number crawl. One evening, over suya and malt at a roadside spot in Lekki, her older cousin Bayo — who traded for a living and spoke about markets the way sailors speak about weather — asked her a question that rearranged her furniture.

"When you buy that fund," he said, "whose promise are you holding?"

"My broker's, I suppose. And the market's."

"No," Bayo said. "You're holding a receipt. Somewhere upstream, when you bought, someone else sold. Your broker matched you with them and took a cut for the introduction. Now — what if you didn't want the thing at all? What if you only wanted the *promise* of its price, guaranteed by an institution that has never once, in a hundred and seventy-five years, failed to pay?"

That is a future. Not a thing. A promise, standardized, guaranteed, and priced. Amara went home and opened an account the following week, and what follows is everything the market charged her over the next twelve months — every toll, in the order she met it, with the story of why each one exists. Nothing here is invented for effect; every fee below is real, and every one of them has a reason that, once you see it, you cannot unsee.

## Chapter One — The toll at the bridge (commission)

Her first trade was one micro E-mini S&P contract, a ten-dollar handshake with the American economy. She bought at 5,412.25 and sold an hour later at 5,414.00, a gain of $8.75, and she felt like a genius for exactly eleven seconds — until the statement showed a $2.50 charge on the way in and another $2.50 on the way out. Her genius was worth $3.75.

She called Bayo, annoyed. He laughed the way family laughs when you pay tuition.

"Child, the bridge didn't build itself. The exchange runs the matching engine that paired your order in eleven microseconds. The clearinghouse stood between you and the stranger on the other side and guaranteed — *guaranteed* — that if he defaulted, you would still be paid. Men in suits in Chicago audit that guarantee every single day. The two-fifty is their salary."

That is the commission: the toll for crossing a bridge someone maintains. On Chicago's exchanges it runs roughly fifty cents to two-fifty per contract per side; on crypto venues it is a thin slice of notional, a hundredth of a percent or so. It is charged going in *and* coming out, which is why scalpers — people who cross the bridge fifty times a day — obsess over it the way long-haul drivers obsess over fuel. Amara learned to do the arithmetic before every trade: edge minus two tolls, or no trade at all. In qkt, this toll is never estimated or forgotten. Every root declares its fee, every fill pays it, and the backtest report carries a column called `commissionPaid` so that your paper profits and your real profits differ by exactly the bridge tolls and nothing else. The gross-to-net bridge in every report exists so that you, like Amara, never again mistake $8.75 for $8.75.

## Chapter Two — The merchant's smile (the spread)

Emboldened, Amara tried to flip a crude oil contract in under a minute. Buy, sell, done — price barely moved, yet she lost money. She stared at the tape. *Buy 68.42. Sell 68.41.* The same instrument, two prices, one cent apart, and she had paid both.

"That cent is the merchant's smile," Bayo told her. "At every instant there is a crowd whispering 'I'll buy at this' and another whispering 'I'll sell at that.' The gap between the highest whisper to buy and the lowest whisper to sell is the spread. When you demand *now* — a market order — you accept the merchant's price. When you name your price and wait — a limit order — you *become* the merchant, and someone impatient pays you the smile instead."

This is the price of impatience, and it is the most democratic of all costs: it taxes the hurried and pays the patient, tick by tick, forever. On a liquid S&P contract it is usually a single tick — twelve dollars and fifty cents a contract that vanishes the instant you flip. On a panicky night, or a thin testnet that nobody trades, it yawns wider, and flipping becomes picking up pennies in front of a steamroller while paying the steamroller a toll.

Here is the harder idea underneath, and it deserves a slow telling, because qkt's simulator was built around it. Suppose you place a buy limit at 100 and the market trades at 100. Did you get filled? An amateur simulator says yes. A venue says: only if someone actually *sold* at 100 while your order stood there — otherwise your order is still sitting in the queue, unfilled, while the market walks away. qkt's rule is a small piece of moral philosophy disguised as code: *a limit is snapped to the tick grid in the direction that never fills early.* Your buy never fills above your price; your sell never fills below it. Backtests that violate this rule print money that never existed. Amara's backtests never would, because the engine refuses to rehearse a fill the venue would have denied her.

## Chapter Three — The elephant in the order book (slippage)

By autumn Amara was sizing up: fifty contracts, then a hundred. And a new ghost appeared. Her fills started landing *worse* than the price on her screen — not by a lot, a tick here, two ticks there, but relentlessly, and only on her big orders. Her small ones were pristine.

Picture the order book as a staircase of willingness. At the top step, ten contracts offered. Below it, twenty more a tick worse. Below that, fifty. When Amara the minnow bought one contract, she took a sip from the top step and the staircase never noticed. When Amara the elephant bought a hundred, she drank the staircase dry down three steps, and her *average* price was the average of all three. The screen had shown her the top step. The market had charged her the staircase.

That difference — decision price versus average fill price, caused by your own size eating the available liquidity — is slippage. It is the cost of being big, or fast, or both, and it is why institutions slice hundred-lot orders into confetti and feed them in over hours, and why news traders accept it as the entry fee to violence. In qkt's simulator it is a knob per root, `slippageTicks`, because different venues have different staircases, and the MT5-fidelity mode layers quantization and real bid/ask on top so that slippage compounds with the spread exactly the way Amara watched it compound on her screen.

Notice what the first three tolls share: they are all collected *at the moment of the trade*, in the open, on the ticket. The remaining tolls are quieter. They collect while you sleep.

## Chapter Four — The rent you never see itemized (financing, and its loud cousin funding)

Amara's December trade was her masterpiece: long bitcoin futures into year-end, rode it up eleven percent, closed in January feeling like royalty. Then Bayo asked her an annoying question: "Royalty compared to what? What did the spot price do?"

Spot had risen *twelve* percent. A full percentage point of her move had evaporated somewhere between the coin and the contract, and no statement line named it. This is the financing cost, and its invisibility is the whole lesson.

When you control three hundred thousand dollars of exposure with fourteen thousand dollars of margin, somebody is effectively fronting the difference, and money lent is money rented. In futures, the rent is not billed — it is *baked into the price*. The futures contract trades at spot plus the risk-free rate minus any yield, a relationship the textbooks call cost of carry. You pay it by buying at a premium and watching that premium decay into spot as expiry approaches. No invoice ever arrives. The convergence *is* the invoice.

Now the perpetual contract — the future that never expires — cannot use expiry convergence to collect this rent, because there is no expiry. So it collects loudly instead. Every eight hours, the venue looks at whether the perpetual trades above or below spot. Above? The longs hand cash to the shorts. Below? The shorts pay the longs. It is called funding, it lands in your account as a visible cash flow, and over a thirty-day hold at a hundredth of a percent per interval it quietly eats nearly a percent of notional. Amara learned to check the funding clock the way sailors check the tide tables.

And here the story turns personal to our machine, because qkt today has a hole exactly here, and it admits it in writing: perpetual funding payments are not modelled. A thirty-day perp backtest shows the price journey perfectly and the funding cash flows not at all — the one place where the engine's books drift from the venue's books by design, tracked as issue #1294, waiting its turn like everything else once waited. A documented gap you can plan around beats a hidden one, but a gap is a gap, and now you know precisely where it lives and what it costs you to ignore it: roughly the funding rate, compounded, for every interval you hold.

## Chapter Five — The tax on immortality (roll cost)

December arrived, and with it Amara's first true futures rite of passage. Her contract was dying in two weeks. She still wanted the exposure. So on a Tuesday morning she sold December and bought March — and March cost more. The difference came straight out of her equity, and nothing had "gone wrong." The market had simply been in *contango*, the normal state where carrying something into the future costs money: storage, insurance, interest, all compressed into the slope between the months.

Her friend Chidi, short crude into a *backwardation* — far months cheaper than near, the market screaming that it needs the stuff *now* — got paid to make the same move. Same action, opposite sign. That slope between expiries, the term structure, is free information the market broadcasts all day: contango charges you to persist, backwardation pays you to.

But here is the part the textbooks underplay and the engine does not: a roll is not a bookkeeping entry. It is *two real trades*. You sell into the bid of the dying contract and buy at the ask of the new one — two spreads, two commissions, two encounters with the staircase from Chapter Three — and the gap between the fills is real money won or lost. qkt performs the roll exactly this way even in backtest: close the old leg, wait for the venue's answer, open the new leg, and if the new leg is refused, unwind whatever filled and record a `ROLL_FAILED` exit rather than invent a position that never existed. Every crossing lands in `rolls.csv` with both fills, the gap, and the fees, plus a `rollCostsPaid` column in the report's bridge. Amara's December roll would have appeared there to the cent — and the engine adjusts history *forward* from the first measured roll so that old backtests never rewrite themselves when new rolls arrive, because a past that changes under your feet is not a past at all.

This, by the way, is why strategies that must hold forever — volatility funds, commodity ETFs — slowly bleed in contango and why their prospectuses bury the mechanism on page forty. The tax on immortality is collected monthly, in daylight, and almost nobody reads the receipt. Now you will.

## Chapter Six — The knock at the door (settlement and delivery)

Amara never held to expiry — Bayo forbade it until her second year — but she watched a friend learn why. He held a crude contract into its final day, imagining a cash number appearing on his screen. What arrived instead was, in effect, a knock at the door: *where do we deliver your thousand barrels?* He paid an emergency "exchange for physical" arrangement, plus the storage and transport he had never priced, and closed the whole affair at a loss that dwarfed his trading loss. Forwards deliver things. That is what they are *for*.

Cash-settled contracts — indices, crypto — spare you the tanker truck: at expiry your position simply converts to cash at the settlement price and vanishes. But note the engine's humility even here. A gateway only ever learns delivery prices for contracts its own account actually held. So if your backtest holds something nobody ever held, qkt settles it at the last known price *and warns you*, in writing, that it did. And in the final day before expiry — the guard window — the exchange accepts only orders that reduce risk, and so does the simulator, because rehearsing a fresh long two hours before delivery is rehearsing a trade the venue would have laughed out of the pit. The settlement itself is published as a venue close with the exit reason `EXPIRY`, logged in `settlements.csv`, and your working orders are cancelled first, the way the exchange cancels them in life.

## Chapter Seven — The small envelopes (exchange fees, data, and the ghost)

Three smaller envelopes arrive, and a story about each.

The first is the government's cut: the exchange and the regulator take fractions of a cent per side. You will never feel it and you should never forget it exists, because "never feeling it" across ten thousand contracts is real money. qkt folds it into `commissionPaid` via the per-contract fee, separate from the venue's percentage cut, so the two never blur.

The second is the rent on truth: you cannot trade what you cannot see. CME's real-time feed costs real dollars a month; your broker may bundle it; your backtests need history, which qkt fetches free where it can — Dukascopy ticks, Binance archives, Deribit's public chains — and refuses to run without where it cannot. A backtest with holes is not a backtest with character. It is a lie with charts. The engine fails closed on missing data for the same reason it fails closed on unlisted contracts: a pretty result built on absent bars has stolen your trust, which is worth more than your margin.

The third envelope never arrives, which is what makes it dangerous: opportunity cost. The fifty thousand dollars sitting as margin for a week could have earned a week's Treasury interest — about fifty dollars at current rates. No statement shows it, no engine models it, no regulator requires it. It is yours to remember, the ghost cost, the price of choosing this game over all the others. Professionals carry it in their heads as a hurdle rate; amateurs never hear it knocking. You just did.

## Chapter Eight — The two bridges (futures vs CFDs)

Amara's cousin, meanwhile, traded only CFDs with an offshore broker, and their year-end comparison over New Year's drinks is the cleanest summary of this whole chapter.

Amara's costs had names and receipts: the bridge tolls on her statement, the spreads on the tape, the roll gap in `rolls.csv`, the settlement price in `settlements.csv`, the margin gate that had once refused her an oversized order and saved her a fifth of her account. Every cent traceable to an exchange rule.

Her cousin's costs wore masks. His "zero commission" broker widened the spread whenever he was winning — the spread *was* the commission, adjustable, invisible. His positions never expired, which felt like freedom, until he added up a year of daily swaps and found he had paid the financing rent at his broker's rate, on his broker's schedule, without negotiation. His feed froze for ninety seconds during a news spike — his broker's feed, his broker's rules — and his stop filled somewhere he did not recognize. No receipts. No register. No appeal beyond a support ticket.

Here is the difference in one image. A future is a *public promise*: standardized terms, a clearinghouse that has never failed in a century and three quarters, one tape the whole world sees, margin set by the exchange, settlement at a published price. A CFD is a *private bet*: your broker is the counterparty, the quote is theirs, the spread is theirs, the swap is theirs, and your position exists only inside their building. The future charges you openly for guarantees. The CFD charges you quietly for convenience — no expiry to manage, higher leverage, fractional everything — and the quietness is the product.

That is also why qkt rehearses them differently under one identical strategy language. Point a `.qkt` file at `BACKTEST:XAUUSD` and it replays Dukascopy ticks with bid/ask spreads and overnight swaps — the broker's reality. Point it at a Binance quarterly or a Deribit structure and it replays exchange bars with tick grids, fees, rolls, margin gates and settlements — the clearinghouse's reality. Same words, different worlds underneath. The strategy doesn't care which bridge it crosses. The trader must know exactly what each bridge charges, which is everything you just read.

## Epilogue — the receipt

Amara keeps a notebook — paper, stubbornly analog — with one page per toll, updated every quarter. Bridge tolls. Merchant smiles. Staircases. Invisible rent, loud funding. Immortality tax. The knock at the door. Small envelopes. The ghost. Beside each, the place in qkt's reports where it must appear, checked the way pilots check instruments: commission in `commissionPaid`, spreads and slippage inside every fill price, rolls in `rolls.csv` and `rollCostsPaid`, settlements in `settlements.csv`, margin in `margin_daily.csv`, funding — for now — in issue #1294, the one receipt still being printed.

"Nobody ever told me trading had this many hands in my pocket," she told Bayo, a year in.

"Every hand feeds you something," he said. "The bridge holds. The merchant waits. The clearinghouse never blinks. Just make sure every hand shows its face — and never, ever pay a hand you cannot name."

That is the whole of it. Name every hand.

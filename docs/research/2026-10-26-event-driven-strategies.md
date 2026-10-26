# Event-driven / news-driven strategies for a retail CFD trader (MT5 / Exness)

Date: 2026-10-26. Scope: XAUUSD, EURUSD, BTCUSD, US indices as CFDs on Exness MT5, run through qkt.
Method: web search and reading of papers, central-bank notes, prop-firm and broker pages. Every claim has a
link. **[unverified]** means I saw only an abstract, a secondary summary, or vendor/forum material and could
not check the number in the primary source. I have not invented numbers. Where a number comes from a
vendor or a newsletter, it says so.

---

## 1. Academic evidence

### 1.1 Pre-FOMC announcement drift (S&P 500)

- **Original result.** Lucca & Moench: since 1994 the S&P 500 rose on average **49 bp in the 24 hours
  before scheduled FOMC announcements** (2pm-to-2pm window), t-stat above 4.5. The 1980-1993 drift was
  about 20 bp. The pre-FOMC window accounts for about 80% of annual realized excess returns since 1994. There
  is **no such drift in Treasuries, money-market futures or the dollar**, and none before other macro releases
  (NFP, GDP) ([NY Fed SR 512, rev. 2013](https://www.newyorkfed.org/medialibrary/media/research/staff_reports/sr512.pdf);
  [Liberty Street 2012](https://libertystreeteconomics.newyorkfed.org/?p=1112)). The drift was larger when
  implied volatility was high, the yield curve was flat, and past pre-FOMC returns were high (same paper,
  abstract). Published in the Journal of Finance, Feb 2015.
- **2011-2018 update by the authors.** The drift persisted **only at meetings with a press conference**:
  about 40 bp from the open of the day before to about lunchtime on announcement day, then about 30 bp more
  by the end of the press conference. No excess return before meetings without a press conference
  ([Liberty Street 2018](https://libertystreeteconomics.newyorkfed.org/2018/11/the-pre-fomc-announcement-drift-more-recent-evidence.html)).
  No standard errors are given in that post.
- **Decay.** Kurov, Wolfe & Gilbert extend the sample to Dec 2019. The drift "essentially disappeared after
  2015", both with and without press conferences. They point to lower uncertainty as a possible cause
  ([Finance Research Letters 40, 2021](https://ideas.repec.org/a/eee/finlet/v40y2021ics1544612320315956.html)).
  Ben Dor & Rosa (2019) reportedly find no drift **[unverified, fragment only]**.
- **Conditional on volatility.** Martello & Ribeiro ("Pre-FOMC Announcement Relief", SSRN 3286745) report a
  drift of **109 bp when implied vol is above its prior median, against 9.7 bp below it**, and roughly zero
  in the bottom IV quintile ([Quantpedia summary](https://quantpedia.com/fomc-equity-drift-occurs-in-periods-of-high-uncertainty/))
  **[unverified: secondary summary; return window not stated]**.
- **What I could not find:** a peer-reviewed estimate of the drift for 2020-2026. Treat the drift as
  regime-dependent and probably decayed. It is not a stable edge.

### 1.2 Announcement-day returns (Savor & Wilson and successors)

- 1958-2009: average excess return on announcement days (CPI, PPI, employment, FOMC) was **11.4 bp, against
  1.1 bp on other days**. Over 60% of the annual equity premium falls on announcement days, and the Sharpe
  ratio is about 10x higher ([Savor & Wilson, JFQA 2013](https://ideas.repec.org/a/cup/jfinqa/v48y2013i02p343-375_00.html)).
  Beta prices returns on announcement days, while the security market line is flat on other days
  ([Savor & Wilson JFE 2014, as reproduced in Wachter & Zhu, NBER w24432](https://www.nber.org/papers/w24432.pdf)).
- Updated to 1961-2023: **10.68 bp on announcement days against 0.93 bp on other days**. The roughly 44
  announcement days a year carry about 4.65%/yr, about 71% of the equity premium
  ([NBER w31923](https://www.nber.org/system/files/working_papers/w31923/w31923.pdf)) **[unverified: from search
  snippet; the table was not read]**.
- Caveat: one review notes that the high announcement-day Sharpe ratios are "largely driven" by the
  pre-FOMC returns ([Netspar WP](https://www.netspar.nl/wp-content/uploads/werker_053_revised.pdf)). So if
  the pre-FOMC drift has decayed, part of this premium has probably decayed with it **[inference]**.
- This is an **equity index, long-only, day-level** effect. It says nothing about the direction of FX or
  gold.

### 1.3 FX / gold / Treasury reaction to US macro surprises

- **ABDV 2003 (AER).** Data: 5-minute DM, GBP, JPY, CHF and EUR versus USD, Jan 1992 to Dec 1998, with MMS
  consensus. Findings ([NBER w8959](https://www.nber.org/system/files/working_papers/w8959/w8959.pdf)):
  - Only the *surprise* (actual minus consensus) moves prices. Expected announcements do not.
  - The conditional mean adjusts as a **jump immediately after the release, with "little movement
    thereafter"**. Volatility adjusts gradually, and full adjustment takes about **12 five-minute periods
    (one hour)**.
  - A one-standard-deviation payroll surprise moves DM/$ by about **0.16%**, against a typical spread then
    of about 0.06%.
  - R² of surprise regressions in announcement intervals is "often around 0.3 and sometimes approaching
    0.6".
  - **Sign effect:** bad news moves the dollar more than good news.
  - Releases with exact known times (US) give sharp jumps. German releases with uncertain times gave weak or
    gradual responses.
- **Treasuries.** Most of the price response finishes within one to two minutes (Ederington & Lee 1993;
  Fleming & Remolona). Spreads widen sharply at the release. Then volume surges and volatility persists in
  a second stage. After FOMC, yield volatility stays elevated for about 1.5 to 2.5 hours
  ([Fleming & Remolona, JF 1999](https://ideas.repec.org/a/bla/jfinan/v54y1999i5p1901-1915.html);
  [NY Fed SR 76](https://www.newyorkfed.org/medialibrary/media/research/staff_reports/sr76.pdf)).
  Sydney futures: most adjustment within 240 seconds
  ([Frino & Hill 2001](https://ideas.repec.org/a/wly/jfutmk/v21y2001i6p517-552.html)).
- **Gold / metals.** Elder, Miao & Ramchander (JBF 2011, intraday 2002-2008): metal futures react "swiftly"
  and the 8:30 ET releases, especially **NFP and durable goods**, have the largest impact. Better-than-expected
  US data pushes **gold and silver down** and copper up
  ([Colorado State repository](https://mountainscholar.org/handle/10217/206884)). Sobti (COMEX gold and
  GLD, 2010-2018): US macro news predicts about 34% of intraday gold jumps, with the FOMC rate decision the
  dominant one, and negative jumps outnumber positive ones
  ([IIMA paper](https://www.iima.ac.in/sites/default/files/2024-03/Neharika%20Sobti%20-%20Intraday%20price%20jumps.pdf);
  [EFMA 2024](https://www.efmaefm.org/0EFMAMEETINGS/EFMA%20ANNUAL%20MEETINGS/2024-Lisbon/papers/EFMA_COMPLETEMANU_NSOBTI.pdf))
  **[unverified: abstract-level]**. Hot CPI prints in the 2022-24 hiking regime hit gold, for example -1.3%
  on the Sep 2022 CPI day ([Gulf News / Bloomberg](https://gulfnews.com/amp/story/business%2Fmarkets%2Fgold-steady-after-us-inflation-spurs-biggest-drop-in-2-months-1.1663125677363)).
  That is one anecdote.

### 1.4 How long the reaction lasts (seconds / minutes / days)

| Horizon | Evidence |
|---|---|
| Milliseconds | SPY and E-mini respond to macro surprises **within 5 ms**. Volume rises more than 100x. Profit for the fastest traders is only about $19k (SPY) or $50k (ES) per event, and it did not grow as speed improved ([Chordia, Green & Kottimukkalur, RFS 2018](https://ideas.repec.org/a/oup/rfinst/v31y2018i12p4650-4687..html)) |
| 1-5 min | Price level mostly finished in Treasuries, FX and futures (sources in 1.3) |
| ~1-2.5 h | Volatility still elevated: about 1 h in FX (ABDV), 1.5-2.5 h for Treasury yields after FOMC (Fleming & Remolona) |
| 12-24 h | **FX after FOMC:** post-announcement returns reverse about **65% of the positive pre-FOMC drift**, mostly 12-24 h after the release ([Lee & Wang, RAPS 2025](https://ideas.repec.org/a/oup/rasset/v15y2025i3-4p247-287..html)) **[abstract only]** |
| Days | End-user FX order flow induced by news "remains significant for days" and has persistent price effects ([Evans & Lyons, JIMF 2005](https://ideas.repec.org/a/eee/jimfin/v24y2005i2p197-217.html)) |
| ~50 days | **Treasuries after FOMC:** a 10 bp funds-rate surprise moves 10y yields about 1.7 bp on the day but **about 14 bp after 50 days**. The authors tie this to mutual-fund outflows ([Brooks, Katz & Lustig, NBER w25127](https://www.nber.org/papers/w25127)) **[abstract numbers]** |

So the *level* response to scheduled data is close to instantaneous. What persists is (a) elevated
volatility for about an hour, (b) possible slow drift over days or weeks that runs through flows (FX order
flow, bond funds), and (c) FOMC-specific dynamics: the press conference, and the overnight reversal in FX.

### 1.5 Post-announcement drift / reversal around FOMC

- **Powell press conferences.** Market volatility during Powell press conferences is about **3x higher than
  under his predecessors**, and they "tend to reverse the market's initial reactions" to the statement
  ([Narain & Sangani, VoxEU 2023](https://cepr.org/voxeu/columns/market-impact-fed-press-conference))
  **[page returned 403; claim taken from search snippet; no reversal frequency found]**.
- **Baglioni & Ribeiro, "The FOMC Announcement Reversal"** (SSRN 4182628). Rule: at 13:50 ET take the opposite
  side of the prior-24h E-mini return and exit at end of day. 180 meetings, 1997-2020, trades at bid/ask,
  split into subperiods at April 2011 ([CXO summary](https://www.cxoadvisory.com/calendar-effects/stock-market-return-reversal-after-fomc-announcements)).
  **Results are paywalled, so the magnitudes are unverified.** This is the most concretely specified FOMC
  recipe I found.
- Cieslak, Morse & Vissing-Jorgensen (JF 2019): since 1994 the equity premium is earned **entirely in even
  weeks (0, 2, 4, 6) of the FOMC cycle** ([SSRN 2687614](https://papers.ssrn.com/abstract=2687614)).
  Out-of-sample decay is not checked here.

### 1.6 Volatility around releases

- Announcements explain much of the intraday volatility pattern in FX. Once announcement timing is
  controlled for, the U-shape disappears (Ederington & Lee 2001; see the listing in
  [IDEAS](https://ideas.repec.org/a/wly/jfutmk/v21y2001i6p517-552.html) results), and announcement effects are
  separable from ARCH persistence ([Andersen & Bollerslev, JF 1998](https://projects.nber.org/papers/w5783)).
- About one-third of intraday jumps in US futures coincide with US macro announcements (Evans 2011,
  [Cardiff ORCA](https://orca.cardiff.ac.uk/19013)) **[abstract-level]**.
- Implication: a volatility *filter* keyed to scheduled events is well grounded. A *directional* edge at the
  release is not.

### 1.7 BTC reaction to US CPI / FOMC since 2020

- **CPI (Block Scholes, industry note, Jul 2026).** From 2020 onward, the CPI surprise explains about 25% of
  first-hour BTC returns, about **-5.5% per +1pp surprise (-0.55% per 0.1pp)**. On a 24-release rolling
  window the peak was R² 52% and β -13%/pp over Dec 2021-Nov 2023. R² went to about zero in late 2024-2025,
  then rose again to about 22% (24-release window, β -3.2%/pp) or nearly 80% (12-release window) in 2026, with
  smaller magnitudes. The effect sits at the **1-hour horizon, not 4-24h**. NFP: about -0.16% per 100k
  surprise, R² 9% overall, peak about 59-60% in mid-2024, about zero in 2026
  ([Block Scholes](https://www.blockscholes.com/institutional-research/is-bitcoin-showing-greater-sensitivity-to-us-cpi-releases-again)).
  **[vendor; no standard errors; 12-release R² is very noisy]**.
- **FOMC.** Academic results are mixed. A 2017-2023 hourly study exists ([arXiv 2311.10739](https://arxiv.org/pdf/2311.10739))
  but I did not read its results. Student theses find no significant effect for easing announcements
  ([Lund](https://lup.lub.lu.se/student-papers/record/9066201)) or for 2022-23 ([ISEG Lisbon](https://www.iseg.ulisboa.pt/estudar/trabalhos-finais-de-mestrado/fi/1692298719004092)).
  Volatility is higher at FOMC statement time for crypto/DeFi assets ([arXiv 2302.10252](https://arxiv.org/pdf/2302.10252)).
- Takeaway: BTC's sign to US data surprises is consistent (hot CPI is bearish), but the **sensitivity is
  strongly time-varying**. Any BTC event model needs a rolling-beta or regime gate.

### 1.8 Gold vs real yields and the 2022-24 decoupling

- The gold to 10y TIPS inverse relationship was fairly stable from the late 1990s through 2021, then
  "weakened substantially" from 2022. Gold held up as real rates rose. Central-bank buying went from an
  average of 473 t/yr (2010-2021) to **over 1,000 t/yr (2022-2024)**
  ([RBC Wealth Management](https://www.rbcwealthmanagement.com/en-us/insights/golds-regime-change);
  [T. Rowe Price](https://www.troweprice.com/content/dam/ide/articles/pdfs/2024/q4/what-is-driving-gold-prices-to-all-time-record-highs-na.pdf)).
- A counter-view: gold still follows real yields in the short run, but from a higher base set by
  central-bank demand ([Janus Henderson, 2026](https://www.janushenderson.com/en-gb/adviser/article/chart-to-watch-whats-behind-the-divergence-between-gold-and-real-treasury-yields/)).
- Geopolitical/sanctions risk raises demand for gold over sanctionable Treasuries. Elevated geopolitical
  risk coincides with higher gold *and* higher 10y yields
  ([Arvai, Coimbra & Pinchetti, NBER w35669](https://www.nber.org/papers/w35669);
  [VoxEU](https://cepr.org/voxeu/columns/fools-gold-how-geopolitical-risk-taking-some-shine-us-dollar)).
  That is a quarterly panel, not an intraday signal.
- Implication: any gold model fitted on pre-2022 level relationships with real yields is suspect. *Intraday
  surprise* sensitivity (hot data means gold down) still shows up in 2022-24 anecdotes, but I found no
  peer-reviewed post-2022 estimate.

### 1.9 CFTC COT positioning

- **Weekly COT changes are contemporaneous, not predictive, for FX.** Speculator position changes track
  30-45% of same-week moves in major currencies and get about 75% of the weekly direction right, but they are
  "not useful for anticipating" next-week moves ([Klitgaard & Weir, NY Fed EPR 2004](https://www.newyorkfed.org/research/epr/04v10n1/0405klit/0405klit.html)).
  An RBNZ re-assessment concludes predictive use is limited at weekly frequency and that the publication lag
  hurts ([RBNZ AN 2018-03](https://ideas.repec.org/p/nzb/nzbans/2018-03.html)).
- **Extremes / turning points.** Tornell & Yuan (J. Futures Markets 2012): *peaks and troughs* in net
  positions help predict spot FX. Speculator positions signal **continuation** and hedger positions signal
  **reversal** ([IDEAS](https://ideas.repec.org/a/wly/jfutmk/v32y2012i2p122-151.html)). Wang (2003, S&P
  futures): large-speculator sentiment is a continuation signal, large-hedger sentiment is contrarian, and
  extremes are the most reliable ([IDEAS](https://ideas.repec.org/a/taf/apfiec/v13y2003i12p891-898.html)).
- So the retail folklore "fade extreme speculator positioning" is **not** what the academic work says. If
  anything, speculator extremes point to continuation and *hedger* extremes are contrarian. Data is
  measured Tuesday and published Friday, so there is a 3-day lag
  ([NexusFi](https://nexusfi.com/a/concepts/commitment-of-traders-cot-report)). Common practitioner thresholds
  (COT index over 156 weeks, above 80 or below 20) are rules of thumb, not validated
  ([LuxAlgo](https://www.luxalgo.com/library/concept/cot-analysis.md)).

### 1.10 Crypto funding rate / basis / Fear & Greed

- **Basis ("crypto carry").** High carry (futures basis) **predicts future price crashes**, and rising carry
  comes with a higher price of crash insurance. The drivers are trend-chasing small investors and scarce
  arbitrage capital. Carry averages about 7-8%/yr with spikes above 40-60% p.a.
  ([Schmeling, Schrimpf & Todorov, BIS WP 1087](https://www.bis.org/publ/work1087.htm);
  [VoxEU](https://cepr.org/voxeu/columns/crypto-carry-market-segmentation-and-price-distortions-digital-asset-markets)).
  This is the best-grounded crypto positioning signal I found. It concerns *basis*, which is related to
  funding but not the same thing.
- **Funding extremes.** K33 reports average subsequent returns of about 79% after 30-day average funding
  turned negative, but it was only the **seventh** such episode since 2018
  ([The Block](https://www.theblock.co/post/315831/perp-signal-bitcoin-bottom-bullish-year-end-k33)).
  **[vendor; n about 7; no holding period stated]**. I found no peer-reviewed backtest of funding extremes.
- **Fear & Greed.** Peer-reviewed Granger tests exist ([Review of Behavioral Finance 2024](https://ideas.repec.org/a/eme/rbfpps/rbf-08-2023-0224.html))
  but I could not see the results. Non-academic analyses conflict: one finds Extreme Greed precedes further
  short-term gains ([Ecoinometrics](https://ecoinometrics.substack.com/p/bitcoin-fear-and-greed-where-is-the)),
  another finds "no actionable rule" at short horizons
  ([mirror, author unclear](https://san-1510.aid.brainsum.com/news/analysis/cryptos-favourite-adage-half-fails-bitcoin-hits-extreme-fear)).
  **Weak evidence either way.**

### 1.11 GDELT / news sentiment / geopolitical risk

- **GPR index (Caldara & Iacoviello, AER 2022)** counts threats and acts in newspapers. Gold and silver hedge
  geopolitical risk, especially *threats* rather than *acts*, and are the only consistent safe havens in
  extreme GPR. Stocks and bonds respond negatively
  ([Baur & Smales, JBF 2020](https://ideas.repec.org/a/eee/jbfina/v117y2020ics037842662030090x.html)).
  "Threats more than acts" fits the folk pattern that the gold spike fades when the event actually happens.
- **Spikes on events fade.** Metals Focus: over 30-40 years of crises, gold rallies were often knee-jerk and
  went back to pre-crisis levels quickly. The exception was 1979 ([Kitco, Mar 2026](https://www.kitco.com/news/article/2026-03-05/gold-slips-after-brief-surge-iran-tensions-metals-focus-still-sees-upside)).
  An OCBC study **[unverified, secondary]** says gold often dipped first by up to 2%, then was 6.5-8% higher
  three months later (except Ukraine 2022) ([Xetra-Gold](https://www.xetra-gold.com/en/detail/gold-as-safe-haven-new-study-shows-why-price-has-been-fluctuating-in-2026-despite-conflicts/)).
  In 2026, gold briefly tested about $5,400 after the US-Israel strikes on Iran, then fell below $5,100 within
  days (Kitco, above).
- **News tone.** An oil-specific dictionary on FT articles helps forecast *monthly* oil out of sample at
  short horizons, while a general dictionary does not ([Economic Modelling 2021](https://ideas.repec.org/a/eee/ecmode/v104y2021ics0264999321002248.html)).
  EURUSD: with more than 100M articles at millisecond precision, sentiment is *predictive but not
  contemporaneous*, and the relationship shifted after the GFC
  ([NBER conf. paper](https://conference.nber.org/conf_papers/f69879.pdf)) **[unverified: snippet]**.
  RavenPack US macro sentiment cross-over on FX: IR 1.61 (10h hold) and 1.29 (3h hold)
  ([vendor-linked report](https://c.mql5.com/forextsd/forum/173/intraday_forex_trading_based_on_sentiment_inflection_points.pdf))
  **[vendor]**.
- **Latency.** GDELT 2.0 updates every **15 minutes**. Event TONE is the average tone of documents in the
  15-minute batch where the event was first seen ([ONS methodology](https://www.ons.gov.uk/peoplepopulationandcommunity/birthsdeathsandmarriages/deaths/methodologies/globaldatabaseofeventslanguageandtonegdeltappendix)).
  Dow Jones to RavenPack metrics takes about **300 ms** ([NBER conf. paper](https://conference.nber.org/conf_papers/f69879.pdf)).
  So GDELT is at least 15 minutes behind the wire. It can only feed **regime / risk filters at hour-to-day
  horizons**, never a reaction trade.

---

## 2. How professionals run event systems

- **Machine-readable feeds.** Dow Jones Elementized News Feed (2007) delivers economic indicators as
  XML-tagged fields so no text parsing is needed, with "millisecond" action claimed. Reuters launched a
  similar product in Dec 2006 ([Waters](https://waterstechnology.com/node/1616422);
  [Institutional Investor](https://www.institutionalinvestor.com/article/2bt055w2ccc7c6cgteeww/innovation/dow-jones-adds-30-indicators-to-news-feed)).
  RavenPack sits on top of DJ news at about 300 ms (above). I did not research Bloomberg's equivalent here.
- **Who wins the first seconds.** Index futures and ETFs respond within 5 ms
  ([Chordia et al. 2018](https://ideas.repec.org/a/oup/rfinst/v31y2018i12p4650-4687..html)). Release
  distribution is engineered for simultaneity. The Fed moved from lock-up to an embargo system in March 2013.
  In the Sept 18, 2013 "no taper" event, Nanex alleged Chicago trades about 7 ms before the release could
  have arrived at light speed. Quincy Data's exchange-timestamp analysis found a release consistent with
  simultaneous arrival at CME and Nasdaq colos at exactly 14:00:00
  ([Mondo Visione / Nanex](https://mondovisione.com/media-and-resources/news/nanex-20-sep-2013-einstein-and-the-great-fed-robbery-2013928/);
  [Quincy Data](https://quincy-data.com/fed-robbery-revisited)). The DOL ended media lock-ups for BLS data
  in June 2020, after a Sandia review found that split-second early access is profitable for HFTs
  ([ThinkAdvisor](https://thinkadvisor.com/2020/01/14/labor-dept-to-restrict-media-access-to-key-economic-reports);
  [Law360](https://www.law360.com/articles/1277263/dol-shuts-down-media-s-pre-release-access-to-jobs-data)).
- **Why retail cannot compete at the first move.** (1) The reaction is complete in milliseconds to a
  couple of minutes (section 1.4). (2) Retail calendar feeds lag: anecdotally a few seconds, and one trader
  reports 1-2 minutes on a broker calendar ([BabyPips](https://forums.babypips.com/t/early-news-feed/70004))
  **[anecdotal; measure it ourselves]**. (3) Liquidity providers pull quotes in the first seconds, so the
  retail price is wide or missing exactly then (section 3.2). (4) Even the fastest players earn only tens of
  thousands of dollars per event (Chordia et al.), which leaves nothing for a slower trader.
- **Horizons that remain open (by elimination, supported by section 1):** (a) volatility-regime and risk
  filters (avoid or size down around events); (b) multi-hour to multi-day responses that run through flows
  (Evans & Lyons; Brooks-Katz-Lustig; Lee & Wang's 12-24h FX reversal); (c) conditional calendar effects
  (pre-FOMC drift *when IV is high*, FOMC reversal); (d) slow positioning / sentiment signals (basis, COT
  turning points) at weekly horizons.

---

## 3. Retail and prop practice

### 3.1 Prop-firm news rules

| Firm | Rule | Source |
|---|---|---|
| FTMO | No order execution (incl. SL/TP/pending) **2 min before to 2 min after** selected releases, on affected symbols only. For USD: USD pairs, **XAUUSD, US indices, DXY**. Positions opened more than 2 min earlier may be held. Not enforced in Challenge/Verification or on Swing accounts. Breach "may result in termination". The page's metadata and body text differ on which accounts it covers | [FTMO FAQ](https://ftmo.com/en/faq/can-i-trade-news/) |
| The5ers | High Stakes: no opening/closing 2 min before/after high-impact news. Profit from breaching trades removed, loss kept. Other programs differ | [third-party reviews](https://tradetanto.com/learn/the-5-ers-rules-explained-a-complete-guide) **[not checked on the5ers.com]** |
| FundedNext | Funded accounts: trades within **5 min before/after** listed high-impact news (10-min window) get only **40% of profit counted**, losses counted in full. Covers market and pending orders incl. SL/TP. No restriction in challenge phases | [FundedNext help](https://help.fundednext.com/en/articles/10701447-is-news-trading-allowed-at-fundednext) |

These rules show that firms see news-window fills as non-replicable on real liquidity. A strategy whose P&L
depends on the ±2-5 min window is exactly what they exclude.

### 3.2 Broker behaviour around NFP/CPI (MT5 retail)

- **Exness leverage and margin.** From **15 minutes before to 5 minutes after major releases (e.g. NFP)**,
  maximum leverage for *new* positions is capped at **1:200 (most instruments) / 1:50 (indices)**
  ([Exness leverage page](https://www.exness.com/forex/forex-leverage/), via search snippet; the page itself
  returned 403). Indices also have "Daily Higher Margin Requirements" ([Exness indices](https://www.exness.com/indices/)).
  An Exness forum reply says ECN accounts are exempt **[secondhand]** ([EnergyExch](https://www.energyexch.com/thread/post/9235820)).
  Comparable brokers: FxPro (15 min before / 5 min after), KVB (5x margin on XAUUSD ±15 min)
  ([FxPro](https://fxpro.com/leverage-information); [KVB](https://www.kvb.co.id/about-us/announcement/61097)).
- **Spreads and slippage.** An Exness executive says spread instability is common at high-impact news and
  that stop orders can slip. Exness's "price volatility protection" delays stop-outs during spread spikes,
  for example at rollover ([Finance Magnates interview](https://financemagnates.com/thought-leadership/exness-head-of-trading-stanislav-bublik-talks-pricing-stability-and-more/amp)).
  Trader complaints describe SL fills far through the level at news, including two identical XAUUSD stops
  filling at different prices at the Sep 2026 NFP ([FPA](https://www.forexpeacearmy.com/community/goto/post?id=495193))
  **[anecdotal]**.
- **Industry pattern (not Exness-specific).** At NFP, spreads roughly double within the first second, some
  brokers "step aside" at about 10 pips EURUSD, and spreads normalise within about a minute
  ([FXStreet 2018](https://www.fxstreet.com/analysis/march-nfp-shock-resilience-201803092200)). A 90+ broker
  aggregate peaked at about 5x the pre-release spread, settling after a couple of minutes at a slightly
  higher level ([FXStreet 2015](https://www.fxstreet.com/education/how-to-trade-nonfarm-payrolls-201508280000)).
  Spreads of 34 pips at NFP have been reported ([MQL5 blog](https://www.mql5.com/en/blogs/post/772709))
  **[anecdotal]**.
- **Our own data:** the 2026-09-14 Exness live-vs-backtest audit (internal memory) found normal XAUUSD
  costs matched (0 commission, about $0.26 spread) but **mt5-sim was optimistic on stops**. That is the
  failure mode that dominates in news windows.

### 3.3 Straddle / breakout / fade strategies and published claims

- **News straddle.** Buy-stop and sell-stop placed minutes before NFP, about 25-30 points from price
  ([MQL5 blog](https://www.mql5.com/en/blogs/post/772709)). Practitioners call it an old technique that
  "tends to fail" in managing the position after the spike ([BabyPips](https://forums.babypips.com/t/no-loss-news-trading/72450)).
  The mechanics work against it: the stop that triggers the entry fills during the quote vacuum (3.2), and
  whipsaws trigger both legs.
- **Published win rates.** I found **no** credible, slippage-adjusted backtest or audited track record of NFP
  straddles, NFP/gold range breakouts or fade-the-spike systems. What exists is EA marketplace listings
  (self-reported, e.g. a gold H4 breakout EA claiming PF 1.77 on 198 trades, not news-specific:
  [MQL5](https://www.mql5.com/fr/market/product/176400)), TradingView scripts with no stats, and how-to
  articles. **Treat all claims as unverified marketing.**
- **Fade the spike.** No academic evidence of systematic overreaction at the first minutes. ABDV found
  "little movement thereafter" in FX, and a DM/$ 5-min study concluded the market is efficient to
  announcements ([LSE FMG DP 258](https://ideas.repec.org/p/fmg/fmgdps/dp258.html)). The reversal evidence
  that does exist is FOMC-specific: the Powell press-conference reversals, and Lee & Wang's 12-24h FX
  reversal of the pre-FOMC drift.

---

## 4. Backtesting pitfalls for event strategies

1. **Revised data / vintages.** Signals must use first-release values as published, not the revised series.
   ALFRED stores each vintage. Release *dates* in archives can be ingestion dates rather than publication
   times, so use source calendars plus a conservative delay as a sensitivity test
   ([FRED blog](https://fredblog.stlouisfed.org/2021/04/alfred-at-15-archiving-fred-data-since-2006/);
   [macroforecast docs](https://macroforecast.readthedocs.io/en/stable/encyclopedia/l1/axes/vintage_policy.html)).
   Our own hub found exactly this on 2026-10-25: FRED stamped 13:00 UTC before the 16:15 posting, FEDFUNDS
   with a month of look-ahead, CFTC shutdown-period reports stamped weeks early (internal memory,
   hub-live-parity-2026-10-25).
2. **Consensus availability.** Every surprise-based signal needs point-in-time consensus. Calendars
   disagree. One NFP release showed consensus figures from 167K to 195K across five calendars
   ([BabyPips](https://forums.babypips.com/t/a-brief-comparison-of-5-popular-economic-calendars/177165)).
   "Prior" values get revised after release ([Briefing.com](https://briefing.com/calendars/economic)).
   Trading Economics sells point-in-time history ([TE API](https://tradingeconomics.com/api/calendar.aspx)).
   ABDV used the MMS International survey. **Our hub currently has the event calendars
   (cal.us_fomc/cpi/nfp) but, as far as I know, no consensus series, so surprise-signed recipes are blocked
   until one is sourced.**
3. **Timestamps and time zones.** FOMC times changed over the sample: 2:15pm until 2011; 12:30pm statements
   with press conferences in 2011-12; 2:00pm with press conference at 2:30pm from 2013
   ([Liberty Street 2018](https://libertystreeteconomics.newyorkfed.org/2018/11/the-pre-fomc-announcement-drift-more-recent-evidence.html)).
   US and EU DST offsets shift 13:30 UTC to 12:30 UTC for part of the year.
4. **Tick vs bar fills.** The price is a jump with a quote vacuum. A bar backtest that fills a stop at the
   stop level, or fills at a bar OHLC inside the release minute, invents liquidity. Use tick data with
   **bid/ask**, model the spread spike (2-5x, normalising within about 1-2 min) and stop slippage *through*
   the level (section 3.2). Internal: mt5-sim is already optimistic on stops in normal conditions.
5. **Spread modelling.** A constant spread is wrong around events. Use recorded Exness ticks from our live
   captures for the release minute, not Dukascopy (internal rule: research on Exness quotes).
6. **Survivorship / regime.** Effects decay (pre-FOMC drift after 2015) or change sign by regime (BTC CPI
   beta near zero in 2024-25; gold vs real yields after 2022). Report by subperiod.
7. **Sample size.** 8 FOMC meetings a year means about 80 events in 10 years, and fewer once split by press
   conference or regime. The pre-FOMC drift was confirmed only because a 49 bp mean against about 0.7 bp
   (sd 10.5 bp on resampled non-FOMC returns) is extreme ([SR 512 appendix](https://www.newyorkfed.org/medialibrary/media/research/staff_reports/sr512.pdf)).
   NFP and CPI give about 12 events a year each. A 40-event backtest with a 55% hit rate is noise.
8. **Multiple testing.** Many windows, offsets, thresholds and instruments are a garden of forking paths.
   Use a t-hurdle of about 3 ([Harvey, Liu & Zhu, RFS 2016](https://www.nber.org/papers/w20592)) and/or
   the Deflated Sharpe Ratio with the true trial count ([Bailey & López de Prado 2014](https://papers.ssrn.com/abstract=2460551)).
9. **Execution constraints in the window.** Exness's leverage cap for new positions from 15 min before to
   5 min after must be in the margin model. Prop rules make ±2-5 min trades non-countable if we ever route
   to a prop account.

---

## 5. Recipes that recur (as published)

| # | Recipe | Published parameters | Evidence quality |
|---|---|---|---|
| R1 | **Pre-FOMC drift, long US index** | Long S&P from 2pm day-before (or day-before open, post-2011) to just before 2pm statement ([LM 2015](https://www.newyorkfed.org/medialibrary/media/research/staff_reports/sr512.pdf); [LSE 2018](https://libertystreeteconomics.newyorkfed.org/2018/11/the-pre-fomc-announcement-drift-more-recent-evidence.html)). Conditional variant: only when IV is above its prior median ([Martello & Ribeiro via Quantpedia](https://quantpedia.com/fomc-equity-drift-occurs-in-periods-of-high-uncertainty/)) | Strong 1994-2011; decayed after 2015 ([Kurov et al.](https://ideas.repec.org/a/eee/finlet/v40y2021ics1544612320315956.html)); conditional variant unverified |
| R2 | **FOMC announcement reversal (index)** | At 13:50 ET, take the opposite sign of the prior-24h E-mini return, exit at close ([Baglioni & Ribeiro via CXO](https://www.cxoadvisory.com/calendar-effects/stock-market-return-reversal-after-fomc-announcements)) | Results paywalled; 180 events |
| R3 | **FOMC press-conference reversal** | Fade the statement reaction (14:00-14:30 ET) into the press conference ([Narain & Sangani](https://cepr.org/voxeu/columns/market-impact-fed-press-conference)) | Qualitative; no published rule or stats found |
| R4 | **Post-FOMC FX reversal of pre-drift** | Reverse the pre-FOMC FX drift, mostly over 12-24h after ([Lee & Wang 2025](https://ideas.repec.org/a/oup/rasset/v15y2025i3-4p247-287..html)) | Peer-reviewed (RAPS); abstract only |
| R5 | **NFP/CPI breakout of pre-release range (gold/EURUSD) / news straddle** | Buy/sell stops about 25-30 points outside price minutes before release ([MQL5](https://www.mql5.com/en/blogs/post/772709)); NY 15-min opening-range variants ([TradingView](https://it.tradingview.com/script/BPVFldie-XAUUSD-1H-Bounce-Breakdown-Strategy)) | No credible stats anywhere; the mechanics fight the quote vacuum |
| R6 | **Surprise-signed continuation** | Trade the sign of (actual minus consensus); hot US data means short gold/EURUSD/BTC ([ABDV](https://www.nber.org/system/files/working_papers/w8959/w8959.pdf); [Elder et al.](https://mountainscholar.org/handle/10217/206884); [Block Scholes](https://www.blockscholes.com/institutional-research/is-bitcoin-showing-greater-sensitivity-to-us-cpi-releases-again)) | Sign is robust; the *tradable* part after the first minute is small or unknown |
| R7 | **Risk-off on conflict news** | Long gold / JPY / CHF on geopolitical *threat* escalation ([Baur & Smales](https://ideas.repec.org/a/eee/jbfina/v117y2020ics037842662030090x.html)); the spike tends to fade when it becomes an act ([Kitco](https://www.kitco.com/news/article/2026-03-05/gold-slips-after-brief-surge-iran-tensions-metals-focus-still-sees-upside)) | Directional hedge property documented; no timing rule; GDELT latency 15 min |
| R8 | **COT extreme reversal** | COT index over 156 weeks, above 80 / below 20 ([LuxAlgo](https://www.luxalgo.com/library/concept/cot-analysis.md)). Academic version: peaks/troughs of net positions, *speculator = continuation, hedger = reversal* ([Tornell & Yuan](https://ideas.repec.org/a/wly/jfutmk/v32y2012i2p122-151.html); [Wang](https://ideas.repec.org/a/taf/apfiec/v13y2003i12p891-898.html)) | Folklore version contradicted; weekly changes not predictive ([Klitgaard & Weir](https://www.newyorkfed.org/research/epr/04v10n1/0405klit/0405klit.html)) |
| R9 | **BTC funding / basis extreme reversal** | 30-day average funding below 0, buy ([K33](https://www.theblock.co/post/315831/perp-signal-bitcoin-bottom-bullish-year-end-k33)); high basis means crash risk ([BIS crypto carry](https://www.bis.org/publ/work1087.htm)) | Basis-crash link peer-level (BIS WP); funding-negative rule n about 7 |

---

## Implications for us

Starting points: Exness MT5 CFDs, retail latency (seconds at best), the release calendars already in
qkt-data-hub (FOMC/CPI/NFP 2018-2027, entries proven to fire 2 min before events live), plus CFTC, funding and
fear_greed in the hub. Minute-to-day horizons.

**Do not build:** anything that needs the first 0-2 minutes. That rules out straddles, pre-release-range
breakouts and fading the first spike. The literature says the level jump is done in ms to 2 min, and our
broker caps leverage and widens spreads exactly then. Our own simulator is optimistic on stops. No credible
published edge exists to offset this. If we test R5 at all, test it once, as a *negative control* to prove
the cost model kills it.

**Test first, in this order:**

1. **Event-aware risk filter (not a strategy, an overlay).** Hypothesis: flattening or blocking entries in
   [-15 min, +60 min] around FOMC/CPI/NFP improves risk-adjusted returns of the *existing* book (volatility
   persists about 1h: ABDV; Fleming-Remolona). Parameters: block-window pre ∈ {5, 15} min, post ∈ {5, 30, 60}
   min; variants block-new-entries vs. flatten. Measure: change in Sharpe, max DD, tail loss (worst 1% of
   trades), stop slippage realized on Exness live vs. sim, number of trades lost. Cheapest test, directly
   usable, and robust to the lack of consensus data.
2. **R4 / R2 / R1 FOMC conditional trades (US500 / XAUUSD / EURUSD CFDs).** About 70 FOMC events in 2018-2026,
   so treat this as exploratory. Tests:
   - R1 long US500 from the day-before open to 13:55 ET, unconditional vs. "VIX above its 1-year median" gate.
   - R2: at 13:50 ET, sign opposite the 24h return, exit at the 16:00 ET close (index CFD sessions allowing).
   - R4: on EURUSD, measure the pre-FOMC 24h drift and fade it from +1h to +24h after the statement.

   Measure: mean bp per event net of the actual Exness spread at entry/exit time, t-stat, hit rate,
   subperiod 2018-21 vs 2022-26 (every meeting has had a press conference since 2019 [from memory, not checked here], so the pre-2016 split is no longer available).
   Accept only with t of about 3 or more, or a Deflated Sharpe that accounts for every variant tried. Expect
   most to fail. The value is in knowing.
3. **Post-release drift at +5 min to +1 day, surprise-signed (R6), once consensus exists.** Enter at +5 or +15
   min after CPI/NFP in the direction of the first 5-min move (a proxy for the surprise sign when consensus is
   unavailable), hold to +1h, +4h or the NY close. Instruments: XAUUSD, EURUSD, BTCUSD (Block Scholes says the
   BTC effect sits at 1h). Measure: continuation vs reversal probability conditional on |first-5-min move| in
   ATR units, net of the spread observed at +5/+15 min (should be near normal by then: FXStreet). Report the
   rolling-12-event beta to see the regime dependence. Source point-in-time consensus (Trading Economics
   history or captured ForexFactory snapshots) before any surprise-*size* variant.
4. **Slow positioning signals as regime filters, not triggers.** BTC basis/funding: test "high 30-day funding
   percentile means reduce long exposure/size" (BIS crash-risk link) rather than the n≈7 "buy negative
   funding" rule. COT: test the academic form only (hedger-net extremes as contrarian, speculator extremes as
   continuation) on gold and EUR with Friday-release timestamps (Tuesday measurement, Friday publication).
   Weekly horizon, so the sample is about 400 weeks since 2018. Measure: next 1-4 week returns by quintile,
   with publication-lag discipline.
5. **Defer:** GDELT/news-tone triggers (15-min latency, no robust FX/gold result found); Fear & Greed
   (conflicting evidence); conflict-news risk-off timing (the hedge property is real but there is no timing
   rule, and the documented pattern is "threat spike, act fade").

**Measurement checklist for every test:** first-release values stamped at true publication time; Exness tick
bid/ask (live captures), not bars; the spread distribution by second around each event logged and used;
stop fills modelled with slippage through the level; Exness 1:200/1:50 news leverage cap in the margin model;
subperiod split (pre-2022 / post-2022); trial count logged for the Deflated Sharpe; per-event P&L table, not
just aggregates.

## Measured: release strategies on XAUUSD, EURUSD, BTCUSD, 2018-2024 (2026-10-26)

**Setup.** qkt 7d1d0b237, full tick replay of the forge Dukascopy store (recorded bid/ask; `--broker mt5-sim`
with the store's commission and slippage per instrument, e.g. gold $4/lot plus 210 points), US release
instants from the hub's `cal.us_high` (223 FOMC/CPI/NFP releases 2018-2024). One position per release, 1 lot,
exit by time. Strategy files and scripts: bot2 `/var/tmp/eventbt` (`strategies/`, `bt.sh`, `analyze.py`).

- **Breakout (+1 min):** on the first 1-minute close after the release beyond the 15-minute pre-release
  range, go with the break; hold 60 minutes. The retail form of the straddle, the report's negative control.
- **Continuation (+5 min):** at release + 5 minutes, trade in the direction of the move since the release;
  hold 60 minutes.
- **Fade (+15 min):** at release + 15 minutes, trade against the move since the release; hold 60 minutes.

**Verification.** 20 sampled breakout trades re-derived independently from raw ticks (range, first close,
direction, fill side): 20/20 match; every entry is at release + 60 s (FOMC 19:00 UTC in winter, 18:00 in summer).

**Results** (bps per trade; *net* = after the store's spread, slippage and commission; *mid* = mid-price at
entry to mid-price at exit, the signal before any cost; 95% bootstrap CI):

| strategy | n | win | net mean | net 95% CI | mid mean | mid 95% CI | mid win | avg spread |
|---|---|---|---|---|---|---|---|---|
| breakout EURUSD | 183 | 45.4% | -3.2 | [-7.3, +0.9] | -1.7 | [-5.8, +2.4] | 47.5% | 0.35 |
| breakout XAUUSD | 166 | 42.8% | -5.3 | [-13.4, +2.1] | -0.2 | [-8.2, +7.3] | 45.2% | 2.24 |
| breakout BTCUSD | 122 | 33.6% | -36.8 | [-64.0, -9.3] | +20.1 | [-2.6, +44.0] | 50.0% | 50.2 |
| continuation EURUSD | 222 | 44.1% | -3.2 | [-6.4, +0.1] | -1.7 | [-5.0, +1.5] | 47.3% | 0.29 |
| continuation XAUUSD | 219 | 44.3% | -5.0 | [-10.9, +0.9] | 0.0 | [-5.9, +5.9] | 47.0% | 2.07 |
| continuation BTCUSD | 223 | 30.5% | -81.8 | [-101.2, -62.2] | +5.1 | [-9.5, +19.5] | 58.3% | 77.6 |
| fade EURUSD | 223 | 50.2% | +1.2 | [-1.6, +4.1] | +2.7 | [-0.2, +5.6] | 55.2% | 0.28 |
| fade XAUUSD | 219 | 46.6% | -0.6 | [-6.1, +5.0] | +4.4 | [-1.1, +9.9] | 51.6% | 2.02 |
| fade BTCUSD | 222 | 22.1% | -86.7 | [-103.3, -68.5] | -0.2 | [-13.0, +13.3] | 49.5% | 77.1 |

Mid-to-mid by year for the cells with a positive point estimate:

| | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 |
|---|---|---|---|---|---|---|---|
| fade EURUSD | +3.3 | -1.7 | +3.0 | +2.3 | +8.2 | -0.2 | +3.7 |
| fade XAUUSD | +4.6 | -6.4 | +3.3 | +1.4 | +16.6 | +2.7 | +8.1 |
| breakout BTCUSD (n 6-26/yr) | +31.3 | +11.0 | -5.3 | -3.3 | +55.3 | +1.3 | +30.6 |

**Reading.**
- Breakout and continuation carry no edge on EURUSD or gold even before costs: the first minutes are
  priced before a retail order fills, as the literature says.
- Fading the move at +15 minutes leans positive on EURUSD and gold in most years (strongest in 2022), a few
  bps per trade, against Exness spreads of about 1 bps (EURUSD) and 1.3 bps (gold at $0.26). Nine cells were
  tried and none clears its CI after that; it is a hypothesis for an out-of-sample test, not an edge.
- BTCUSD's net results are a property of the store, not the market: Dukascopy quoted BTC 50-80 bps wide,
  where Exness quotes about $6 today (under 1 bp). Judge BTC by the mid column: nothing robust.
- Per event, about 32 trades a year: even the leaning cells are small in money at sane risk.

## Measured: a release blackout on the forward book, 2018-2024 (2026-10-26)

**Setup.** The ten forward-stack strategies, each run twice on the same tick replay as above (qkt 7d1d0b237,
`--broker mt5-sim`, 1 Jan 2018 to 1 Jan 2025): unchanged, and with every entry rule gated by a blackout read
from the hub's release calendar, no entry from 15 minutes before a US FOMC/CPI/NFP release to 60 minutes after:

```
evcal = HUB:cal.us_high.USD EVERY 1d
WHEN ((evcal.at - NOW.epoch_ms > 900000 OR NOW.epoch_ms - evcal.at > 3600000)
      AND NOW.epoch_ms - evcal.at[1] > 3600000) AND (<original entry condition>)
```

`evcal.at` is the next release (the calendar publishes it once the previous one has passed) and
`evcal.at[1]` the one before it. Files and the comparison script: bot2 `/var/tmp/eventbt/filter`,
`filter_compare.py`. `xauusd_novel_v3` is excluded: it trades off copper (`XCUUSD`), which the tick store
does not carry.

**Verification.** Every trade the blackout removed is accounted for: either its deciding bar closed inside
the window (all 20:00 UTC bars on winter FOMC days, exactly 60:00 after the 19:00 statement, the window's
inclusive edge), or it fell in the run's first two days, before the calendar had a previous release and the
gate read Undefined, so it failed closed. No entry outside a window was blocked; none inside was let through.

| strategy | trips base/blk | P&L base / blk ($) | win% base/blk | closed-trade DD% | entries in window | trips held through a release | mean P&L held vs other ($) | difference, 95% CI |
|---|---|---|---|---|---|---|---|---|
| btcusd_inside_bar_breakout_1d_v13 | 107/107 | 689 / 689 | 49.5/49.5 | 0.67/0.67 | 0 | 78 | 2.4 vs 17.4 | -15.0 [-55.1, +23.9] |
| btcusd_tsmom_2w_continuous_1d_b2 | 152/152 | 1255 / 1255 | 52.0/52.0 | 0.62/0.62 | 0 | 115 | -2.4 vs 41.4 | -43.8 [-75.7, -11.5] |
| fx2_EURUSD_6 | 201/200 | 240 / 229 | 45.8/45.5 | 0.46/0.46 | 0 | 90 | 4.2 vs -1.4 | +5.6 [-1.4, +12.8] |
| gold_gaparmor_calm_v31_paper | 10040/10000 | -6735 / -6772 | 73.3/73.2 | 7.35/7.36 | 0 | 0 | - | - |
| novel_v3_4d9caf790521_240m | 138/134 | 846 / 949 | 52.9/53.7 | 0.16/0.12 | 3 | 48 | 3.1 vs 7.8 | -4.7 [-17.8, +8.5] |
| novel_v3_xag_gold_tail | 225/225 | 1742 / 1742 | 52.0/52.0 | 0.27/0.27 | 0 | 95 | 10.6 vs 5.7 | +4.9 [-6.7, +17.2] |
| xauusd_novel_accel_240m | 183/183 | 666 / 666 | 53.0/53.0 | 0.34/0.34 | 0 | 65 | 9.4 vs 0.4 | +9.0 [+0.3, +17.8] |
| xauusd_novel_v3_562e14c13ec4_240m | 232/230 | 743 / 613 | 53.4/52.2 | 0.14/0.14 | 4 | 54 | 1.5 vs 3.7 | -2.3 [-9.0, +4.3] |
| xauusd_xag_ratio_accel_1h_rollover_catchup_v1 | 106/106 | 436 / 436 | 50.0/50.0 | 0.11/0.11 | 0 | 30 | 9.9 vs 1.8 | +8.1 [-1.4, +17.4] |

(gaparmor's 40 removed trades are its run-start days; it trades 20 sleeves at once.)

**Reading.**
- The blackout is close to inert on this book. Its strategies decide on 4-hour and daily bar closes
  (00:00, 04:00, ... UTC) and releases land at 12:30, 13:30 and 18:00/19:00, so only seven entries in seven
  years fell in a window; removing them moved two strategies by +$103 and -$130. A release filter is worth
  having for intraday strategies, not for this book.
- The exposure that is real is holding *through* releases: 30-60% of these trades span at least one. Whether
  that helps or hurts differs by strategy: BTC trend trades held through a release did worse (-$43.8 a trade,
  CI excludes zero), gold acceleration trades better (+$9.0, CI just excludes zero). The comparison is
  confounded with holding time (a longer trade is likelier to span a release and has a different P&L
  profile), and with eight tests one such interval is expected by chance. A flatten-before-release rule
  would need its own run, not this table, before anyone acts on it.

## Measured: GDELT event spikes against XAU, EURUSD, USDJPY, BTC (Stage 0, 2026-10-26)

**Data.** The hub's `text.gdelt.events` backfill: every GDELT 2.0 export batch from 2015-03 to 2024-12,
each checked against GDELT's listed MD5, aggregated per hour into eight regions (counts of events,
material/verbal conflict, threats, protests, sanctions, violence; article-weighted Goldstein and tone).
Hours missing a batch are excluded here. Prices are the last mid of each 30-minute bar, built from the raw
Dukascopy ticks (the store has no USDJPY ticks for 2020, so USDJPY's discovery period is 2021-23).

**Timing, measured before testing.** GDELT names a batch in `lastupdate.txt` about ten minutes before its
stamp, but the file itself can answer 404 for half an hour or more after that. Live, the hub completed each
hour 38-47 minutes after it ended. Backfilled hours are therefore stamped known 90 minutes after their end
(hub 514a664), and every signal below is traded at that instant: the close of the bar ending at hour end
+ 90 minutes. An earlier pass that entered at hour end + 30 minutes was discarded as look-ahead.

**Test** (`/var/tmp/eventbt/stage0.py`). For five scopes (GLOBAL, US, MIDEAST, RU_UA, CN), a spike is an
hour whose event volume (log), material-conflict share or tone is three robust sigma (median/MAD) from the
previous 168 hours, tone downwards; spikes within 24 hours of the last are dropped. Outcome: the return
over 1, 4 and 24 hours *in excess of the period's mean return at that horizon* (gold rose about 10 bps a
day in 2024, BTC doubled), with a 95% bootstrap CI, and |return| against the same half-hour of the week.
Discovery is 2020-23; 2024 was held out and read once. 2025 is untouched.

**Results.**
- 8 of 141 discovery cells with ten or more spikes clear 95%; about 7 would by chance. No spike type
  predicts larger moves: |return| after a spike averages 0.85-1.07x the same-hour baseline per symbol and horizon.
- The one coherent pattern is risk-off after a jump in Middle East material conflict:

| MIDEAST conflict spike | 2020-23 | 2024 holdout |
|---|---|---|
| XAUUSD excess, 4 h | +6.0 bps [-0.2, +12.5], n 153 | +14.9 [+3.7, +25.2], n 35 |
| XAUUSD excess, 24 h | +16.1 [-0.1, +32.7], n 118 | +40.6 [+5.1, +74.2], n 26 |
| USDJPY excess, 24 h | -11.1 [-23.1, -0.5], n 99 | -10.2 [-34.0, +11.3], n 28 |

  Gold up and yen up, as a safe-haven move should look, with the same sign at both horizons in both
  periods; but the discovery interval only touches zero, the holdout is 26 events, and 2024 contained the
  Iran-Israel exchanges that may carry it alone. A lead to test, not an edge. Gold's 24 h move here is
  ten times Exness's spread, so cost is not the obstacle; sample size is.

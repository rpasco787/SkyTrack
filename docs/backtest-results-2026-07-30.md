# Accuracy Backtest Results

Generated: 2026-07-30T20:38:41.865108Z

## Coverage Funnel

| Stage | Count | % of landings |
|---|---|---|
| Landings | 4109 | 100.0% |
| Callsign parsed | 2275 | 55.4% |
| Inbound leg found | 2061 | 50.2% |
| Next departure found | 1951 | 47.5% |
| Ground truth present | 1951 | 47.5% |

## Prediction Accuracy (by arm)

| Arm | n | MAE (s) | RMSE (s) | Bias (s) | p50 | p90 | Precision | Recall | F1 |
|---|---|---|---|---|---|---|---|---|---|
| model | 1951 | 483.1 | 1294.0 | -166.9 | 240 | 936 | 0.887 | 0.534 | 0.667 |
| prior | 1951 | 758.9 | 1658.1 | -550.4 | 300 | 1920 | 0.600 | 0.008 | 0.015 |
| zero | 1951 | 791.5 | 1631.7 | -424.3 | 360 | 1800 | 0.000 | 0.000 | 0.000 |
| flat | 1951 | 898.8 | 1598.2 | -558.4 | 612 | 1800 | 0.747 | 0.461 | 0.570 |

## Distribution Sanity Check

Signed BTS `DEP_DELAY` per window. Priors are fitted on the training window
and scored on the eval window, so a large shift here means the priors
underfit — a data difference, not a model failure.

| Window | n | Median (s) | Mean (s) | p90 (s) |
|---|---|---|---|---|
| train 03-01..03-08 | 146775 | -120 | 973.4 | 3300 |
| eval 03-09..03-10 | 39819 | -180 | 643.6 | 2280 |

## Prediction Confusion Matrix (model arm, DelayClassification)

Predictions: 1951, backtestable: 1951, MAE: 483.1s

- predicted=MODERATE actual=MODERATE: 104
- predicted=MODERATE actual=SEVERE: 1
- predicted=MODERATE actual=MAJOR: 26
- predicted=MODERATE actual=ON_TIME: 5
- predicted=MODERATE actual=MINOR: 21
- predicted=SEVERE actual=SEVERE: 12
- predicted=SEVERE actual=ON_TIME: 1
- predicted=MAJOR actual=MODERATE: 4
- predicted=MAJOR actual=SEVERE: 3
- predicted=MAJOR actual=MAJOR: 46
- predicted=MAJOR actual=MINOR: 1
- predicted=ON_TIME actual=MODERATE: 65
- predicted=ON_TIME actual=SEVERE: 2
- predicted=ON_TIME actual=MAJOR: 21
- predicted=ON_TIME actual=ON_TIME: 997
- predicted=ON_TIME actual=MINOR: 244
- predicted=MINOR actual=MODERATE: 72
- predicted=MINOR actual=SEVERE: 1
- predicted=MINOR actual=MAJOR: 5
- predicted=MINOR actual=ON_TIME: 132
- predicted=MINOR actual=MINOR: 188

## Cascade Accuracy

| Metric | Value |
|---|---|
| Total chains | 187 |
| Total hops | 328 |
| Backtestable hops | 328 |
| Scored hops (late-aircraft) | 246 |
| Hop MAE vs late-aircraft (s) | 770.8 |
| Hop RMSE vs late-aircraft (s) | 1153.5 |
| Hop bias vs late-aircraft (s) | -5.1 |
| Hop p50/p90 vs late-aircraft (s) | 480 / 1680 |
| Hop MAE vs total dep delay (s) | 1346.1 |
| Hop bias vs total dep delay (s) | -547.2 |
| Avg chain length | 1.75 |
| Precision (>=15min) | 0.972 |
| Recall (>=15min) | 0.455 |
| F1 | 0.620 |

## Top 10 Worst-Error Predictions (model arm)

| Callsign | Route | Scheduled Dep Epoch | Predicted (s) | Actual (s) | Abs Error (s) |
|---|---|---|---|---|---|
| SKW5076 | ORD-TYS | 1773059700 | 32776 | -660 | 33436 |
| DAL2140 | LGA-SRQ | 1773085140 | -180 | 22860 | 23040 |
| AAY2851 | ROA-SFB | 1773083520 | 3432 | 18780 | 15348 |
| FFT2052 | ATL-DFW | 1773084240 | 764 | 9780 | 9016 |
| SKW5309 | SFO-TUS | 1773098100 | -180 | 7320 | 7500 |
| DAL2209 | MCO-MSP | 1773087480 | 2183 | 9360 | 7177 |
| DAL1416 | BOS-MIA | 1773083700 | 407 | 6960 | 6553 |
| JBU148 | JFK-PHX | 1773093600 | 88 | 6120 | 6032 |
| AAL820 | PDX-ORD | 1773081960 | 1302 | 7080 | 5778 |
| AAL1364 | DFW-CVG | 1773093420 | -60 | 5580 | 5640 |

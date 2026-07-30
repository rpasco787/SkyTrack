# Accuracy Backtest Results

Generated: 2026-07-27T20:52:49.112642Z

## Coverage Funnel

| Stage | Count | % of landings |
|---|---|---|
| Landings | 4109 | 100.0% |
| Callsign parsed | 2275 | 55.4% |
| Inbound leg found | 2061 | 50.2% |
| Next departure found | 1951 | 47.5% |
| Ground truth present | 1951 | 47.5% |

## Prediction Accuracy (by arm)

| Arm | n | MAE (s) | RMSE (s) | Bias (s) | Precision | Recall | F1 |
|---|---|---|---|---|---|---|---|
| model | 1951 | 560.7 | 1316.4 | -65.4 | 0.858 | 0.474 | 0.611 |
| zero | 1951 | 791.5 | 1631.7 | -424.3 | 0.000 | 0.000 | 0.000 |
| flat | 1951 | 898.8 | 1598.2 | -558.4 | 0.747 | 0.461 | 0.570 |

## Prediction Confusion Matrix (model arm, DelayClassification)

Predictions: 1951, backtestable: 1951, MAE: 560.7s

- predicted=MODERATE actual=MODERATE: 81
- predicted=MODERATE actual=SEVERE: 1
- predicted=MODERATE actual=MAJOR: 25
- predicted=MODERATE actual=ON_TIME: 5
- predicted=MODERATE actual=MINOR: 20
- predicted=SEVERE actual=MAJOR: 2
- predicted=SEVERE actual=SEVERE: 12
- predicted=SEVERE actual=ON_TIME: 1
- predicted=MAJOR actual=MODERATE: 6
- predicted=MAJOR actual=SEVERE: 3
- predicted=MAJOR actual=MAJOR: 44
- predicted=MAJOR actual=MINOR: 1
- predicted=ON_TIME actual=MODERATE: 97
- predicted=ON_TIME actual=SEVERE: 2
- predicted=ON_TIME actual=MAJOR: 23
- predicted=ON_TIME actual=ON_TIME: 1076
- predicted=ON_TIME actual=MINOR: 336
- predicted=MINOR actual=MODERATE: 61
- predicted=MINOR actual=SEVERE: 1
- predicted=MINOR actual=MAJOR: 4
- predicted=MINOR actual=ON_TIME: 53
- predicted=MINOR actual=MINOR: 97

## Cascade Accuracy

| Metric | Value |
|---|---|
| Total chains | 123 |
| Total hops | 290 |
| Backtestable hops | 290 |
| Hop-level MAE (s) | 1550.3 |
| Hop-level RMSE (s) | 3689.3 |
| Hop-level bias (s) | 539.6 |
| Avg chain length | 2.36 |
| Precision (>=15min) | 0.947 |
| Recall (>=15min) | 0.084 |
| F1 | 0.154 |

## Top 10 Worst-Error Predictions (model arm)

| Callsign | Route | Scheduled Dep Epoch | Predicted (s) | Actual (s) | Abs Error (s) |
|---|---|---|---|---|---|
| SKW5076 | ORD-TYS | 1773059700 | 33076 | -660 | 33736 |
| DAL2140 | LGA-SRQ | 1773085140 | 0 | 22860 | 22860 |
| AAY2851 | ROA-SFB | 1773083520 | 3672 | 18780 | 15108 |
| FFT2052 | ATL-DFW | 1773084240 | 344 | 9780 | 9436 |
| SKW5309 | SFO-TUS | 1773098100 | 0 | 7320 | 7320 |
| DAL2209 | MCO-MSP | 1773087480 | 2243 | 9360 | 7117 |
| DAL1416 | BOS-MIA | 1773083700 | 647 | 6960 | 6313 |
| JBU148 | JFK-PHX | 1773093600 | 0 | 6120 | 6120 |
| UAL1180 | DEN-IAD | 1773076920 | 6443 | 480 | 5963 |
| AAL1568 | DFW-ABQ | 1773086340 | 1478 | 7200 | 5722 |

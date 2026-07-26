# Accuracy Backtest Results

Generated: 2026-07-26T19:09:34.086250Z

## Coverage Funnel

| Stage | Count | % of landings |
|---|---|---|
| Landings | 4109 | 100.0% |
| Callsign parsed | 2275 | 55.4% |
| Inbound leg found | 2061 | 50.2% |
| Next departure found | 1977 | 48.1% |
| Ground truth present | 1977 | 48.1% |

## Prediction Accuracy (by arm)

| Arm | n | MAE (s) | RMSE (s) | Bias (s) | Precision | Recall | F1 |
|---|---|---|---|---|---|---|---|
| model | 1977 | 600.9 | 1709.8 | -157.4 | 0.832 | 0.382 | 0.524 |
| zero | 1977 | 723.2 | 1854.1 | -348.8 | 0.000 | 0.000 | 0.000 |
| flat | 1977 | 1041.7 | 2322.7 | -554.0 | 0.609 | 0.360 | 0.453 |

## Prediction Confusion Matrix (model arm, DelayClassification)

Predictions: 1977, backtestable: 1977, MAE: 600.9s

- predicted=MODERATE actual=MODERATE: 86
- predicted=MODERATE actual=SEVERE: 2
- predicted=MODERATE actual=MAJOR: 24
- predicted=MODERATE actual=ON_TIME: 5
- predicted=MODERATE actual=MINOR: 20
- predicted=MAJOR actual=MODERATE: 4
- predicted=MAJOR actual=SEVERE: 1
- predicted=MAJOR actual=MAJOR: 14
- predicted=ON_TIME actual=MODERATE: 105
- predicted=ON_TIME actual=SEVERE: 4
- predicted=ON_TIME actual=MAJOR: 36
- predicted=ON_TIME actual=ON_TIME: 1115
- predicted=ON_TIME actual=MINOR: 344
- predicted=MINOR actual=MODERATE: 61
- predicted=MINOR actual=SEVERE: 1
- predicted=MINOR actual=MAJOR: 4
- predicted=MINOR actual=ON_TIME: 54
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
| FFT1592 | MCO-MCO | 1773088080 | 1121 | 51780 | 50659 |
| AAL1997 | DEN-DEN | 1773151200 | 0 | 27720 | 27720 |
| DAL2140 | LGA-LGA | 1773085140 | 0 | 22860 | 22860 |
| SKW6389 | PHX-PHX | 1773108300 | 0 | 12720 | 12720 |
| FFT2052 | ATL-ATL | 1773084240 | 344 | 9780 | 9436 |
| AAL1439 | DFW-DFW | 1773087360 | 3380 | 10860 | 7480 |
| SKW5309 | SFO-SFO | 1773098100 | 0 | 7320 | 7320 |
| DAL2209 | MCO-MCO | 1773087480 | 2243 | 9360 | 7117 |
| DAL1195 | ATL-ATL | 1773175680 | 0 | 7020 | 7020 |
| AAL1584 | DFW-DFW | 1773111300 | 0 | 6660 | 6660 |

# Accuracy Backtest Results

Generated: 2026-07-25T05:12:49.894470Z

## Coverage Funnel

| Stage | Count | % of landings |
|---|---|---|
| Landings | 4109 | 100.0% |
| Callsign parsed | 2201 | 53.6% |
| Inbound leg found | 1987 | 48.4% |
| Next departure found | 1910 | 46.5% |
| Ground truth present | 1910 | 46.5% |

## Prediction Accuracy (by arm)

| Arm | n | MAE (s) | RMSE (s) | Bias (s) | Precision | Recall | F1 |
|---|---|---|---|---|---|---|---|
| model | 1910 | 591.1 | 1718.2 | -151.2 | 0.827 | 0.386 | 0.527 |
| zero | 1910 | 713.3 | 1862.3 | -342.8 | 0.000 | 0.000 | 0.000 |
| flat | 1910 | 1038.2 | 2340.1 | -547.2 | 0.612 | 0.363 | 0.456 |

## Prediction Confusion Matrix (model arm, DelayClassification)

Predictions: 1910, backtestable: 1910, MAE: 591.1s

- predicted=MODERATE actual=MODERATE: 82
- predicted=MODERATE actual=SEVERE: 2
- predicted=MODERATE actual=MAJOR: 23
- predicted=MODERATE actual=ON_TIME: 5
- predicted=MODERATE actual=MINOR: 20
- predicted=MAJOR actual=MODERATE: 4
- predicted=MAJOR actual=SEVERE: 1
- predicted=MAJOR actual=MAJOR: 14
- predicted=ON_TIME actual=MODERATE: 102
- predicted=ON_TIME actual=SEVERE: 4
- predicted=ON_TIME actual=MAJOR: 30
- predicted=ON_TIME actual=ON_TIME: 1078
- predicted=ON_TIME actual=MINOR: 336
- predicted=MINOR actual=MODERATE: 58
- predicted=MINOR actual=SEVERE: 1
- predicted=MINOR actual=MAJOR: 4
- predicted=MINOR actual=ON_TIME: 52
- predicted=MINOR actual=MINOR: 94

## Cascade Accuracy

| Metric | Value |
|---|---|
| Total chains | 115 |
| Total hops | 272 |
| Backtestable hops | 272 |
| Hop-level MAE (s) | 1563.8 |
| Hop-level RMSE (s) | 3696.1 |
| Hop-level bias (s) | 610.7 |
| Avg chain length | 2.37 |
| Precision (>=15min) | 0.943 |
| Recall (>=15min) | 0.079 |
| F1 | 0.146 |

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

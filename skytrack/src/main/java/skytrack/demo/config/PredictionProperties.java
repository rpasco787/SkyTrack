package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.prediction")
public record PredictionProperties(
        boolean enabled,
        String btsCsvPath,
        int minTurnaroundMinutes,
        int delayThresholdMinutes) {

    public PredictionProperties {
        if (btsCsvPath == null) btsCsvPath = "data/bts/ontime-2026-03-09.csv";
        if (minTurnaroundMinutes <= 0) minTurnaroundMinutes = 45;
        // 0 is the backtest escape hatch (disables emit gating); only negative values default.
        if (delayThresholdMinutes < 0) delayThresholdMinutes = 15;
    }
}

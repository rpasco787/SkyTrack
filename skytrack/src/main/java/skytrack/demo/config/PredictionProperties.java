package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.prediction")
public record PredictionProperties(
        boolean enabled,
        String btsCsvPath,
        int minTurnaroundMinutes,
        int delayThresholdMinutes,
        int maxRotationLookaheadMinutes) {

    public PredictionProperties {
        if (btsCsvPath == null) btsCsvPath = "data/bts/ontime-2026-03-09.csv";
        if (minTurnaroundMinutes <= 0) minTurnaroundMinutes = 45;
        // 0 is the backtest escape hatch (disables emit gating); only negative values default.
        if (delayThresholdMinutes < 0) delayThresholdMinutes = 15;
        // Beyond this gap the aircraft has overnighted, so the next leg's delay is not
        // attributable to the arrival that started the walk.
        if (maxRotationLookaheadMinutes <= 0) maxRotationLookaheadMinutes = 360;
    }

    public long maxRotationLookaheadSeconds() {
        return maxRotationLookaheadMinutes * 60L;
    }
}

package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.disruption")
public record DisruptionScoreProperties(
        int windowMinutes,
        int bucketSizeMinutes,
        int delayThresholdMinutes,
        int cascadeThresholdMinutes,
        double cascadePropagationFactor) {

    public DisruptionScoreProperties {
        if (windowMinutes <= 0) windowMinutes = 60;
        if (bucketSizeMinutes <= 0) bucketSizeMinutes = 1;
        if (delayThresholdMinutes <= 0) delayThresholdMinutes = 15;
        if (cascadeThresholdMinutes <= 0) cascadeThresholdMinutes = 30;
        if (cascadePropagationFactor <= 0) cascadePropagationFactor = 0.85;
    }
}

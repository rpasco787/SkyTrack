package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.disruption")
public record DisruptionScoreProperties(
        int windowMinutes,
        int bucketSizeMinutes,
        int delayThresholdMinutes,
        int cascadeThresholdMinutes,
        double cascadePropagationFactor,
        double enRouteRecoveryFactor,
        int cascadeMaxHops) {

    public DisruptionScoreProperties {
        if (windowMinutes <= 0) windowMinutes = 60;
        if (bucketSizeMinutes <= 0) bucketSizeMinutes = 1;
        if (delayThresholdMinutes <= 0) delayThresholdMinutes = 10;
        if (cascadeThresholdMinutes <= 0) cascadeThresholdMinutes = 5;
        if (cascadePropagationFactor <= 0) cascadePropagationFactor = 0.85;
        if (enRouteRecoveryFactor <= 0 || enRouteRecoveryFactor > 1) enRouteRecoveryFactor = 0.15;
        if (cascadeMaxHops <= 0) cascadeMaxHops = 8;
    }
}

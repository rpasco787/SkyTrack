package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.s3")
public record S3Properties(
        String bucket,
        String endpoint,
        String region,
        String prefix,
        String predictionPrefix,
        int flushIntervalSeconds
) {
    public S3Properties {
        if (bucket == null || bucket.isBlank()) bucket = "skytrack-history";
        if (region == null || region.isBlank()) region = "us-east-1";
        if (prefix == null || prefix.isBlank()) prefix = "delays";
        // Separate from `prefix`: the two writers must not collide, so this cannot default to it.
        if (predictionPrefix == null || predictionPrefix.isBlank()) predictionPrefix = "predictions";
        if (flushIntervalSeconds <= 0) flushIntervalSeconds = 300;
    }
}

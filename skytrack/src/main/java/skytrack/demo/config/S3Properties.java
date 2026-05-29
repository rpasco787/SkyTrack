package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.s3")
public record S3Properties(
        String bucket,
        String endpoint,
        String region,
        String prefix,
        int flushIntervalSeconds
) {
    public S3Properties {
        if (bucket == null || bucket.isBlank()) bucket = "skytrack-history";
        if (region == null || region.isBlank()) region = "us-east-1";
        if (prefix == null || prefix.isBlank()) prefix = "delays";
        if (flushIntervalSeconds <= 0) flushIntervalSeconds = 300;
    }
}

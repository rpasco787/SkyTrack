package skytrack.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skytrack.dynamodb")
public record DynamoDbProperties(
        String tableName,
        String endpoint,
        String region) {}

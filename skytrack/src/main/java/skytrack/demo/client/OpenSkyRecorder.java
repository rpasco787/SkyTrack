package skytrack.demo.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public class OpenSkyRecorder {

    private static final Logger log = LoggerFactory.getLogger(OpenSkyRecorder.class);

    public static void main(String[] args) throws InterruptedException, IOException {
        String clientId = System.getenv("OPENSKY_CLIENT_ID");
        String clientSecret = System.getenv("OPENSKY_CLIENT_SECRET");
        Path outputDir = Path.of(args.length > 0 ? args[0] : "data/recorded-opensky");

        Files.createDirectories(outputDir);

        RestClient client = RestClient.builder()
                .baseUrl("https://opensky-network.org")
                .build();

        OpenSkyTokenManager tokenManager = null;
        if (clientId != null && clientSecret != null) {
            tokenManager = new OpenSkyTokenManager(clientId, clientSecret);
            log.info("Using OAuth2 access (5s rate limit)");
        } else {
            log.info("Using anonymous access (10s rate limit)");
        }

        final OpenSkyTokenManager tm = tokenManager;
        int pollIntervalSeconds = 30;
        int durationMinutes = 185;
        int totalPolls = (durationMinutes * 60) / pollIntervalSeconds;

        log.info("Recording {} polls over {} minutes to {}", totalPolls, durationMinutes, outputDir);

        for (int i = 0; i < totalPolls; i++) {
            try {
                var request = client.get().uri("/api/states/all");
                if (tm != null) {
                    request = request.header("Authorization", "Bearer " + tm.getToken());
                }
                String body = request.retrieve().body(String.class);

                String filename = Instant.now().getEpochSecond() + ".json";
                Files.writeString(outputDir.resolve(filename), body);
                log.info("Poll {}/{} saved: {}", i + 1, totalPolls, filename);
            } catch (Exception e) {
                log.error("Poll {}/{} failed", i + 1, totalPolls, e);
            }

            if (i < totalPolls - 1) {
                Thread.sleep(pollIntervalSeconds * 1000L);
            }
        }

        log.info("Recording complete. {} files in {}", totalPolls, outputDir);
    }
}

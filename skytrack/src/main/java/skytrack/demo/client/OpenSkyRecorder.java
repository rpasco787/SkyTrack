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
        String username = System.getenv("OPENSKY_USERNAME");
        String password = System.getenv("OPENSKY_PASSWORD");
        Path outputDir = Path.of(args.length > 0 ? args[0] : "data/recorded-opensky");

        Files.createDirectories(outputDir);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://opensky-network.org");

        if (username != null && password != null) {
            builder.defaultHeaders(h -> h.setBasicAuth(username, password));
            log.info("Using authenticated access (5s rate limit)");
        } else {
            log.info("Using anonymous access (10s rate limit)");
        }

        RestClient client = builder.build();
        int pollIntervalSeconds = 30;
        int durationMinutes = 185;
        int totalPolls = (durationMinutes * 60) / pollIntervalSeconds;

        log.info("Recording {} polls over {} minutes to {}", totalPolls, durationMinutes, outputDir);

        for (int i = 0; i < totalPolls; i++) {
            try {
                String body = client.get()
                        .uri("/api/states/all")
                        .retrieve()
                        .body(String.class);

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

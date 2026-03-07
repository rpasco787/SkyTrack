package skytrack.demo.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import skytrack.demo.config.OpenSkyProperties;
import skytrack.demo.model.FlightPosition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class LiveOpenSkyClient implements FlightDataSource {

    private static final Logger log = LoggerFactory.getLogger(LiveOpenSkyClient.class);
    private static final String US_ORIGIN = "United States";

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public LiveOpenSkyClient(OpenSkyProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.apiUrl());

        if (properties.username() != null && properties.password() != null) {
            builder.defaultHeaders(headers ->
                    headers.setBasicAuth(properties.username(), properties.password()));
        }

        this.restClient = builder.build();
    }

    @Override
    public List<FlightPosition> fetchPositions() {
        try {
            String body = restClient.get()
                    .uri("/api/states/all")
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(body);
            return parseStateVectors(root);
        } catch (Exception e) {
            log.error("Failed to fetch from OpenSky API", e);
            return List.of();
        }
    }

    static List<FlightPosition> parseStateVectors(JsonNode root) {
        JsonNode states = root.get("states");
        if (states == null || states.isNull()) {
            return List.of();
        }

        List<FlightPosition> positions = new ArrayList<>();
        Instant parsedAt = Instant.now();

        for (JsonNode sv : states) {
            try {
                String originCountry = sv.get(2).asString("");
                if (!US_ORIGIN.equals(originCountry)) {
                    continue;
                }

                JsonNode lonNode = sv.get(5);
                JsonNode latNode = sv.get(6);
                if (lonNode.isNull() || latNode.isNull()) {
                    continue;
                }

                positions.add(new FlightPosition(
                        sv.get(0).asString(),
                        sv.get(1).asString("").trim(),
                        latNode.asDouble(),
                        lonNode.asDouble(),
                        sv.get(7).isNull() ? null : sv.get(7).asDouble(),
                        sv.get(9).isNull() ? null : sv.get(9).asDouble(),
                        sv.get(10).isNull() ? null : sv.get(10).asDouble(),
                        sv.get(8).asBoolean(),
                        sv.get(4).asLong(),
                        sv.get(3).isNull() ? 0L : sv.get(3).asLong(),
                        parsedAt
                ));
            } catch (Exception e) {
                LoggerFactory.getLogger(LiveOpenSkyClient.class)
                        .warn("Failed to parse state vector: {}", sv, e);
            }
        }

        return positions;
    }
}

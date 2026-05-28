package skytrack.demo.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.WeatherObservation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class LiveAviationWeatherClient implements WeatherSource {

    private static final Logger log = LoggerFactory.getLogger(LiveAviationWeatherClient.class);
    private static final Set<String> CEILING_COVERS = Set.of("BKN", "OVC", "VV");

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public LiveAviationWeatherClient(WeatherProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.apiUrl())
                .build();
    }

    @Override
    public List<WeatherObservation> fetchObservations(List<String> airportIcaoCodes) {
        if (airportIcaoCodes.isEmpty()) {
            return List.of();
        }
        String ids = String.join(",", airportIcaoCodes);
        try {
            String body = restClient.get()
                    .uri(uri -> uri.queryParam("ids", ids).queryParam("format", "json").build())
                    .retrieve()
                    .body(String.class);
            return parse(body);
        } catch (Exception e) {
            log.error("Failed to fetch METAR for ids={}: {}", ids, e.getMessage());
            return List.of();
        }
    }

    List<WeatherObservation> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.isArray()) return List.of();
            List<WeatherObservation> result = new ArrayList<>();
            for (JsonNode node : root) {
                WeatherObservation obs = mapMetar(node);
                if (obs != null) result.add(obs);
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse METAR response: {}", e.getMessage());
            return List.of();
        }
    }

    private WeatherObservation mapMetar(JsonNode node) {
        String icao = textOrNull(node, "icaoId");
        if (icao == null) return null;
        Instant observedAt = epochSecondsOrNull(node, "obsTime");
        Double visibility = parseVisibility(textOrNull(node, "visib"));
        Integer ceiling = ceilingFromClouds(node.get("clouds"));
        Integer windSpeed = intOrNull(node, "wspd");
        Integer windGust = intOrNull(node, "wgst");
        // No ceiling layer (CLR/FEW/SCT) means unlimited ceiling — classify by visibility alone.
        Integer ceilingForCategory = ceiling != null ? ceiling : Integer.MAX_VALUE;
        FlightCategory category = FlightCategory.from(visibility, ceilingForCategory);
        String rawOb = textOrNull(node, "rawOb");
        return new WeatherObservation(icao, null, observedAt, visibility,
                ceiling, windSpeed, windGust, category, rawOb);
    }

    static Double parseVisibility(String visib) {
        if (visib == null) return null;
        String trimmed = visib.replace("+", "").trim();
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer ceilingFromClouds(JsonNode clouds) {
        if (clouds == null || !clouds.isArray()) return null;
        Integer lowest = null;
        for (JsonNode layer : clouds) {
            String cover = textOrNull(layer, "cover");
            if (cover == null || !CEILING_COVERS.contains(cover)) continue;
            Integer base = intOrNull(layer, "base");
            if (base == null) continue;
            if (lowest == null || base < lowest) lowest = base;
        }
        return lowest;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asString();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    private static Instant epochSecondsOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return Instant.ofEpochSecond(v.asLong());
    }
}

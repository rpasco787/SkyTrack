package skytrack.demo.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import skytrack.demo.config.AeroApiProperties;
import skytrack.demo.model.FlightSchedule;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class AeroApiClient implements FlightScheduleApiClient {

    private static final Logger log = LoggerFactory.getLogger(AeroApiClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final AeroApiProperties properties;
    private final AtomicInteger callsThisMonth;

    public AeroApiClient(AeroApiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.callsThisMonth = new AtomicInteger(0);
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("x-apikey", properties.apiKey() != null ? properties.apiKey() : "")
                .build();
    }

    @Override
    public Optional<FlightSchedule> getFlightSchedule(String callsign, String date) {
        if (!properties.enabled()) {
            log.debug("AeroAPI disabled, returning empty for callsign={}", callsign);
            return Optional.empty();
        }
        if (!checkBudget()) {
            return Optional.empty();
        }
        try {
            String body = restClient.get()
                    .uri("/flights/{ident}?start={date}&end={date}", callsign, date, date)
                    .retrieve()
                    .body(String.class);
            callsThisMonth.incrementAndGet();
            FlightSchedule schedule = parseFlightFromJson(mapper, body);
            return Optional.ofNullable(schedule);
        } catch (Exception e) {
            log.error("Failed to fetch flight schedule for callsign={}, date={}", callsign, date, e);
            return Optional.empty();
        }
    }

    @Override
    public List<FlightSchedule> getDailyFlights(String date) {
        if (!properties.enabled()) {
            log.debug("AeroAPI disabled, returning empty for date={}", date);
            return List.of();
        }
        if (!checkBudget()) {
            return List.of();
        }
        try {
            String body = restClient.get()
                    .uri("/flights?start={date}&end={date}", date, date)
                    .retrieve()
                    .body(String.class);
            callsThisMonth.incrementAndGet();
            return parseFlightsFromJson(mapper, body);
        } catch (Exception e) {
            log.error("Failed to fetch daily flights for date={}", date, e);
            return List.of();
        }
    }

    public int getRemainingCalls() {
        return properties.maxMonthlyCalls() - callsThisMonth.get();
    }

    boolean checkBudget() {
        int used = callsThisMonth.get();
        int max = properties.maxMonthlyCalls();
        if (used >= max) {
            log.error("AeroAPI monthly budget exhausted: {}/{} calls used", used, max);
            return false;
        }
        if (used >= max * 0.9) {
            log.warn("AeroAPI budget warning: {}/{} calls used (>90%)", used, max);
        }
        return true;
    }

    static FlightSchedule parseFlightFromJson(ObjectMapper mapper, String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode flights = root.get("flights");
        if (flights == null || flights.isEmpty()) {
            return null;
        }
        return mapFlightNode(flights.get(0));
    }

    static List<FlightSchedule> parseFlightsFromJson(ObjectMapper mapper, String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode flights = root.get("flights");
        if (flights == null || flights.isEmpty()) {
            return List.of();
        }
        List<FlightSchedule> result = new ArrayList<>();
        for (JsonNode node : flights) {
            result.add(mapFlightNode(node));
        }
        return result;
    }

    private static FlightSchedule mapFlightNode(JsonNode node) {
        return new FlightSchedule(
                textOrNull(node, "ident"),
                textOrNull(node, "ident_iata"),
                textOrNull(node, "operator"),
                nestedTextOrNull(node, "origin", "code_iata"),
                nestedTextOrNull(node, "destination", "code_iata"),
                instantOrNull(node, "scheduled_out"),
                instantOrNull(node, "scheduled_in"),
                instantOrNull(node, "actual_out"),
                instantOrNull(node, "actual_in"),
                textOrNull(node, "gate_origin"),
                textOrNull(node, "gate_destination"),
                textOrNull(node, "aircraft_type")
        );
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asString();
    }

    private static String nestedTextOrNull(JsonNode node, String parent, String child) {
        JsonNode parentNode = node.get(parent);
        if (parentNode == null || parentNode.isNull()) return null;
        JsonNode childNode = parentNode.get(child);
        return (childNode == null || childNode.isNull()) ? null : childNode.asString();
    }

    private static Instant instantOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        return Instant.parse(value.asString());
    }
}

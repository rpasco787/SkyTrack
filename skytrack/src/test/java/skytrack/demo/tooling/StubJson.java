package skytrack.demo.tooling;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/** Builds the AeroAPI response body ({"flights":[{...}]}) and the full WireMock mapping. */
public final class StubJson {

    private StubJson() {}

    /** The response body the AeroAPI client parses. Only scheduled_in is functionally required. */
    public static String flightsBody(ObjectMapper mapper, SeedRow row, long scheduledInEpoch) throws Exception {
        ObjectNode flight = mapper.createObjectNode();
        flight.put("ident", row.callsign());
        flight.put("ident_iata", row.identIata());
        flight.put("operator", row.icaoCarrier());

        ObjectNode origin = flight.putObject("origin");
        origin.putNull("code");
        origin.putNull("code_iata");

        ObjectNode dest = flight.putObject("destination");
        dest.put("code", row.airportIcao());
        if (row.airportIata().isEmpty()) {
            dest.putNull("code_iata");
        } else {
            dest.put("code_iata", row.airportIata());
        }

        flight.putNull("scheduled_out");
        flight.put("scheduled_in", Instant.ofEpochSecond(scheduledInEpoch).toString());
        flight.putNull("actual_out");
        flight.putNull("actual_in");
        flight.putNull("gate_origin");
        flight.putNull("gate_destination");
        flight.putNull("aircraft_type");

        ObjectNode root = mapper.createObjectNode();
        root.putArray("flights").add(flight);
        return mapper.writeValueAsString(root);
    }

    /** Full WireMock mapping with the body inlined as jsonBody (one file per callsign). */
    public static String mapping(ObjectMapper mapper, SeedRow row, long scheduledInEpoch) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("priority", 5);

        ObjectNode request = root.putObject("request");
        request.put("method", "GET");
        request.put("urlPathPattern", "/aeroapi/flights/" + row.callsign() + ".*");

        ObjectNode response = root.putObject("response");
        response.put("status", 200);
        response.putObject("headers").put("Content-Type", "application/json");
        // Re-parse the body string into a node so it embeds as a JSON object, not a string.
        response.set("jsonBody", mapper.readTree(flightsBody(mapper, row, scheduledInEpoch)));

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }
}

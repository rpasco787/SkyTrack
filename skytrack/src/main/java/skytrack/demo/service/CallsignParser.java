package skytrack.demo.service;

import org.springframework.stereotype.Component;
import skytrack.demo.model.ParsedCallsign;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CallsignParser {

    private static final Pattern CALLSIGN_PATTERN = Pattern.compile("^([A-Z]{3})(\\d+)$");

    private static final Map<String, String> ICAO_TO_IATA = Map.ofEntries(
            Map.entry("UAL", "UA"),
            Map.entry("AAL", "AA"),
            Map.entry("DAL", "DL"),
            Map.entry("SWA", "WN"),
            Map.entry("JBU", "B6"),
            Map.entry("ASA", "AS"),
            Map.entry("NKS", "NK"),
            Map.entry("FFT", "F9"),
            Map.entry("SKW", "OO"),
            Map.entry("RPA", "YX"),
            Map.entry("ENY", "MQ"),
            Map.entry("HAL", "HA"),
            Map.entry("ACA", "AC"),
            Map.entry("WJA", "WS"),
            Map.entry("FDX", "FX"),
            Map.entry("UPS", "5X")
    );

    public Optional<ParsedCallsign> parse(String callsign) {
        if (callsign == null || callsign.isBlank()) return Optional.empty();
        Matcher matcher = CALLSIGN_PATTERN.matcher(callsign.trim().toUpperCase());
        if (!matcher.matches()) return Optional.empty();

        String icaoCode = matcher.group(1);
        String flightNumber = matcher.group(2);
        String iataCode = ICAO_TO_IATA.get(icaoCode);
        if (iataCode == null) return Optional.empty();

        return Optional.of(new ParsedCallsign(icaoCode, flightNumber, iataCode));
    }
}

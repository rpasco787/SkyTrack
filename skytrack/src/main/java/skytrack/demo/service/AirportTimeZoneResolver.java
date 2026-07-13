package skytrack.demo.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class AirportTimeZoneResolver {

    private final Map<String, ZoneId> zones = new HashMap<>();

    public AirportTimeZoneResolver() {
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("airport-timezones.csv").getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length >= 2 && !parts[0].isBlank()) {
                    zones.put(parts[0].trim(), ZoneId.of(parts[1].trim()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load airport-timezones.csv", e);
        }
    }

    public Optional<ZoneId> zoneFor(String iata) {
        return iata == null ? Optional.empty() : Optional.ofNullable(zones.get(iata));
    }
}

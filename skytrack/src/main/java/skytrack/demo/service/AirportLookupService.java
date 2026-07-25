package skytrack.demo.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import skytrack.demo.model.Airport;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class AirportLookupService {

    private static final Logger log = LoggerFactory.getLogger(AirportLookupService.class);
    private static final double EARTH_RADIUS_KM = 6371.0;

    private final Path csvPath;
    private List<Airport> airports = List.of();

    public AirportLookupService(
            @Value("${skytrack.airports.csv-path:data/airports/airports.csv}") String csvPath) {
        this.csvPath = Path.of(csvPath);
    }

    @PostConstruct
    public void loadAirports() throws IOException {
        List<Airport> loaded = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                if (fields.length < 14) continue;
                String isoCountry = unquote(fields[8]);
                String type = unquote(fields[2]);
                if (!"US".equals(isoCountry)) continue;
                if (!"large_airport".equals(type) && !"medium_airport".equals(type)) continue;

                try {
                    loaded.add(new Airport(
                            unquote(fields[1]),   // ident
                            unquote(fields[12]),  // icao_code
                            unquote(fields[13]),  // iata_code
                            unquote(fields[3]),   // name
                            Double.parseDouble(unquote(fields[4])),  // latitude_deg
                            Double.parseDouble(unquote(fields[5])),  // longitude_deg
                            type
                    ));
                } catch (NumberFormatException e) {
                    log.warn("Skipping airport with invalid coordinates: {}", unquote(fields[1]));
                }
            }
        }
        this.airports = List.copyOf(loaded);
        log.info("Loaded {} US airports (large + medium)", airports.size());
    }

    public Optional<Airport> findNearest(double lat, double lon, double maxDistanceKm) {
        Airport nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Airport airport : airports) {
            double dist = haversineKm(lat, lon, airport.latitude(), airport.longitude());
            if (dist < minDist && dist <= maxDistanceKm) {
                minDist = dist;
                nearest = airport;
            }
        }
        return Optional.ofNullable(nearest);
    }

    public Optional<Airport> findByIcao(String icaoCode) {
        return airports.stream()
                .filter(a -> icaoCode.equals(a.icaoCode()) || icaoCode.equals(a.ident()))
                .findFirst();
    }

    public Optional<Airport> findByIata(String iataCode) {
        if (iataCode == null || iataCode.isBlank()) return Optional.empty();
        return airports.stream()
                .filter(a -> iataCode.equals(a.iataCode()))
                .findFirst();
    }

    public int count() {
        return airports.size();
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}

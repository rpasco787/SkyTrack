package skytrack.demo.testing;

import skytrack.demo.model.FlightPosition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class SyntheticFlightGenerator {

    // lat, lon for major US hubs
    private static final Map<String, double[]> AIRPORT_COORDS = Map.of(
            "ORD", new double[]{41.9742, -87.9073},
            "ATL", new double[]{33.6367, -84.4281},
            "JFK", new double[]{40.6413, -73.7781},
            "LAX", new double[]{33.9425, -118.4081},
            "MIA", new double[]{25.7959, -80.2870}
    );

    private static final String[] CALLSIGN_PREFIXES = {"UAL", "DAL", "AAL", "SWA", "SKW"};

    private SyntheticFlightGenerator() {}

    /**
     * Generates N flight legs where each leg's departure delay decays from the initial delay.
     * Models real-world delay propagation through a rotated aircraft schedule.
     */
    public static List<FlightPosition> generateCascadeScenario(int legs, int initialDelayMinutes, double decayFactor) {
        List<FlightPosition> all = new ArrayList<>();
        long baseTime = Instant.now().getEpochSecond();
        double delaySeconds = initialDelayMinutes * 60.0;

        for (int leg = 0; leg < legs; leg++) {
            String callsign = CALLSIGN_PREFIXES[leg % CALLSIGN_PREFIXES.length] + (1000 + leg);
            double[] coords = airportCoordsForIndex(leg);
            long legBaseTime = baseTime + (long)(leg * 7200) + (long) delaySeconds;

            // Generate ~10 position snapshots per leg
            for (int p = 0; p < 10; p++) {
                long t = legBaseTime + (p * 300L);
                double progress = p / 9.0;
                double[] dest = airportCoordsForIndex(leg + 1);
                double lat = coords[0] + (dest[0] - coords[0]) * progress;
                double lon = coords[1] + (dest[1] - coords[1]) * progress;
                double heading = Math.toDegrees(Math.atan2(dest[1] - coords[1], dest[0] - coords[0]));
                if (heading < 0) heading += 360;

                all.add(new FlightPosition(
                        "icao" + leg + p, callsign,
                        lat, lon,
                        30000.0 - (p == 0 || p == 9 ? 30000.0 : 0),
                        450.0, heading,
                        p == 0 || p == 9,
                        t, t - 5,
                        Instant.ofEpochSecond(t)
                ));
            }

            delaySeconds *= decayFactor;
        }

        all.sort(Comparator.comparingLong(FlightPosition::lastContact));
        return all;
    }

    /**
     * Generates ~20 positions for a single aircraft circling near an airport in a holding pattern.
     */
    public static List<FlightPosition> generateHoldingPattern(String callsign, String airport) {
        double[] center = AIRPORT_COORDS.getOrDefault(airport, new double[]{41.9742, -87.9073});
        List<FlightPosition> positions = new ArrayList<>();
        long baseTime = Instant.now().getEpochSecond();
        double radius = 0.15; // ~15km radius

        for (int i = 0; i < 20; i++) {
            double angle = (2 * Math.PI / 20) * i;
            double lat = center[0] + radius * Math.sin(angle);
            double lon = center[1] + radius * Math.cos(angle);
            double heading = (Math.toDegrees(angle) + 90) % 360;
            long t = baseTime + (i * 60L);

            positions.add(new FlightPosition(
                    "icao-hold-" + i, callsign,
                    lat, lon,
                    10000.0, 250.0, heading,
                    false,
                    t, t - 5,
                    Instant.ofEpochSecond(t)
            ));
        }

        positions.sort(Comparator.comparingLong(FlightPosition::lastContact));
        return positions;
    }

    /**
     * Generates N flights all arriving at the same airport within a given window (minutes).
     */
    public static List<FlightPosition> generateBurstArrivals(String airport, int count, int windowMinutes) {
        double[] dest = AIRPORT_COORDS.getOrDefault(airport, new double[]{41.9742, -87.9073});
        List<FlightPosition> positions = new ArrayList<>();
        long baseTime = Instant.now().getEpochSecond();
        long windowSeconds = windowMinutes * 60L;

        for (int i = 0; i < count; i++) {
            String callsign = CALLSIGN_PREFIXES[i % CALLSIGN_PREFIXES.length] + (500 + i);
            long arrivalOffset = (windowSeconds / count) * i;
            double[] origin = airportCoordsForIndex(i + 1);

            // 5 position snapshots per flight, converging on destination
            for (int p = 0; p < 5; p++) {
                long t = baseTime + arrivalOffset - ((4 - p) * 300L);
                double progress = p / 4.0;
                double lat = origin[0] + (dest[0] - origin[0]) * progress;
                double lon = origin[1] + (dest[1] - origin[1]) * progress;
                double heading = Math.toDegrees(Math.atan2(dest[1] - origin[1], dest[0] - origin[0]));
                if (heading < 0) heading += 360;

                positions.add(new FlightPosition(
                        "icao-burst-" + i + "-" + p, callsign,
                        lat, lon,
                        p == 4 ? 0.0 : 25000.0,
                        p == 4 ? 0.0 : 430.0,
                        heading,
                        p == 4,
                        t, t - 5,
                        Instant.ofEpochSecond(t)
                ));
            }
        }

        positions.sort(Comparator.comparingLong(FlightPosition::lastContact));
        return positions;
    }

    private static double[] airportCoordsForIndex(int index) {
        String[] airports = {"ORD", "ATL", "JFK", "LAX", "MIA"};
        return AIRPORT_COORDS.get(airports[index % airports.length]);
    }
}

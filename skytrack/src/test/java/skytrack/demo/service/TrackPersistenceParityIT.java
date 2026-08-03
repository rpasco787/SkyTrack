package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.client.ReplayOpenSkyClient;
import skytrack.demo.config.OpenSkyProperties;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.AircraftTrack;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.model.LandingEvent;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conditional persistence changes what the state machine reads back, so it can only be trusted if
 * landing detection does not regress across the full replay.
 *
 * <p><strong>Exact parity is not achievable by design.</strong> The stale check cannot distinguish a
 * genuine coverage gap from a deferred write using persisted state alone, so the error can be made
 * small or flipped in sign but never eliminated. Measured over this replay: without the widened
 * stale window, a 120s interval <em>drops</em> 63 of 4109 landings (1.5%); with it, the same
 * interval detects 17 extra (0.4%) while cutting writes 76%. This test therefore asserts the
 * property that actually matters — landings are never lost — and bounds the gain.
 *
 * <p>Gated — run with:
 *   cd skytrack && ./mvnw test -Dtest=TrackPersistenceParityIT -Dskytrack.backtest=true
 */
class TrackPersistenceParityIT {

    private static final String REPLAY_DIR = "./skytrack/data/recorded-opensky/";

    @Test
    void conditionalPersistenceNeverDetectsFewerLandingsThanPersistingEveryPosition() throws Exception {
        assumeTrue(Boolean.getBoolean("skytrack.backtest"),
                "Full-replay parity harness — run with -Dskytrack.backtest=true");
        assumeTrue(Files.exists(Path.of("skytrack/data/recorded-opensky/")));

        int always = countLandings(0);        // persist every position (today's behaviour)
        int conditional = countLandings(120); // persist-interval 120s

        assertThat(always).isGreaterThan(0);
        assertThat(conditional)
                .as("conditional persistence must never drop landings; a shortfall means tracks are "
                        + "going stale and resetting to UNKNOWN, and landings from UNKNOWN are not "
                        + "emitted — that is the exact failure this harness exists to catch")
                .isGreaterThanOrEqualTo(always);
        assertThat(conditional - always)
                .as("the widened stale window must not inflate detections materially; a large "
                        + "excess means tracks that genuinely went dark are keeping their state")
                .isLessThanOrEqualTo(always / 100);
    }

    private int countLandings(long persistIntervalSeconds) throws Exception {
        var airportLookup = new AirportLookupService("data/airports/airports.csv");
        airportLookup.loadAirports();
        var props = new StateMachineProperties(150, 50, 5, 300,
                persistIntervalSeconds == 0 ? 1 : persistIntervalSeconds);
        var stateMachine = new AircraftStateMachine(airportLookup, props);
        var replay = new ReplayOpenSkyClient(
                new OpenSkyProperties("replay", null, null, null, REPLAY_DIR, 1), new ObjectMapper());

        Map<String, AircraftTrack> persisted = new HashMap<>();
        List<LandingEvent> landings = new ArrayList<>();

        for (List<FlightPosition> batch = replay.fetchPositions();
             !batch.isEmpty();
             batch = replay.fetchPositions()) {
            for (FlightPosition pos : batch) {
                AircraftTrack stored = persisted.computeIfAbsent(pos.icao24(), AircraftTrack::initial);
                Long previousLastSeen = stored.getLastSeen();

                // Copy, so a skipped "write" genuinely discards the mutation the way DynamoDB does.
                AircraftTrack working = copyOf(stored);
                var result = stateMachine.process(working, pos);

                boolean persist = persistIntervalSeconds == 0
                        || result.stateChanged()
                        || result.landingEvent().isPresent()
                        || previousLastSeen == null
                        || (pos.lastContact() - previousLastSeen) >= persistIntervalSeconds;
                if (persist) {
                    persisted.put(pos.icao24(), result.updatedTrack());
                }
                result.landingEvent().ifPresent(landings::add);
            }
        }
        return landings.size();
    }

    private static AircraftTrack copyOf(AircraftTrack t) {
        return AircraftTrack.builder()
                .icao24(t.getIcao24()).sortKey(t.getSortKey()).state(t.getState())
                .callsign(t.getCallsign()).latitude(t.getLatitude()).longitude(t.getLongitude())
                .baroAltitude(t.getBaroAltitude()).lastSeen(t.getLastSeen())
                .nearestAirportIcao(t.getNearestAirportIcao()).stateEnteredAt(t.getStateEnteredAt())
                .updatedAt(t.getUpdatedAt()).ttl(t.getTtl())
                .build();
    }
}

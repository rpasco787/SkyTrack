package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.client.ReplayOpenSkyClient;
import skytrack.demo.config.OpenSkyProperties;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.AircraftTrack;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.tooling.SeedBuilder;
import skytrack.demo.tooling.SeedRow;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tooling: replays all recorded OpenSky files through the REAL state machine in-memory and
 * writes wiremock/seed/landing-seed.jsonl. Gated — run with:
 *   cd skytrack && ./mvnw test -Dtest=LandingSeedExtractorIT -Dskytrack.tooling=true
 */
class LandingSeedExtractorIT {

    @Test
    void extractLandingSeed() throws Exception {
        assumeTrue(Boolean.getBoolean("skytrack.tooling"),
                "Tooling test — run with -Dskytrack.tooling=true");

        // Real components, no Spring. Paths resolve from the repo root (surefire workingDirectory=..).
        var airportLookup = new AirportLookupService("data/airports/airports.csv");
        airportLookup.loadAirports();
        var stateMachine = new AircraftStateMachine(
                airportLookup, new StateMachineProperties(150, 50, 5, 300, 120));
        var replay = new ReplayOpenSkyClient(
                new OpenSkyProperties("replay", null, null, null,
                        "./skytrack/data/recorded-opensky/", 1),
                new ObjectMapper());

        Map<String, AircraftTrack> tracks = new HashMap<>();
        List<LandingEvent> landings = new ArrayList<>();

        int files = 0;
        for (List<FlightPosition> batch = replay.fetchPositions();
             !batch.isEmpty();
             batch = replay.fetchPositions()) {
            files++;
            for (FlightPosition pos : batch) {
                AircraftTrack track = tracks.computeIfAbsent(
                        pos.icao24(), AircraftTrack::initial);
                var result = stateMachine.process(track, pos);
                tracks.put(pos.icao24(), result.updatedTrack());
                result.landingEvent().ifPresent(landings::add);
            }
        }

        List<SeedRow> rows = new SeedBuilder(new CallsignParser()).build(landings);

        Path out = Path.of("wiremock/seed/landing-seed.jsonl");
        Files.createDirectories(out.getParent());
        ObjectMapper json = new ObjectMapper();
        try (var w = Files.newBufferedWriter(out)) {
            for (SeedRow r : rows) {
                w.write(json.writeValueAsString(r));
                w.newLine();
            }
        }
        System.out.printf("Extracted %d landings -> %d seed rows from %d files -> %s%n",
                landings.size(), rows.size(), files, out.toAbsolutePath());
    }
}

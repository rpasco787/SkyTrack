package skytrack.demo.tooling;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tooling: reads wiremock/seed/landing-seed.jsonl and writes wiremock/generated/gen-<CALLSIGN>.json.
 * Gated — run with:
 *   cd skytrack && ./mvnw test -Dtest=WireMockStubGeneratorIT -Dskytrack.tooling=true
 */
class WireMockStubGeneratorIT {

    private static final long RNG_SEED = 20260616L;
    private static final Set<String> HOTSPOTS = Set.of("ORD", "JFK");
    // Callsigns with hand-written stubs / fixtures — do not overwrite.
    private static final Set<String> RESERVED = Set.of("AAL100", "DAL567", "UAL1234");

    @Test
    void generateStubs() throws Exception {
        assumeTrue(Boolean.getBoolean("skytrack.tooling"),
                "Tooling test — run with -Dskytrack.tooling=true");

        ObjectMapper mapper = new ObjectMapper();
        List<SeedRow> rows = readSeed(mapper, Path.of("wiremock/seed/landing-seed.jsonl"));
        // Stable order -> reproducible RNG assignment.
        rows.sort((a, b) -> a.callsign().compareTo(b.callsign()));

        Path outDir = Path.of("wiremock/generated");
        if (Files.isDirectory(outDir)) {                 // clean prior run
            try (Stream<Path> old = Files.list(outDir)) {
                old.filter(p -> p.getFileName().toString().startsWith("gen-"))
                   .forEach(WireMockStubGeneratorIT::deleteQuietly);
            }
        }
        Files.createDirectories(outDir);

        var rng = new SplittableRandom(RNG_SEED);
        int written = 0, skipped = 0;
        for (SeedRow row : rows) {
            if (RESERVED.contains(row.callsign())) { skipped++; continue; }
            boolean hotspot = HOTSPOTS.contains(row.airportIata());
            var band = DelayModel.chooseBand(rng.nextDouble(), hotspot);
            long delay = DelayModel.sampleDelaySeconds(band, rng);
            long scheduledIn = row.arrivalEpoch() - delay;

            Files.writeString(outDir.resolve("gen-" + row.callsign() + ".json"),
                    StubJson.mapping(mapper, row, scheduledIn));
            written++;
        }
        System.out.printf("Wrote %d stubs (skipped %d reserved) to %s%n",
                written, skipped, outDir.toAbsolutePath());
    }

    private static List<SeedRow> readSeed(ObjectMapper mapper, Path seed) throws IOException {
        try (Stream<String> lines = Files.lines(seed)) {
            return lines.filter(l -> !l.isBlank())
                    .map(l -> readRow(mapper, l))
                    .collect(Collectors.toList());
        }
    }

    private static SeedRow readRow(ObjectMapper mapper, String line) {
        try { return mapper.readValue(line, SeedRow.class); }
        catch (Exception e) { throw new RuntimeException("Bad seed line: " + line, e); }
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }
}

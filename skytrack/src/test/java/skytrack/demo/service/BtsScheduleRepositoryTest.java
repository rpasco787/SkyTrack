package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.BtsFlightRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BtsScheduleRepositoryTest {

    private final BtsFlightRecord inbound  = new BtsFlightRecord("UA","1234","N12345","DEN","ORD", 1773080000L, 1773083600L, 600L, false, null, null);
    private final BtsFlightRecord outbound = new BtsFlightRecord("UA","5678","N12345","ORD","LAX", 1773090000L, 1773093600L, 900L, false, null, null);
    private final BtsFlightRecord otherTail= new BtsFlightRecord("AA","99","N999","ORD","MIA",     1773091000L, 1773094600L, 0L,   false, null, null);

    private final BtsScheduleRepository repo =
            new BtsScheduleRepository(List.of(inbound, outbound, otherTail));

    @Test
    void findsInboundLegByCarrierFlightAndDest() {
        Optional<BtsFlightRecord> rec = repo.findInboundLeg("UA","1234","ORD", 1773081000L);
        assertThat(rec).contains(inbound);
        assertThat(rec.get().tailNumber()).isEqualTo("N12345");
    }

    @Test
    void findsNextDepartureForTailAfterLanding() {
        Optional<BtsFlightRecord> next = repo.findNextDeparture("N12345","ORD", 1773085000L);
        assertThat(next).contains(outbound);
    }

    @Test
    void noNextDepartureWhenAllBeforeCutoff() {
        assertThat(repo.findNextDeparture("N12345","ORD", 1773099999L)).isEmpty();
    }

    @Test
    void computesRouteRecoveryFactors() {
        // Two ORD→LAX legs both with lateAircraftDelaySeconds > 0:
        //   legA: depDelay=3600s, arrDelay=2700s → recovery=(3600-2700)/3600 = 0.25
        //   legB: depDelay=3600s, arrDelay=3240s → recovery=(3600-3240)/3600 = 0.10
        // sorted = [0.10, 0.25], size=2, median index = 2/2 = 1 → 0.25
        var legA = new BtsFlightRecord("UA","1","N1","ORD","LAX",1000L,5000L,3600L,false,2700L,1800L);
        var legB = new BtsFlightRecord("UA","2","N2","ORD","LAX",2000L,6000L,3600L,false,3240L, 900L);
        var repo = TestRepos.of(legA, legB);

        Map<String, Double> factors = repo.medianRecoveryFactorByRoute();

        assertThat(factors).containsKey("ORD-LAX");
        assertThat(factors.get("ORD-LAX")).isCloseTo(0.25, within(0.001));
    }

    @Test
    void computesCarrierMedianTurnaround() {
        // UA tail N12345: DEN→ORD scheduled arr=1773083600, then ORD→LAX scheduled dep=1773090000
        // gap = 1773090000 - 1773083600 = 6400s (in [300, 14400] window)
        var repo = TestRepos.of(inbound, outbound);

        Map<String, Long> turnarounds = repo.medianTurnaroundSecondsByCarrier();

        assertThat(turnarounds).containsKey("UA");
        assertThat(turnarounds.get("UA")).isEqualTo(6400L);
    }

    @Test
    void findInboundLegStillPicksNearestScheduledDeparture() {
        // Same carrier+flight+dest on two different days; nearest wins.
        var repo = TestRepos.of(
                rec("UA", "100", "N1", "IAH", "ORD", 1000L, 5000L),
                rec("UA", "100", "N1", "IAH", "ORD", 900_000L, 905_000L));
        assertThat(repo.findInboundLeg("UA", "100", "ORD", 5200L))
                .get().extracting(BtsFlightRecord::scheduledDepEpoch).isEqualTo(1000L);
    }

    @Test
    void fromCsvAppliesEpochWindowFilter() throws Exception {
        Path csv = writeTempCsv();  // two rows: one 2026-03-09, one 2026-03-20
        var all = BtsScheduleRepository.fromCsv(csv.toString(), tz());
        var filtered = BtsScheduleRepository.fromCsv(
                csv.toString(), tz(), 1773014400L, 1773187200L);  // 2026-03-09 UTC day
        assertThat(all.size()).isEqualTo(2);
        assertThat(filtered.size()).isEqualTo(1);
    }

    private static BtsFlightRecord rec(String carrierIata, String flightNumber, String tailNumber,
                                        String origin, String dest,
                                        long scheduledDepEpoch, long scheduledArrEpoch) {
        return new BtsFlightRecord(carrierIata, flightNumber, tailNumber, origin, dest,
                scheduledDepEpoch, scheduledArrEpoch, null, false, null, null);
    }

    private static AirportTimeZoneResolver tz() {
        return new AirportTimeZoneResolver();
    }

    private Path writeTempCsv() throws Exception {
        Path csv = Files.createTempFile("bts-window-test", ".csv");
        List<String> lines = List.of(
                "FL_DATE,OP_UNIQUE_CARRIER,OP_CARRIER_FL_NUM,TAIL_NUM,ORIGIN,DEST,"
                        + "CRS_DEP_TIME,CRS_ARR_TIME,CANCELLED,DEP_DELAY,ARR_DELAY,LATE_AIRCRAFT_DELAY",
                "2026-03-09,UA,100,N1,ORD,ORD,1200,1300,0,0,0,0",
                "2026-03-20,UA,200,N2,ORD,ORD,1200,1300,0,0,0,0");
        Files.write(csv, lines);
        csv.toFile().deleteOnExit();
        return csv;
    }
}

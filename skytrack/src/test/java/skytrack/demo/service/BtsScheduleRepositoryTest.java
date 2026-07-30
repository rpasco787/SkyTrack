package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.BtsFlightRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    void returnsDepartureInsideLookaheadWindow() {
        // outbound departs 5000s after the cutoff; a 7200s window includes it
        assertThat(repo.findNextDeparture("N12345", "ORD", 1773085000L, 7200L)).contains(outbound);
    }

    @Test
    void skipsDepartureBeyondLookaheadWindow() {
        // Same outbound, 5000s out, but only a 3600s window. Past that the aircraft has
        // overnighted and any delay on the next leg is causally unrelated to this arrival.
        assertThat(repo.findNextDeparture("N12345", "ORD", 1773085000L, 3600L)).isEmpty();
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
    void findInboundLegMatchesOnScheduledArrivalNotScheduledDeparture() {
        // Two same carrier+flight+dest legs. Matching on scheduled *departure* picks legB
        // (|6000-8100| = 2100 beats |1000-8100| = 7100); matching on scheduled *arrival*
        // picks legA (|8000-8100| = 100 beats |13000-8100| = 4900). legA is the leg that
        // actually landed near 8100.
        var legA = rec("UA", "100", "N1", "IAH", "ORD", 1000L, 8000L);
        var legB = rec("UA", "100", "N1", "IAH", "ORD", 6000L, 13000L);
        var repo = TestRepos.of(legA, legB);

        assertThat(repo.findInboundLeg("UA", "100", "ORD", 8100L)).contains(legA);
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

    // --- previous leg on a tail's rotation -----------------------------------------------------

    @Test
    void findsTheLegImmediatelyPrecedingADepartureOnTheSameTail() {
        // Three legs on N1. The leg before the 09:00 departure is the one departing at 06:00,
        // not the earliest one on the day.
        var first  = rec("UA", "1", "N1", "IAH", "DEN", 1_000L, 5_000L);
        var second = rec("UA", "2", "N1", "DEN", "ORD", 6_000L, 9_000L);
        var third  = rec("UA", "3", "N1", "ORD", "LAX", 10_000L, 14_000L);
        var repo = TestRepos.of(first, second, third);

        assertThat(repo.findPreviousLeg("N1", 10_000L)).contains(second);
        assertThat(repo.findPreviousLeg("N1", 6_000L)).contains(first);
    }

    @Test
    void hasNoPreviousLegForTheFirstDepartureOfATail() {
        var repo = TestRepos.of(rec("UA", "1", "N1", "IAH", "DEN", 1_000L, 5_000L));

        assertThat(repo.findPreviousLeg("N1", 1_000L)).isEmpty();
    }

    @Test
    void previousLegIgnoresOtherAircraft() {
        var mine   = rec("UA", "1", "N1", "IAH", "DEN", 1_000L, 5_000L);
        var theirs = rec("AA", "9", "N2", "MIA", "DEN", 6_000L, 8_000L);
        var repo = TestRepos.of(mine, theirs);

        assertThat(repo.findPreviousLeg("N1", 9_000L)).contains(mine);
    }

    @Test
    void previousLegIsEmptyForAnUnknownTail() {
        assertThat(TestRepos.of().findPreviousLeg("N404", 1_000L)).isEmpty();
        assertThat(TestRepos.of().findPreviousLeg(null, 1_000L)).isEmpty();
    }

    // --- pressured turnaround (p15 of actual ground time under time pressure) ----------------

    @Test
    void computesP15OfActualTurnaroundPerCarrierAndAirport() {
        // 30 pressured rotations with turnarounds 600,700,...,3500. Sorted, p15 index is
        // (int)(30 * 0.15) = 4, so the 5th smallest wins: 1000s.
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 30; i++) {
            records.addAll(pressuredRotation("N" + i, "UA", "ORD", 600 + 100L * i));
        }

        Map<String, Long> p15 = TestRepos.of(records.toArray(BtsFlightRecord[]::new))
                .pressuredTurnaroundP15ByCarrierAirport();

        assertThat(p15).containsEntry("UA|ORD", 1000L);
    }

    @Test
    void computesP50OfActualTurnaroundFromTheSamePressuredPopulation() {
        // Same 30 rotations as the p15 test (600,700,...,3500). p50 index is
        // (int)(30 * 0.5) = 15, so the 16th smallest: 2100s — the typical turnaround under
        // pressure, not the floor. Prediction needs this; slack needs the p15.
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 30; i++) {
            records.addAll(pressuredRotation("N" + i, "UA", "ORD", 600 + 100L * i));
        }
        var repo = TestRepos.of(records.toArray(BtsFlightRecord[]::new));

        assertThat(repo.pressuredTurnaroundP50ByCarrierAirport()).containsEntry("UA|ORD", 2100L);
        assertThat(repo.pressuredTurnaroundP15ByCarrierAirport()).containsEntry("UA|ORD", 1000L);
    }

    @Test
    void p50TurnaroundAlsoExcludesRotationsWhereTheInboundArrivedOnTime() {
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 30; i++) {
            records.addAll(pressuredRotation("P" + i, "UA", "ORD", 1800L));
            records.addAll(rotation("Q" + i, "UA", "ORD", 100_000L, 300L, 100_900L, 0L, false));
        }

        assertThat(TestRepos.of(records.toArray(BtsFlightRecord[]::new))
                .pressuredTurnaroundP50ByCarrierAirport())
                .containsEntry("UA|ORD", 1800L);
    }

    @Test
    void measuresActualTurnaroundRatherThanScheduledGroundTime() {
        // Inbound lands 60 min late into a 70-minute scheduled gap: the crew really had 10
        // minutes. Scheduled ground time reports 4200s; the physical floor is 600s.
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 30; i++) {
            records.addAll(rotation("N" + i, "UA", "ORD", 100_000L, 3600L, 104_200L, 0L, false));
        }
        var repo = TestRepos.of(records.toArray(BtsFlightRecord[]::new));

        assertThat(repo.pressuredTurnaroundP15ByCarrierAirport()).containsEntry("UA|ORD", 600L);
        assertThat(repo.medianTurnaroundSecondsByCarrier()).containsEntry("UA", 4200L);
    }

    @Test
    void excludesRotationsWhereTheInboundArrivedOnTime() {
        // 30 pressured rotations at 1800s and 30 unpressured ones at 600s. Including the
        // unpressured legs would drag p15 of the pooled 60 down to 600s.
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 30; i++) {
            records.addAll(pressuredRotation("P" + i, "UA", "ORD", 1800L));
            records.addAll(rotation("Q" + i, "UA", "ORD", 100_000L, 300L, 100_900L, 0L, false));
        }

        assertThat(TestRepos.of(records.toArray(BtsFlightRecord[]::new))
                .pressuredTurnaroundP15ByCarrierAirport())
                .containsEntry("UA|ORD", 1800L);
    }

    @Test
    void backsOffToCarrierLevelWhenNoSingleAirportHasEnoughSamples() {
        // 15 rotations at each of two airports: neither airport clears 30, the carrier does.
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 15; i++) {
            records.addAll(pressuredRotation("A" + i, "UA", "ORD", 1000L));
            records.addAll(pressuredRotation("B" + i, "UA", "DEN", 2000L));
        }

        Map<String, Long> p15 = TestRepos.of(records.toArray(BtsFlightRecord[]::new))
                .pressuredTurnaroundP15ByCarrierAirport();

        assertThat(p15).doesNotContainKey("UA|ORD").doesNotContainKey("UA|DEN");
        assertThat(p15).containsEntry("UA", 1000L);
    }

    @Test
    void skipsTurnaroundsOutsideTheSanityClamp() {
        // 30 plausible rotations at 1800s, plus 30 implausibly short (60s, bad data) and 30
        // implausibly long (20000s, really an overnight). Unclamped, p15 would be 60s.
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 30; i++) {
            records.addAll(pressuredRotation("P" + i, "UA", "ORD", 1800L));
            records.addAll(pressuredRotation("S" + i, "UA", "ORD", 60L));
            records.addAll(pressuredRotation("L" + i, "UA", "ORD", 20_000L));
        }

        assertThat(TestRepos.of(records.toArray(BtsFlightRecord[]::new))
                .pressuredTurnaroundP15ByCarrierAirport())
                .containsEntry("UA|ORD", 1800L);
    }

    @Test
    void ignoresConsecutiveLegsThatDoNotFormARotation() {
        // Outbound leaves an airport the inbound never landed at — not a turnaround at all.
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 30; i++) {
            records.add(leg("UA", "IN", "N" + i, "DEN", "ORD", 96_000L, 100_000L, 0L, false, 1200L));
            records.add(leg("UA", "OUT", "N" + i, "SFO", "LAX", 101_800L, 108_000L, 0L, false, null));
        }

        assertThat(TestRepos.of(records.toArray(BtsFlightRecord[]::new))
                .pressuredTurnaroundP15ByCarrierAirport()).isEmpty();
    }

    @Test
    void excludesRotationsWithMissingDelayData() {
        // 29 usable rotations plus 30 whose outbound departure delay was never reported.
        // If the incomplete ones counted, the bucket would clear the 30-sample floor.
        var records = new ArrayList<BtsFlightRecord>();
        for (int i = 0; i < 29; i++) {
            records.addAll(pressuredRotation("P" + i, "UA", "ORD", 1800L));
        }
        for (int i = 0; i < 30; i++) {
            records.add(leg("UA", "IN", "M" + i, "DEN", "ORD", 96_000L, 100_000L, 0L, false, 1200L));
            records.add(leg("UA", "OUT", "M" + i, "ORD", "LAX", 101_800L, 108_000L, null, true, null));
        }

        assertThat(TestRepos.of(records.toArray(BtsFlightRecord[]::new))
                .pressuredTurnaroundP15ByCarrierAirport()).isEmpty();
    }

    /** A rotation whose inbound landed 20 minutes late, so it counts as pressured. */
    private static List<BtsFlightRecord> pressuredRotation(String tail, String carrier,
                                                            String airport, long turnaroundSeconds) {
        return rotation(tail, carrier, airport, 100_000L, 1200L, 101_200L + turnaroundSeconds, 0L, false);
    }

    private static List<BtsFlightRecord> rotation(String tail, String carrier, String airport,
                                                   long inboundSchedArr, long inboundArrDelay,
                                                   long outboundSchedDep, long outboundDepDelay,
                                                   boolean outboundCancelled) {
        return List.of(
                leg(carrier, "IN", tail, "DEN", airport, inboundSchedArr - 4000, inboundSchedArr,
                        0L, false, inboundArrDelay),
                leg(carrier, "OUT", tail, airport, "LAX", outboundSchedDep, outboundSchedDep + 7000,
                        outboundDepDelay, outboundCancelled, null));
    }

    private static BtsFlightRecord leg(String carrier, String flightNumber, String tail,
                                        String origin, String dest, long schedDep, Long schedArr,
                                        Long depDelay, boolean cancelled, Long arrDelay) {
        return new BtsFlightRecord(carrier, flightNumber, tail, origin, dest, schedDep, schedArr,
                depDelay, cancelled, arrDelay, null);
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

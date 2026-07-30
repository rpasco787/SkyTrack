package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.BtsFlightRecord;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineDelayPriorTest {

    private static final AirportTimeZoneResolver TZ = new AirportTimeZoneResolver();

    @Test
    void usesCarrierOriginHourMedianWhenBucketHasEnoughSamples() {
        var prior = fit(concat(
                legs("UA", "ORD", localEpoch("ORD", 9, 14), 30, 600L),
                legs("UA", "ORD", localEpoch("ORD", 9, 9), 30, 60L)));

        assertThat(prior.priorSeconds("UA", "ORD", localEpoch("ORD", 9, 14))).isEqualTo(600L);
        assertThat(prior.priorSeconds("UA", "ORD", localEpoch("ORD", 9, 9))).isEqualTo(60L);
    }

    @Test
    void bucketsByHourOfDayNotAbsoluteDate() {
        // Fitted on 2026-03-09; queried on 2026-03-20. Same local hour must hit the same bucket,
        // otherwise the prior degenerates into a per-timestamp lookup that never generalises.
        var prior = fit(concat(
                legs("UA", "ORD", localEpoch("ORD", 9, 14), 30, 600L),
                legs("UA", "ORD", localEpoch("ORD", 9, 9), 30, 60L)));

        assertThat(prior.priorSeconds("UA", "ORD", localEpoch("ORD", 20, 14))).isEqualTo(600L);
        assertThat(prior.priorSeconds("UA", "ORD", localEpoch("ORD", 20, 9))).isEqualTo(60L);
    }

    @Test
    void backsOffToCarrierOriginWhenHourBucketIsSparse() {
        // (UA,ORD,14) has 5 samples -> too sparse. (UA,ORD) has 35 -> median 300.
        var prior = fit(concat(
                legs("UA", "ORD", localEpoch("ORD", 9, 14), 5, 6000L),
                legs("UA", "ORD", localEpoch("ORD", 9, 9), 30, 300L)));

        assertThat(prior.priorSeconds("UA", "ORD", localEpoch("ORD", 9, 14))).isEqualTo(300L);
    }

    @Test
    void backsOffToOriginHourWhenCarrierOriginIsSparse() {
        // (UA,ORD,14)=5 and (UA,ORD)=5 -> sparse. (ORD,14)=35 across carriers -> median 120.
        var prior = fit(concat(
                legs("UA", "ORD", localEpoch("ORD", 9, 14), 5, 6000L),
                legs("DL", "ORD", localEpoch("ORD", 9, 14), 30, 120L)));

        assertThat(prior.priorSeconds("UA", "ORD", localEpoch("ORD", 9, 14))).isEqualTo(120L);
    }

    @Test
    void backsOffToCarrierWhenOriginHourIsSparse() {
        // UA has 35 legs but only 5 at ORD, and ORD hour 14 has only those 5.
        // Carrier median (180) must win over the global median (3000).
        var prior = fit(concat(
                legs("UA", "ORD", localEpoch("ORD", 9, 14), 5, 6000L),
                legs("UA", "DEN", localEpoch("DEN", 9, 9), 30, 180L),
                legs("AA", "MIA", localEpoch("MIA", 9, 9), 30, 3000L)));

        assertThat(prior.priorSeconds("UA", "ORD", localEpoch("ORD", 9, 14))).isEqualTo(180L);
    }

    @Test
    void fallsBackToGlobalMedianWhenEveryKeyIsSparse() {
        var prior = fit(concat(
                legs("UA", "ORD", localEpoch("ORD", 9, 14), 5, 6000L),
                legs("DL", "DEN", localEpoch("DEN", 9, 9), 30, 240L)));

        assertThat(prior.priorSeconds("UA", "ORD", localEpoch("ORD", 9, 14))).isEqualTo(240L);
    }

    @Test
    void returnsZeroWhenThereIsNoTrainingData() {
        var prior = BaselineDelayPrior.from(TestRepos.of(), TZ);

        assertThat(prior.priorSeconds("UA", "ORD", localEpoch("ORD", 9, 14))).isZero();
    }

    @Test
    void excludesLegsWithLateAircraftDelay() {
        // The turnaround/pressure term already models late-aircraft propagation. Fitting the
        // prior on those legs too would double-count it in the additive composition.
        var records = new ArrayList<BtsFlightRecord>();
        records.addAll(legs("UA", "ORD", localEpoch("ORD", 9, 14), 30, 300L));
        records.addAll(legsWithLateAircraft("UA", "ORD", localEpoch("ORD", 9, 14), 30, 9000L, 1800L));

        assertThat(fit(records).priorSeconds("UA", "ORD", localEpoch("ORD", 9, 14))).isEqualTo(300L);
    }

    @Test
    void includesLegsWhereLateAircraftDelayIsExplicitlyZero() {
        var records = legsWithLateAircraft("UA", "ORD", localEpoch("ORD", 9, 14), 30, 420L, 0L);

        assertThat(fit(records).priorSeconds("UA", "ORD", localEpoch("ORD", 9, 14))).isEqualTo(420L);
    }

    @Test
    void cancelledLegsDoNotCountTowardTheSampleThreshold() {
        // 29 real samples + 30 cancelled at hour 14. If cancellations counted, the bucket would
        // clear 30 and answer 6000; correctly excluded, it backs off to (UA,ORD) -> 111.
        var records = new ArrayList<BtsFlightRecord>();
        records.addAll(legs("UA", "ORD", localEpoch("ORD", 9, 14), 29, 6000L));
        records.addAll(cancelledLegs("UA", "ORD", localEpoch("ORD", 9, 14), 30));
        records.addAll(legs("UA", "ORD", localEpoch("ORD", 9, 9), 30, 111L));

        assertThat(fit(records).priorSeconds("UA", "ORD", localEpoch("ORD", 9, 14))).isEqualTo(111L);
    }

    @Test
    void usesMedianSoMultiHourOutliersDoNotDragTheEstimate() {
        // 29 legs at 60s and one 10-hour outlier: mean is ~1258s, median is 60s.
        var records = new ArrayList<BtsFlightRecord>();
        records.addAll(legs("UA", "ORD", localEpoch("ORD", 9, 14), 29, 60L));
        records.addAll(legs("UA", "ORD", localEpoch("ORD", 9, 14), 1, 36_000L));

        assertThat(fit(records).priorSeconds("UA", "ORD", localEpoch("ORD", 9, 14))).isEqualTo(60L);
    }

    @Test
    void keepsNegativePriorsForCarriersThatHabituallyPushBackEarly() {
        // BTS DEP_DELAY is signed and most flights leave a few minutes early. The MAE-minimising
        // constant is the signed conditional median, so early departures must not be clamped.
        var prior = fit(legs("WN", "MDW", localEpoch("MDW", 9, 7), 30, -180L));

        assertThat(prior.priorSeconds("WN", "MDW", localEpoch("MDW", 9, 7))).isEqualTo(-180L);
    }

    // --- helpers -------------------------------------------------------------------------

    private static BaselineDelayPrior fit(List<BtsFlightRecord> records) {
        return BaselineDelayPrior.from(TestRepos.of(records.toArray(BtsFlightRecord[]::new)), TZ);
    }

    @SafeVarargs
    private static List<BtsFlightRecord> concat(List<BtsFlightRecord>... parts) {
        var all = new ArrayList<BtsFlightRecord>();
        for (List<BtsFlightRecord> part : parts) all.addAll(part);
        return all;
    }

    private static long localEpoch(String iata, int dayOfMarch, int localHour) {
        ZoneId zone = TZ.zoneFor(iata).orElseThrow();
        return ZonedDateTime.of(
                LocalDate.of(2026, 3, dayOfMarch), LocalTime.of(localHour, 30), zone).toEpochSecond();
    }

    private static List<BtsFlightRecord> legs(String carrier, String origin, long depEpoch,
                                              int count, long depDelaySeconds) {
        return legsWithLateAircraft(carrier, origin, depEpoch, count, depDelaySeconds, null);
    }

    private static List<BtsFlightRecord> legsWithLateAircraft(String carrier, String origin,
                                                              long depEpoch, int count,
                                                              long depDelaySeconds,
                                                              Long lateAircraftSeconds) {
        var records = new ArrayList<BtsFlightRecord>(count);
        for (int i = 0; i < count; i++) {
            records.add(new BtsFlightRecord(carrier, String.valueOf(i), "N" + i, origin, "XXX",
                    depEpoch, depEpoch + 7200, depDelaySeconds, false, null, lateAircraftSeconds));
        }
        return records;
    }

    private static List<BtsFlightRecord> cancelledLegs(String carrier, String origin,
                                                       long depEpoch, int count) {
        var records = new ArrayList<BtsFlightRecord>(count);
        for (int i = 0; i < count; i++) {
            records.add(new BtsFlightRecord(carrier, "C" + i, "NC" + i, origin, "XXX",
                    depEpoch, depEpoch + 7200, null, true, null, null));
        }
        return records;
    }
}

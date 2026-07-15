package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.model.AirportDisruptionScore;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DisruptionScoreServiceTest {

    private DisruptionScoreService service;

    @BeforeEach
    void setUp() {
        var props = new DisruptionScoreProperties(60, 1, 15, 30, 0.85, 0.15, 8);
        service = new DisruptionScoreService(props);
    }

    private DelayEvent delayEvent(String airport, long arrivalTime,
                                   long delaySeconds, DelayClassification classification) {
        return new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "K" + airport, airport, arrivalTime,
                arrivalTime - delaySeconds, delaySeconds,
                classification, "AEROAPI", Instant.ofEpochSecond(arrivalTime),
                null, null, null, null);
    }

    @Test
    void shouldReturnZeroScoreForUnknownAirport() {
        var score = service.computeScore("ZZZ");
        assertThat(score.score()).isEqualTo(0.0);
        assertThat(score.totalFlightsInWindow()).isEqualTo(0);
    }

    @Test
    void shouldRecordAndComputeScore() {
        long baseTime = 1709312400L;
        service.recordDelay(delayEvent("ORD", baseTime, 1800, DelayClassification.MODERATE));
        service.recordDelay(delayEvent("ORD", baseTime + 60, 2700, DelayClassification.MODERATE));
        service.recordDelay(delayEvent("ORD", baseTime + 120, 3600, DelayClassification.MAJOR));

        var score = service.computeScore("ORD");
        assertThat(score.score()).isGreaterThan(0.0);
        assertThat(score.activeDelayCount()).isEqualTo(3);
        assertThat(score.totalFlightsInWindow()).isEqualTo(3);
        assertThat(score.airportIata()).isEqualTo("ORD");
    }

    @Test
    void shouldCountOnlyFaaDelayedFlightsInActiveDelayCount() {
        long baseTime = 1709312400L;
        service.recordDelay(delayEvent("ORD", baseTime, 0, DelayClassification.ON_TIME));
        service.recordDelay(delayEvent("ORD", baseTime + 60, 600, DelayClassification.MINOR));
        service.recordDelay(delayEvent("ORD", baseTime + 120, 1800, DelayClassification.MODERATE));

        var score = service.computeScore("ORD");
        assertThat(score.activeDelayCount()).isEqualTo(1);
        assertThat(score.totalFlightsInWindow()).isEqualTo(3);
    }

    @Test
    void shouldEvictExpiredBuckets() {
        long oldTime = 1709308800L;
        long recentTime = oldTime + 7200; // 2 hours later

        service.recordDelay(delayEvent("ORD", oldTime, 1800, DelayClassification.MODERATE));
        service.recordDelay(delayEvent("ORD", recentTime, 900, DelayClassification.MODERATE));

        var score = service.computeScore("ORD");
        // Old event (2 hours before latest) should be evicted from the 60-min window
        assertThat(score.totalFlightsInWindow()).isEqualTo(1);
    }

    @Test
    void shouldGetTopDisruptedAirports() {
        long baseTime = 1709312400L;

        // ORD: 3 major delays
        for (int i = 0; i < 3; i++) {
            service.recordDelay(delayEvent("ORD", baseTime + i * 60, 3600, DelayClassification.MAJOR));
        }

        // LAX: 1 minor delay (not FAA-delayed)
        service.recordDelay(delayEvent("LAX", baseTime, 600, DelayClassification.MINOR));

        // JFK: 5 severe delays
        for (int i = 0; i < 5; i++) {
            service.recordDelay(delayEvent("JFK", baseTime + i * 60, 7800, DelayClassification.SEVERE));
        }

        List<AirportDisruptionScore> top = service.getTopDisruptedAirports(2);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).airportIata()).isEqualTo("JFK");
        assertThat(top.get(0).score()).isGreaterThan(top.get(1).score());
    }

    @Test
    void shouldCapScoreAt100() {
        long baseTime = 1709312400L;
        for (int i = 0; i < 20; i++) {
            service.recordDelay(delayEvent("ORD", baseTime + i * 60, 7200, DelayClassification.SEVERE));
        }

        var score = service.computeScore("ORD");
        assertThat(score.score()).isLessThanOrEqualTo(100.0);
    }

    @Test
    void shouldTrackSeparateAirports() {
        long baseTime = 1709312400L;
        service.recordDelay(delayEvent("ORD", baseTime, 1800, DelayClassification.MODERATE));
        service.recordDelay(delayEvent("LAX", baseTime, 3600, DelayClassification.MAJOR));

        var ordScore = service.computeScore("ORD");
        var laxScore = service.computeScore("LAX");

        assertThat(ordScore.totalFlightsInWindow()).isEqualTo(1);
        assertThat(laxScore.totalFlightsInWindow()).isEqualTo(1);
        assertThat(laxScore.score()).isGreaterThan(ordScore.score());
    }

    @Test
    void shouldComputeAverageDelayMinutes() {
        long baseTime = 1709312400L;
        // 3 flights: 30 min, 60 min, 90 min delays → avg = 60 min
        service.recordDelay(delayEvent("ORD", baseTime, 1800, DelayClassification.MODERATE));
        service.recordDelay(delayEvent("ORD", baseTime + 60, 3600, DelayClassification.MAJOR));
        service.recordDelay(delayEvent("ORD", baseTime + 120, 5400, DelayClassification.MAJOR));

        var score = service.computeScore("ORD");
        assertThat(score.averageDelayMinutes()).isEqualTo(60.0);
    }

    @Test
    void shouldIgnoreNullAirportCode() {
        var event = new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", null, 1709312400L, null, null,
                DelayClassification.UNKNOWN, "UNRESOLVED", Instant.now(),
                null, null, null, null);
        service.recordDelay(event);
        // Should not throw
    }
}

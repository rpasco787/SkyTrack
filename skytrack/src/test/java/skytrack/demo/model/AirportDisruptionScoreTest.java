package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AirportDisruptionScoreTest {

    @Test
    void shouldConstructScore() {
        var score = new AirportDisruptionScore("ORD", 75.5, 12, 30, 42.3, 0.15, Instant.now());
        assertThat(score.airportIata()).isEqualTo("ORD");
        assertThat(score.score()).isEqualTo(75.5);
        assertThat(score.activeDelayCount()).isEqualTo(12);
        assertThat(score.totalFlightsInWindow()).isEqualTo(30);
        assertThat(score.averageDelayMinutes()).isEqualTo(42.3);
        assertThat(score.trendDirection()).isEqualTo(0.15);
    }
}

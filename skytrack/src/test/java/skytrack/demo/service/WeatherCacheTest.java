package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.WeatherObservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherCacheTest {

    private final WeatherProperties props =
            new WeatherProperties("replay", null, null, 5000, 15, 30, List.of());

    @Test
    void shouldStoreAndReturnFreshObservation() {
        var clock = Clock.fixed(Instant.parse("2026-05-05T15:00:00Z"), ZoneOffset.UTC);
        var cache = new WeatherCache(props, clock);

        var obs = sample("KORD", clock.instant());
        cache.update(List.of(obs));

        Optional<WeatherObservation> result = cache.get("KORD");
        assertThat(result).isPresent();
        assertThat(result.get().airportIcao()).isEqualTo("KORD");
    }

    @Test
    void shouldReturnEmptyForUnknownAirport() {
        var cache = new WeatherCache(props, Clock.systemUTC());
        assertThat(cache.get("KZZZ")).isEmpty();
    }

    @Test
    void shouldExpireAfterTtl() {
        var fixed = Instant.parse("2026-05-05T15:00:00Z");
        var mutableClock = new MutableClock(fixed);
        var cache = new WeatherCache(props, mutableClock);

        cache.update(List.of(sample("KORD", fixed)));
        assertThat(cache.get("KORD")).isPresent();

        mutableClock.advance(Duration.ofMinutes(31)); // ttl is 30 min
        assertThat(cache.get("KORD")).isEmpty();
    }

    @Test
    void shouldOverwriteExistingEntry() {
        var clock = Clock.fixed(Instant.parse("2026-05-05T15:00:00Z"), ZoneOffset.UTC);
        var cache = new WeatherCache(props, clock);

        cache.update(List.of(sample("KORD", clock.instant(), FlightCategory.VFR)));
        cache.update(List.of(sample("KORD", clock.instant(), FlightCategory.IFR)));

        assertThat(cache.get("KORD")).hasValueSatisfying(o ->
                assertThat(o.flightCategory()).isEqualTo(FlightCategory.IFR));
    }

    @Test
    void shouldReportSize() {
        var clock = Clock.fixed(Instant.parse("2026-05-05T15:00:00Z"), ZoneOffset.UTC);
        var cache = new WeatherCache(props, clock);
        cache.update(List.of(
                sample("KORD", clock.instant()),
                sample("KATL", clock.instant())));
        assertThat(cache.size()).isEqualTo(2);
    }

    private static WeatherObservation sample(String icao, Instant observedAt) {
        return sample(icao, observedAt, FlightCategory.VFR);
    }

    private static WeatherObservation sample(String icao, Instant observedAt, FlightCategory cat) {
        return new WeatherObservation(icao, null, observedAt,
                10.0, 5000, 10, null, cat, "raw");
    }

    static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant initial) { this.now = initial; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

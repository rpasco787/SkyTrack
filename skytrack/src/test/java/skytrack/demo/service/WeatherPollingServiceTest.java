package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.client.WeatherSource;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.WeatherObservation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherPollingServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-05T15:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldFetchAndPopulateCache() {
        WeatherSource source = mock(WeatherSource.class);
        var props = new WeatherProperties("replay", null, null, 5000, 15, 30,
                List.of("KORD", "KATL"));
        var cache = new WeatherCache(props, clock);
        WeatherObservation obs = new WeatherObservation("KORD", null, clock.instant(),
                10.0, 5000, 10, null, FlightCategory.VFR, "raw");
        when(source.fetchObservations(eq(List.of("KORD", "KATL"))))
                .thenReturn(List.of(obs));

        var service = new WeatherPollingService(source, cache, props);
        service.refresh();

        assertThat(cache.get("KORD")).isPresent();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void shouldSkipWhenNoTargetAirports() {
        WeatherSource source = mock(WeatherSource.class);
        var props = new WeatherProperties("replay", null, null, 5000, 15, 30, List.of());
        var cache = new WeatherCache(props, clock);

        var service = new WeatherPollingService(source, cache, props);
        service.refresh();

        verify(source, never()).fetchObservations(anyList());
        assertThat(cache.size()).isZero();
    }

    @Test
    void shouldSwallowExceptionsFromSource() {
        WeatherSource source = mock(WeatherSource.class);
        var props = new WeatherProperties("replay", null, null, 5000, 15, 30,
                List.of("KORD"));
        var cache = new WeatherCache(props, clock);
        when(source.fetchObservations(anyList()))
                .thenThrow(new RuntimeException("boom"));

        var service = new WeatherPollingService(source, cache, props);
        service.refresh(); // must not throw

        assertThat(cache.size()).isZero();
    }
}

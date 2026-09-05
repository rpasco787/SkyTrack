package skytrack.demo.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.metrics.PipelineMetrics;
import skytrack.demo.model.FlightSchedule;
import skytrack.demo.model.LandingEvent;
import skytrack.demo.model.ResolvedArrival;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleResolverTest {

    @Mock private FlightScheduleApiClient apiClient;
    private CallsignParser callsignParser;
    @Mock private RouteAverageEstimator routeAverageEstimator;

    private ScheduleResolver resolver;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        callsignParser = new CallsignParser(); // use real parser
        resolver = new ScheduleResolver(apiClient, callsignParser, routeAverageEstimator,
                new PipelineMetrics(registry));
    }

    private LandingEvent landingEvent(String callsign, long arrivalTime) {
        return new LandingEvent("abc123", callsign, "KORD", "ORD",
                arrivalTime, 41.9742, -87.9073);
    }

    @Test
    void shouldResolveViaAeroApi() {
        long scheduledArrival = Instant.parse("2026-03-15T16:30:00Z").getEpochSecond();
        long actualArrival = Instant.parse("2026-03-15T16:45:00Z").getEpochSecond();

        var schedule = new FlightSchedule("UAL1234", "UA1234", "UAL", "ORD", "LAX",
                Instant.parse("2026-03-15T14:00:00Z"),
                Instant.ofEpochSecond(scheduledArrival),
                null,
                Instant.ofEpochSecond(actualArrival),
                null, null, "B738");

        when(apiClient.getFlightSchedule(eq("UAL1234"), anyString()))
                .thenReturn(Optional.of(schedule));

        ResolvedArrival result = resolver.resolve(landingEvent("UAL1234", actualArrival));

        assertThat(result.resolutionMethod()).isEqualTo("AEROAPI");
        assertThat(result.carrierCode()).isEqualTo("UA");
        assertThat(result.flightNumber()).isEqualTo("1234");
        assertThat(result.scheduledArrivalTime()).isEqualTo(scheduledArrival);
        assertThat(result.delaySeconds()).isEqualTo(actualArrival - scheduledArrival);
        verify(routeAverageEstimator).record(schedule);
    }

    @Test
    void shouldFallBackToRouteAverageWhenApiReturnsEmpty() {
        when(apiClient.getFlightSchedule(eq("UAL1234"), anyString()))
                .thenReturn(Optional.empty());
        when(routeAverageEstimator.estimateDelaySeconds("UAL", "ORD"))
                .thenReturn(OptionalDouble.of(900.0));

        long arrivalTime = Instant.parse("2026-03-15T16:45:00Z").getEpochSecond();
        ResolvedArrival result = resolver.resolve(landingEvent("UAL1234", arrivalTime));

        assertThat(result.resolutionMethod()).isEqualTo("ROUTE_AVERAGE");
        assertThat(result.delaySeconds()).isEqualTo(900L);
    }

    @Test
    void shouldReturnUnresolvedWhenCallsignUnparseable() {
        long arrivalTime = Instant.parse("2026-03-15T16:45:00Z").getEpochSecond();
        ResolvedArrival result = resolver.resolve(landingEvent("UNKNOWN", arrivalTime));

        assertThat(result.resolutionMethod()).isEqualTo("UNRESOLVED");
        assertThat(result.carrierCode()).isNull();
        assertThat(result.delaySeconds()).isNull();
        verify(apiClient, never()).getFlightSchedule(anyString(), anyString());
    }

    @Test
    void shouldReturnUnresolvedWhenBothAeroApiAndRouteAverageFail() {
        when(apiClient.getFlightSchedule(eq("DAL567"), anyString()))
                .thenReturn(Optional.empty());
        when(routeAverageEstimator.estimateDelaySeconds("DAL", "ORD"))
                .thenReturn(OptionalDouble.empty());

        long arrivalTime = Instant.parse("2026-03-15T16:45:00Z").getEpochSecond();
        ResolvedArrival result = resolver.resolve(landingEvent("DAL567", arrivalTime));

        assertThat(result.resolutionMethod()).isEqualTo("UNRESOLVED");
    }

    @Test
    void shouldHandleApiException() {
        when(apiClient.getFlightSchedule(eq("UAL1234"), anyString()))
                .thenThrow(new RuntimeException("API error"));
        when(routeAverageEstimator.estimateDelaySeconds("UAL", "ORD"))
                .thenReturn(OptionalDouble.empty());

        long arrivalTime = Instant.parse("2026-03-15T16:45:00Z").getEpochSecond();
        ResolvedArrival result = resolver.resolve(landingEvent("UAL1234", arrivalTime));

        assertThat(result.resolutionMethod()).isEqualTo("UNRESOLVED");
    }

    @Test
    void timesTheScheduleLookupAsResolvedWhenTheApiReturnsASchedule() {
        var schedule = new FlightSchedule("UAL1234", "UA1234", "UAL", "LAX", "ORD",
                null, Instant.ofEpochSecond(1709312000L), null, null, null, null, null);
        when(apiClient.getFlightSchedule(eq("UAL1234"), anyString())).thenReturn(Optional.of(schedule));

        resolver.resolve(landingEvent("UAL1234", 1709312400L));

        assertThat(registry.get(PipelineMetrics.SCHEDULE_RESOLUTION).tag("outcome", "resolved").timer().count()).isEqualTo(1L);
        assertThat(registry.get(PipelineMetrics.SCHEDULE_RESOLUTION).tag("outcome", "empty").timer().count()).isZero();
    }

    @Test
    void timesTheScheduleLookupAsEmptyWhenTheApiHasNothing() {
        when(apiClient.getFlightSchedule(anyString(), anyString())).thenReturn(Optional.empty());
        when(routeAverageEstimator.estimateDelaySeconds(anyString(), anyString())).thenReturn(OptionalDouble.empty());

        resolver.resolve(landingEvent("UAL1234", 1709312400L));

        assertThat(registry.get(PipelineMetrics.SCHEDULE_RESOLUTION).tag("outcome", "empty").timer().count()).isEqualTo(1L);
    }

    @Test
    void timesTheScheduleLookupAsErrorWhenTheApiThrowsAndStillFallsBack() {
        when(apiClient.getFlightSchedule(anyString(), anyString())).thenThrow(new RuntimeException("boom"));
        when(routeAverageEstimator.estimateDelaySeconds(anyString(), anyString())).thenReturn(OptionalDouble.of(300.0));

        var resolved = resolver.resolve(landingEvent("UAL1234", 1709312400L));

        assertThat(resolved.resolutionMethod()).isEqualTo("ROUTE_AVERAGE");
        assertThat(registry.get(PipelineMetrics.SCHEDULE_RESOLUTION).tag("outcome", "error").timer().count()).isEqualTo(1L);
    }
}

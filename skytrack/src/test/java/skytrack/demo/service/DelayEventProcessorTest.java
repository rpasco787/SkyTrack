package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.model.*;
import skytrack.demo.parquet.HistoricalDelayWriter;
import skytrack.demo.sqs.SqsAirportEventProducer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DelayEventProcessorTest {

    @Mock private DelayComputer delayComputer;
    @Mock private DisruptionScoreService disruptionScoreService;
    @Mock private SqsAirportEventProducer eventProducer;
    @Mock private CascadeChainDetector cascadeChainDetector;
    @Mock private WeatherCache weatherCache;
    @Mock private HistoricalDelayWriter historicalDelayWriter;
    @Mock private ScheduleCoverageTracker coverageTracker;
    @Mock private RecentCascadeStore recentCascadeStore;
    @Mock private DelayPredictionService delayPredictionService;

    private DelayEventProcessor processor;

    @BeforeEach
    void setUp() {
        lenient().when(weatherCache.get(anyString())).thenReturn(Optional.empty());
        processor = new DelayEventProcessor(
                delayComputer, disruptionScoreService, eventProducer, cascadeChainDetector,
                weatherCache, historicalDelayWriter, coverageTracker, recentCascadeStore,
                delayPredictionService);
    }

    private ResolvedArrival resolvedArrival(long delaySeconds) {
        return new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L,
                1709312400L - delaySeconds, delaySeconds, "AEROAPI");
    }

    private DelayEvent delayEvent(long delaySeconds) {
        return new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L,
                1709312400L - delaySeconds, delaySeconds,
                DelayClassification.fromDelaySeconds(delaySeconds),
                "AEROAPI", Instant.now(),
                null, null, null, null);
    }

    @Test
    void shouldProcessArrivalThroughFullPipeline() {
        var arrival = resolvedArrival(900);
        var event = delayEvent(900);

        when(delayComputer.compute(eq(arrival), any())).thenReturn(event);
        when(cascadeChainDetector.detect(arrival)).thenReturn(Optional.empty());

        processor.process(arrival);

        verify(delayComputer).compute(eq(arrival), any());
        verify(disruptionScoreService).recordDelay(event);
        verify(eventProducer).send(event);
        verify(cascadeChainDetector).detect(arrival);
    }

    @Test
    void shouldHandleCascadeChain() {
        var arrival = resolvedArrival(3600);
        var event = delayEvent(3600);
        var chain = CascadeChain.of("UAL1234", "ORD", 3600L, List.of(), Instant.now());

        when(delayComputer.compute(eq(arrival), any())).thenReturn(event);
        when(cascadeChainDetector.detect(arrival)).thenReturn(Optional.of(chain));

        processor.process(arrival);

        verify(cascadeChainDetector).detect(arrival);
        verify(recentCascadeStore).add(chain);
    }

    @Test
    void shouldProcessUnresolvedArrival() {
        var arrival = new ResolvedArrival("abc123", "ZZZ999", null, null,
                "KORD", "ORD", 1709312400L, null, null, "UNRESOLVED");
        var event = new DelayEvent("abc123", "ZZZ999", null, null,
                "KORD", "ORD", 1709312400L, null, null,
                DelayClassification.UNKNOWN, "UNRESOLVED", Instant.now(),
                null, null, null, null);

        when(delayComputer.compute(eq(arrival), any())).thenReturn(event);
        when(cascadeChainDetector.detect(arrival)).thenReturn(Optional.empty());

        processor.process(arrival);

        verify(disruptionScoreService).recordDelay(event);
        verify(eventProducer).send(event);
    }

    @Test
    void shouldEnrichDelayEventWithWeatherFromCache() {
        var clock = Clock.fixed(Instant.parse("2026-05-05T15:00:00Z"), ZoneOffset.UTC);
        DelayComputer realComputer = new DelayComputer();
        var props = new skytrack.demo.config.WeatherProperties(
                "replay", null, null, 5000, 15, 30, List.of("KORD"));
        WeatherCache realCache = new WeatherCache(props, clock);
        realCache.update(List.of(new WeatherObservation(
                "KORD", "ORD", clock.instant(),
                2.0, 800, 18, 25, FlightCategory.IFR, "raw")));

        var p = new DelayEventProcessor(realComputer, disruptionScoreService,
                eventProducer, cascadeChainDetector, realCache, historicalDelayWriter, coverageTracker, recentCascadeStore,
                delayPredictionService);
        when(cascadeChainDetector.detect(any())).thenReturn(Optional.empty());

        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L, "API_CACHE");
        p.process(arrival);

        ArgumentCaptor<DelayEvent> captor = ArgumentCaptor.forClass(DelayEvent.class);
        verify(eventProducer).send(captor.capture());
        assertThat(captor.getValue().flightCategory()).isEqualTo(FlightCategory.IFR);
        assertThat(captor.getValue().ceilingFeet()).isEqualTo(800);
    }

    @Test
    void shouldTriggerDeparturePredictionOnLanding() {
        var arrival = resolvedArrival(900);
        var event = delayEvent(900);
        when(delayComputer.compute(eq(arrival), any())).thenReturn(event);
        when(cascadeChainDetector.detect(arrival)).thenReturn(Optional.empty());

        processor.process(arrival);

        verify(delayPredictionService).predictNextDeparture(arrival);
    }

    @Test
    void shouldRecordResolutionMethodInCoverageTracker() {
        var arrival = resolvedArrival(900);
        var event = delayEvent(900);
        when(delayComputer.compute(eq(arrival), any())).thenReturn(event);
        when(cascadeChainDetector.detect(arrival)).thenReturn(Optional.empty());

        processor.process(arrival);

        verify(coverageTracker).record(event.resolutionMethod());
    }

    @Test
    void shouldBufferDelayEventForHistoricalStorage() {
        var arrival = resolvedArrival(900);
        var event = delayEvent(900);
        when(delayComputer.compute(eq(arrival), any())).thenReturn(event);
        when(cascadeChainDetector.detect(arrival)).thenReturn(Optional.empty());

        processor.process(arrival);

        verify(historicalDelayWriter).buffer(any(DelayEvent.class));
    }

    @Test
    void shouldEmitEventWithoutWeatherOnCacheMiss() {
        DelayComputer realComputer = new DelayComputer();
        var props = new skytrack.demo.config.WeatherProperties(
                "replay", null, null, 5000, 15, 30, List.of());
        WeatherCache emptyCache = new WeatherCache(props, Clock.systemUTC());

        var p = new DelayEventProcessor(realComputer, disruptionScoreService,
                eventProducer, cascadeChainDetector, emptyCache, historicalDelayWriter, coverageTracker, recentCascadeStore,
                delayPredictionService);
        when(cascadeChainDetector.detect(any())).thenReturn(Optional.empty());

        var arrival = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709311500L, 900L, "API_CACHE");
        p.process(arrival);

        ArgumentCaptor<DelayEvent> captor = ArgumentCaptor.forClass(DelayEvent.class);
        verify(eventProducer).send(captor.capture());
        assertThat(captor.getValue().flightCategory()).isNull();
    }
}

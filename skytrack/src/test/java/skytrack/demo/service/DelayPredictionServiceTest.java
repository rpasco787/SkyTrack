package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.config.PredictionProperties;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.OutboundFlight;
import skytrack.demo.model.PredictedDelayEvent;
import skytrack.demo.model.ResolvedArrival;
import skytrack.demo.parquet.HistoricalPredictionWriter;
import skytrack.demo.sqs.SqsAirportEventProducer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DelayPredictionServiceTest {

    @Mock private OutboundScheduleResolver resolver;
    @Mock private RecentPredictionStore store;
    @Mock private SqsAirportEventProducer eventProducer;
    @Mock private HistoricalPredictionWriter historicalPredictionWriter;

    private static final Instant FIXED_NOW = Instant.parse("2026-03-09T20:30:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    // landing epoch, turnaround 45min=2700s → earliest ready = landing+2700
    private static final long LANDING = 1773088200L;

    // outbound sched 960s before earliest-ready → predicted = 960s (16 min > threshold 15)
    private static final long SCHED_DEP_ABOVE = LANDING + 2700 - 960;
    // outbound sched 600s before earliest-ready → predicted = 600s (10 min < threshold 15)
    private static final long SCHED_DEP_BELOW = LANDING + 2700 - 600;

    private final PredictionProperties props = new PredictionProperties(true, "x", 45, 15);
    private final TurnaroundEstimator turnaround = new TurnaroundEstimator(props, Map.of());
    private final DelayPredictor predictor = new DelayPredictor();

    private DelayPredictionService service;

    @BeforeEach
    void setUp() {
        service = new DelayPredictionService(
                resolver, turnaround, predictor, props,
                store, eventProducer, historicalPredictionWriter, FIXED_CLOCK);
    }

    private ResolvedArrival arrival() {
        return new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", LANDING, null, null, null);
    }

    private OutboundFlight outboundAbove() {
        return new OutboundFlight("UA", "5678", "N12345", "ORD", SCHED_DEP_ABOVE, 900L);
    }

    @Test
    void emitsWhenDelayExceedsThreshold() {
        when(resolver.resolve(any())).thenReturn(Optional.of(outboundAbove()));

        service.predictNextDeparture(arrival());

        verify(store).add(any(PredictedDelayEvent.class));
        verify(eventProducer).send(any(PredictedDelayEvent.class));
        verify(historicalPredictionWriter).buffer(any(PredictedDelayEvent.class));
    }

    @Test
    void suppressesBelowThreshold() {
        var outbound = new OutboundFlight("UA", "5678", "N12345", "ORD", SCHED_DEP_BELOW, 300L);
        when(resolver.resolve(any())).thenReturn(Optional.of(outbound));

        service.predictNextDeparture(arrival());

        verifyNoInteractions(store, eventProducer, historicalPredictionWriter);
    }

    @Test
    void buildsCorrectPredictedDelayEvent() {
        when(resolver.resolve(any())).thenReturn(Optional.of(outboundAbove()));

        service.predictNextDeparture(arrival());

        ArgumentCaptor<PredictedDelayEvent> captor =
                ArgumentCaptor.forClass(PredictedDelayEvent.class);
        verify(store).add(captor.capture());
        PredictedDelayEvent event = captor.getValue();

        assertThat(event.inboundCallsign()).isEqualTo("UAL1234");
        assertThat(event.tailNumber()).isEqualTo("N12345");
        assertThat(event.departureAirportIata()).isEqualTo("ORD");
        assertThat(event.predictedDelaySeconds()).isEqualTo(960L);
        assertThat(event.predictedClassification())
                .isEqualTo(DelayClassification.fromDelaySeconds(960L));
        assertThat(event.actualDelaySeconds()).isEqualTo(900L);
        assertThat(event.confidence()).isEqualTo("BTS_REPLAY");
        assertThat(event.createdAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void returnsEarlyWhenNoOutboundFound() {
        when(resolver.resolve(any())).thenReturn(Optional.empty());

        service.predictNextDeparture(arrival());

        verifyNoInteractions(store, eventProducer, historicalPredictionWriter);
    }

    @Test
    void zeroThresholdStoresSubThresholdPrediction() {
        var zeroThresholdProps = new PredictionProperties(true, "x", 45, 0);
        var zeroThresholdService = new DelayPredictionService(
                resolver, turnaround, predictor, zeroThresholdProps,
                store, eventProducer, historicalPredictionWriter, FIXED_CLOCK);
        var outbound = new OutboundFlight("UA", "5678", "N12345", "ORD", SCHED_DEP_BELOW, 300L);
        when(resolver.resolve(any())).thenReturn(Optional.of(outbound));

        zeroThresholdService.predictNextDeparture(arrival());

        verify(store).add(any(PredictedDelayEvent.class));
    }
}

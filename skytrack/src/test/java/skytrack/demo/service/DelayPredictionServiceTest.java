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

    private final PredictionProperties props = new PredictionProperties(true, "x", 45, 15, 360);
    private final TurnaroundEstimator turnaround = new TurnaroundEstimator(props, Map.of());
    private final DelayPredictor predictor = new DelayPredictor();

    private static final BaselineDelayPrior NO_PRIOR =
            BaselineDelayPrior.from(TestRepos.of(), new AirportTimeZoneResolver());

    private DelayPredictionService service;

    @BeforeEach
    void setUp() {
        service = new DelayPredictionService(
                resolver, turnaround, predictor, NO_PRIOR, props,
                store, eventProducer, historicalPredictionWriter, FIXED_CLOCK);
    }

    private ResolvedArrival arrival() {
        return new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", LANDING, null, null, null);
    }

    private OutboundFlight outboundAbove() {
        return new OutboundFlight("UA", "5678", "N12345", "ORD", "LAX", SCHED_DEP_ABOVE, 900L);
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
        var outbound = new OutboundFlight("UA", "5678", "N12345", "ORD", "LAX", SCHED_DEP_BELOW, 300L);
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
    void usesCarrierSpecificTurnaroundWhenAvailable() {
        // AA median turnaround = 3600s (60 min), config default = 45 min (2700s).
        var carrierTurnaround = new TurnaroundEstimator(props, Map.of("AA", 3600L));
        var carrierService = new DelayPredictionService(
                resolver, carrierTurnaround, predictor, NO_PRIOR, props,
                store, eventProducer, historicalPredictionWriter, FIXED_CLOCK);
        // outbound scheduled 1000s after landing: default turnaround -> predicted 1700s,
        // AA-specific turnaround -> predicted 2600s. Either way it clears the 900s threshold.
        var outbound = new OutboundFlight("AA", "5678", "N12345", "ORD", "LAX", LANDING + 1000, null);
        when(resolver.resolve(any())).thenReturn(Optional.of(outbound));

        carrierService.predictNextDeparture(arrival());

        ArgumentCaptor<PredictedDelayEvent> captor = ArgumentCaptor.forClass(PredictedDelayEvent.class);
        verify(store).add(captor.capture());
        assertThat(captor.getValue().minTurnaroundSeconds()).isEqualTo(3600L);
        assertThat(captor.getValue().predictedDelaySeconds()).isEqualTo(2600L);
    }

    @Test
    void prefersTheTurnaroundFittedForThisCarrierAtThisAirport() {
        // AA turns aircraft in 3600s at ORD but 5400s system-wide. The outbound leaves ORD,
        // so the station figure must win: predicted = (LANDING + 3600) - (LANDING + 1000).
        var estimator = new TurnaroundEstimator(props, Map.of("AA|ORD", 3600L, "AA", 5400L));
        var airportAwareService = new DelayPredictionService(
                resolver, estimator, predictor, NO_PRIOR, props,
                store, eventProducer, historicalPredictionWriter, FIXED_CLOCK);
        var outbound = new OutboundFlight("AA", "5678", "N12345", "ORD", "LAX", LANDING + 1000, null);
        when(resolver.resolve(any())).thenReturn(Optional.of(outbound));

        airportAwareService.predictNextDeparture(arrival());

        ArgumentCaptor<PredictedDelayEvent> captor = ArgumentCaptor.forClass(PredictedDelayEvent.class);
        verify(store).add(captor.capture());
        assertThat(captor.getValue().minTurnaroundSeconds()).isEqualTo(3600L);
        assertThat(captor.getValue().predictedDelaySeconds()).isEqualTo(2600L);
    }

    @Test
    void addsTheBaselinePriorForTheOutboundCarrierStationAndHour() {
        // 960s of turnaround pressure as in buildsCorrectPredictedDelayEvent, plus a station
        // that habitually pushes back 300s late — a term the feasibility check cannot express.
        var priorService = new DelayPredictionService(
                resolver, turnaround, predictor, priorOf("UA", "ORD", SCHED_DEP_ABOVE, 300L), props,
                store, eventProducer, historicalPredictionWriter, FIXED_CLOCK);
        when(resolver.resolve(any())).thenReturn(Optional.of(outboundAbove()));

        priorService.predictNextDeparture(arrival());

        ArgumentCaptor<PredictedDelayEvent> captor = ArgumentCaptor.forClass(PredictedDelayEvent.class);
        verify(store).add(captor.capture());
        assertThat(captor.getValue().predictedDelaySeconds()).isEqualTo(1260L);
    }

    @Test
    void predictsFromTheExpectedTurnaroundNotTheSlackFloor() {
        // The floor (1800s) is what slack is measured against; the prediction must use the
        // typical figure (3600s), or it assumes every crew hits its best-ever turnaround.
        var estimator = new TurnaroundEstimator(
                props, Map.of("AA|ORD", 1800L), Map.of("AA|ORD", 3600L));
        var splitService = new DelayPredictionService(
                resolver, estimator, predictor, NO_PRIOR, props,
                store, eventProducer, historicalPredictionWriter, FIXED_CLOCK);
        var outbound = new OutboundFlight("AA", "5678", "N12345", "ORD", "LAX", LANDING + 1000, null);
        when(resolver.resolve(any())).thenReturn(Optional.of(outbound));

        splitService.predictNextDeparture(arrival());

        ArgumentCaptor<PredictedDelayEvent> captor = ArgumentCaptor.forClass(PredictedDelayEvent.class);
        verify(store).add(captor.capture());
        assertThat(captor.getValue().predictedDelaySeconds()).isEqualTo(2600L);
    }

    @Test
    void zeroThresholdEmitsEvenANegativePrediction() {
        // Threshold 0 means "no emit gate" — the backtest relies on it to score every resolved
        // outbound. Once a negative prior can push a prediction below zero, a gate that compares
        // `predicted < 0` starts silently dropping exactly those samples.
        long schedDep = LANDING + 99_999;
        var zeroThresholdProps = new PredictionProperties(true, "x", 45, 0, 360);
        var ungated = new DelayPredictionService(
                resolver, turnaround, predictor, priorOf("UA", "ORD", schedDep, -180L),
                zeroThresholdProps, store, eventProducer, historicalPredictionWriter, FIXED_CLOCK);
        var outbound = new OutboundFlight("UA", "5678", "N12345", "ORD", "LAX", schedDep, 0L);
        when(resolver.resolve(any())).thenReturn(Optional.of(outbound));

        ungated.predictNextDeparture(arrival());

        ArgumentCaptor<PredictedDelayEvent> captor = ArgumentCaptor.forClass(PredictedDelayEvent.class);
        verify(store).add(captor.capture());
        assertThat(captor.getValue().predictedDelaySeconds()).isEqualTo(-180L);
    }

    @Test
    void aPositiveThresholdStillSuppressesPredictionsBelowIt() {
        // Production must not raise a delay event for a flight predicted to leave early.
        long schedDep = LANDING + 99_999;
        var gated = new DelayPredictionService(
                resolver, turnaround, predictor, priorOf("UA", "ORD", schedDep, -180L), props,
                store, eventProducer, historicalPredictionWriter, FIXED_CLOCK);
        var outbound = new OutboundFlight("UA", "5678", "N12345", "ORD", "LAX", schedDep, 0L);
        when(resolver.resolve(any())).thenReturn(Optional.of(outbound));

        gated.predictNextDeparture(arrival());

        verifyNoInteractions(store, eventProducer, historicalPredictionWriter);
    }

    /** A prior fitted so that (carrier, origin, hour-of depEpoch) resolves to {@code seconds}. */
    private static BaselineDelayPrior priorOf(String carrier, String origin,
                                               long depEpoch, long seconds) {
        var records = new skytrack.demo.model.BtsFlightRecord[BaselineDelayPrior.MIN_SAMPLES];
        for (int i = 0; i < records.length; i++) {
            records[i] = new skytrack.demo.model.BtsFlightRecord(carrier, String.valueOf(i),
                    "N" + i, origin, "LAX", depEpoch, depEpoch + 7200, seconds, false, null, null);
        }
        return BaselineDelayPrior.from(TestRepos.of(records), new AirportTimeZoneResolver());
    }

    @Test
    void zeroThresholdStoresSubThresholdPrediction() {
        var zeroThresholdProps = new PredictionProperties(true, "x", 45, 0, 360);
        var zeroThresholdService = new DelayPredictionService(
                resolver, turnaround, predictor, NO_PRIOR, zeroThresholdProps,
                store, eventProducer, historicalPredictionWriter, FIXED_CLOCK);
        var outbound = new OutboundFlight("UA", "5678", "N12345", "ORD", "LAX", SCHED_DEP_BELOW, 300L);
        when(resolver.resolve(any())).thenReturn(Optional.of(outbound));

        zeroThresholdService.predictNextDeparture(arrival());

        verify(store).add(any(PredictedDelayEvent.class));
    }
}

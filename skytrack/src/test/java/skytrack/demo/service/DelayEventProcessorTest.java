package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.model.*;
import skytrack.demo.sqs.SqsAirportEventProducer;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DelayEventProcessorTest {

    @Mock private DelayComputer delayComputer;
    @Mock private DisruptionScoreService disruptionScoreService;
    @Mock private SqsAirportEventProducer eventProducer;
    @Mock private CascadeDetector cascadeDetector;

    private DelayEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DelayEventProcessor(
                delayComputer, disruptionScoreService, eventProducer, cascadeDetector);
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

        when(delayComputer.compute(arrival)).thenReturn(event);
        when(cascadeDetector.checkCascade(event)).thenReturn(Optional.empty());

        processor.process(arrival);

        verify(delayComputer).compute(arrival);
        verify(disruptionScoreService).recordDelay(event);
        verify(eventProducer).send(event);
        verify(cascadeDetector).checkCascade(event);
    }

    @Test
    void shouldHandleCascadeAlert() {
        var arrival = resolvedArrival(3600);
        var event = delayEvent(3600);
        var alert = new CascadeAlert("UAL1234", "ORD", 3600, 3060, 0.85, Instant.now());

        when(delayComputer.compute(arrival)).thenReturn(event);
        when(cascadeDetector.checkCascade(event)).thenReturn(Optional.of(alert));

        processor.process(arrival);

        verify(cascadeDetector).checkCascade(event);
        // Cascade alert is logged — no additional side effect to verify beyond the call
    }

    @Test
    void shouldProcessUnresolvedArrival() {
        var arrival = new ResolvedArrival("abc123", "ZZZ999", null, null,
                "KORD", "ORD", 1709312400L, null, null, "UNRESOLVED");
        var event = new DelayEvent("abc123", "ZZZ999", null, null,
                "KORD", "ORD", 1709312400L, null, null,
                DelayClassification.UNKNOWN, "UNRESOLVED", Instant.now(),
                null, null, null, null);

        when(delayComputer.compute(arrival)).thenReturn(event);
        when(cascadeDetector.checkCascade(event)).thenReturn(Optional.empty());

        processor.process(arrival);

        verify(disruptionScoreService).recordDelay(event);
        verify(eventProducer).send(event);
    }
}

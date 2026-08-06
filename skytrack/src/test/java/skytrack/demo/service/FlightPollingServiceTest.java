package skytrack.demo.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.sqs.SqsPositionProducer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightPollingServiceTest {

    @Mock
    private FlightDataSource flightDataSource;

    @Mock
    private SqsPositionProducer sqsPositionProducer;

    @InjectMocks
    private FlightPollingService pollingService;

    @Test
    void shouldFetchPositionsAndPublishToSqs() {
        var positions = List.of(
                new FlightPosition("abc123", "UAL1234", 41.97, -87.91,
                        10668.0, 230.5, 270.0, false,
                        1709312400L, 1709312400L, Instant.now())
        );
        when(flightDataSource.fetchPositions()).thenReturn(positions);

        pollingService.pollFlightData();

        verify(flightDataSource).fetchPositions();
        verify(sqsPositionProducer).send(positions);
    }

    @Test
    void shouldNotPublishWhenNoPositions() {
        when(flightDataSource.fetchPositions()).thenReturn(List.of());

        pollingService.pollFlightData();

        verify(flightDataSource).fetchPositions();
        verifyNoInteractions(sqsPositionProducer);
    }

    @Test
    void shouldHandleDataSourceException() {
        when(flightDataSource.fetchPositions()).thenThrow(new RuntimeException("Connection failed"));

        pollingService.pollFlightData();

        verify(flightDataSource).fetchPositions();
        verifyNoInteractions(sqsPositionProducer);
    }

    @Test
    void shouldLogAnErrorWhenNotEveryPositionWasQueued() {
        var positions = List.of(
                new FlightPosition("abc123", "UAL1234", 41.97, -87.91,
                        10668.0, 230.5, 270.0, false,
                        1709312400L, 1709312400L, Instant.now())
        );
        when(flightDataSource.fetchPositions()).thenReturn(positions);
        when(sqsPositionProducer.send(positions)).thenReturn(0);

        List<ILoggingEvent> events = capturePollingLog(() -> pollingService.pollFlightData());

        assertThat(events)
                .filteredOn(e -> e.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("Published only 0 of 1 positions"));
    }

    @Test
    void shouldLogInfoAndNoErrorWhenEveryPositionWasQueued() {
        // The complement of the test above: the ERROR branch must not fire on a healthy poll, or the
        // signal Task 2 adds is worthless.
        var positions = List.of(
                new FlightPosition("abc123", "UAL1234", 41.97, -87.91,
                        10668.0, 230.5, 270.0, false,
                        1709312400L, 1709312400L, Instant.now())
        );
        when(flightDataSource.fetchPositions()).thenReturn(positions);
        when(sqsPositionProducer.send(positions)).thenReturn(1);

        List<ILoggingEvent> events = capturePollingLog(() -> pollingService.pollFlightData());

        assertThat(events).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(events)
                .filteredOn(e -> e.getLevel() == Level.INFO)
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                        .isEqualTo("Published 1 positions to SQS"));
    }

    /** Mirrors PredictionConfigTest.captureStartupLog. */
    private static List<ILoggingEvent> capturePollingLog(Runnable action) {
        var logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(FlightPollingService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list;
    }
}

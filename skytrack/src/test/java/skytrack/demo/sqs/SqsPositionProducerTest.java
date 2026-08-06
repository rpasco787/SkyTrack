package skytrack.demo.sqs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import skytrack.demo.model.FlightPosition;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsPositionProducerTest {

    @Mock
    private SqsClient sqsClient;

    private SqsPositionProducer producer;

    @BeforeEach
    void setUp() {
        producer = new SqsPositionProducer(sqsClient, "http://localhost:4566/000000000000/skytrack-positions.fifo");
    }

    @Test
    void shouldSendSingleBatchForUpTo10Positions() {
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenReturn(SendMessageBatchResponse.builder().build());

        var positions = List.of(
                makePosition("abc123", "UAL1234", 1709312400L),
                makePosition("def456", "DAL567", 1709312400L)
        );

        producer.send(positions);

        var captor = ArgumentCaptor.forClass(SendMessageBatchRequest.class);
        verify(sqsClient, times(1)).sendMessageBatch(captor.capture());

        SendMessageBatchRequest request = captor.getValue();
        assertThat(request.entries()).hasSize(2);
        assertThat(request.entries().get(0).messageGroupId()).isEqualTo("abc123");
        assertThat(request.entries().get(1).messageGroupId()).isEqualTo("def456");
    }

    @Test
    void shouldSplitIntoMultipleBatchesWhenOver10() {
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenReturn(SendMessageBatchResponse.builder().build());

        List<FlightPosition> positions = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            positions.add(makePosition("icao" + i, "CALL" + i, 1709312400L + i));
        }

        producer.send(positions);

        verify(sqsClient, times(2)).sendMessageBatch(any(SendMessageBatchRequest.class));
    }

    @Test
    void shouldSetDeduplicationId() {
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenReturn(SendMessageBatchResponse.builder().build());

        var positions = List.of(makePosition("abc123", "UAL1234", 1709312400L));

        producer.send(positions);

        var captor = ArgumentCaptor.forClass(SendMessageBatchRequest.class);
        verify(sqsClient).sendMessageBatch(captor.capture());

        String dedupId = captor.getValue().entries().get(0).messageDeduplicationId();
        assertThat(dedupId).isEqualTo("abc123-1709312400");
    }

    @Test
    void shouldNotCallSqsForEmptyList() {
        producer.send(List.of());

        verifyNoInteractions(sqsClient);
    }

    @Test
    void logsAnErrorWhenSqsRejectsPartOfTheBatch() {
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenReturn(SendMessageBatchResponse.builder()
                        .failed(BatchResultErrorEntry.builder()
                                .id("0").code("InternalError").message("boom").senderFault(false)
                                .build())
                        .build());

        List<ILoggingEvent> events = captureProducerLog(
                () -> producer.send(List.of(makePosition("abc123", "UAL1234", 1709312400L))));

        assertThat(events)
                .as("a partially-rejected batch must not look like a success")
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(e.getFormattedMessage()).contains("1").contains("rejected");
                });
    }

    @Test
    void shouldReturnZeroWhenEveryBatchThrows() {
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenThrow(SqsException.builder().message("connection reset").build());

        int queued = producer.send(positions(25));

        assertThat(queued).isZero();
    }

    @Test
    void shouldReturnOnlyTheAcceptedCountOnPartialBatchFailure() {
        // SQS returns HTTP 200 for a batch where individual entries failed.
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenReturn(SendMessageBatchResponse.builder()
                        .failed(BatchResultErrorEntry.builder()
                                .id("0").code("InternalError").message("nope").build())
                        .build());

        int queued = producer.send(positions(10));

        assertThat(queued).isEqualTo(9);
    }

    @Test
    void shouldReturnTheFullCountWhenEveryEntryIsAccepted() {
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenReturn(SendMessageBatchResponse.builder().build());

        int queued = producer.send(positions(25));

        assertThat(queued).isEqualTo(25);
    }

    @Test
    void shouldLogOneAggregatedErrorPerSendRatherThanOnePerBatch() {
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenThrow(SqsException.builder().message("connection reset").build());

        // 250 positions = 25 batches. The old code logged 25 ERRORs.
        List<ILoggingEvent> events = captureProducerLog(() -> producer.send(positions(250)));

        assertThat(events).filteredOn(e -> e.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("250 of 250"));
    }

    /** Mirrors PredictionConfigTest.captureStartupLog. */
    private static List<ILoggingEvent> captureProducerLog(Runnable action) {
        var logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(SqsPositionProducer.class);
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

    private static List<FlightPosition> positions(int count) {
        List<FlightPosition> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new FlightPosition("icao" + i, "CS" + i, 41.97, -87.91,
                    10668.0, 230.5, 270.0, false,
                    1709312400L, 1709312400L, Instant.now()));
        }
        return list;
    }

    private FlightPosition makePosition(String icao, String callsign, long timePosition) {
        return new FlightPosition(icao, callsign, 41.97, -87.91,
                10668.0, 230.5, 270.0, false,
                timePosition, timePosition, Instant.now());
    }
}

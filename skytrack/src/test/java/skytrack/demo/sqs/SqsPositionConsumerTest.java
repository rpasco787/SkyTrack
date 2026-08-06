package skytrack.demo.sqs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.service.FlightPositionHandler;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsPositionConsumerTest {

    @Mock
    private SqsClient sqsClient;

    @Mock
    private FlightPositionHandler handler;

    private SqsPositionConsumer consumer;

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/skytrack-positions.fifo";

    private static final String VALID_BODY = """
            {"icao24":"abc123","callsign":"UAL1234","latitude":41.97,"longitude":-87.91,
             "baroAltitude":10668.0,"velocity":230.5,"heading":270.0,"onGround":false,
             "lastContact":1709312400,"timePosition":1709312400,"parsedAt":"2026-03-01T00:00:00Z"}
            """;

    @BeforeEach
    void setUp() {
        consumer = new SqsPositionConsumer(sqsClient, QUEUE_URL, handler);
    }

    @Test
    void shouldDeserializeAndDelegateToHandler() {
        var message = Message.builder()
                .messageId("msg-1")
                .receiptHandle("receipt-1")
                .body(VALID_BODY)
                .build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());
        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder().build());

        consumer.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FlightPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(handler).handle(captor.capture());

        List<FlightPosition> positions = captor.getValue();
        assertThat(positions).hasSize(1);
        assertThat(positions.getFirst().icao24()).isEqualTo("abc123");
        assertThat(positions.getFirst().callsign()).isEqualTo("UAL1234");
    }

    @Test
    void shouldDeleteMessagesAfterSuccessfulHandling() {
        var message = Message.builder()
                .messageId("msg-1")
                .receiptHandle("receipt-1")
                .body(VALID_BODY)
                .build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());
        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder().build());

        consumer.poll();

        var captor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
        verify(sqsClient).deleteMessageBatch(captor.capture());

        assertThat(captor.getValue().entries()).hasSize(1);
        assertThat(captor.getValue().entries().get(0).receiptHandle()).isEqualTo("receipt-1");
    }

    @Test
    void shouldNotDeleteMessagesWhenHandlerThrows() {
        var message = Message.builder()
                .messageId("msg-1")
                .receiptHandle("receipt-1")
                .body(VALID_BODY)
                .build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());
        doThrow(new RuntimeException("handler failed")).when(handler).handle(any());

        consumer.poll();

        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    void shouldDoNothingWhenNoMessagesReceived() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        consumer.poll();

        verifyNoInteractions(handler);
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    void shouldLeaveMalformedMessagesOnTheQueueForRedrive() {
        // A malformed message must NOT be deleted: leaving it raises ApproximateReceiveCount so the
        // RedrivePolicy moves it to the DLQ. Deleting it destroys the only copy.
        var good = Message.builder()
                .messageId("msg-good").receiptHandle("receipt-good").body(VALID_BODY).build();
        var bad = Message.builder()
                .messageId("msg-bad").receiptHandle("receipt-bad").body("{not json").build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(good, bad).build());
        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder().build());

        consumer.poll();

        var captor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
        verify(sqsClient).deleteMessageBatch(captor.capture());

        assertThat(captor.getValue().entries())
                .extracting(DeleteMessageBatchRequestEntry::receiptHandle)
                .containsExactly("receipt-good");
    }

    @Test
    void shouldStillHandleTheParseableMessagesInAMixedBatch() {
        var good = Message.builder()
                .messageId("msg-good").receiptHandle("receipt-good").body(VALID_BODY).build();
        var bad = Message.builder()
                .messageId("msg-bad").receiptHandle("receipt-bad").body("{not json").build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(good, bad).build());
        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder().build());

        consumer.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FlightPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(handler).handle(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().icao24()).isEqualTo("abc123");
    }

    @Test
    void shouldNotCallDeleteWhenEveryMessageIsMalformed() {
        var bad = Message.builder()
                .messageId("msg-bad").receiptHandle("receipt-bad").body("{not json").build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(bad).build());

        consumer.poll();

        verifyNoInteractions(handler);
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }
}

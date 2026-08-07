package skytrack.demo.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.service.FlightPositionHandler;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class SqsPositionConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsPositionConsumer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final FlightPositionHandler handler;

    public SqsPositionConsumer(SqsClient sqsClient, String queueUrl, FlightPositionHandler handler) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
        this.handler = handler;
    }

    /**
     * @return the number of messages received, or 0 if the queue was empty or the poll failed.
     *         Callers loop on this to distinguish "did work" from "found nothing / errored", which
     *         a continuous worker needs in order to back off instead of spinning.
     */
    public int poll() {
        try {
            ReceiveMessageResponse response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .build());

            List<Message> messages = response.messages();
            if (messages.isEmpty()) {
                return 0;
            }

            log.debug("Received {} messages from SQS", messages.size());

            List<FlightPosition> positions = new ArrayList<>();
            List<Message> parsed = new ArrayList<>();
            for (Message message : messages) {
                try {
                    positions.add(mapper.readValue(message.body(), FlightPosition.class));
                    parsed.add(message);
                } catch (Exception e) {
                    // Deliberately NOT added to `parsed`, so it is never deleted below. Leaving it
                    // on the queue is the only way it can reach the DLQ: ApproximateReceiveCount
                    // climbs on each redelivery until the RedrivePolicy (maxReceiveCount: 5) moves
                    // it to skytrack-dlq.fifo. Deleting it here — which is what this code used to
                    // do — destroyed the only copy and left the DLQ unreachable from the one
                    // failure mode that actually occurs.
                    //
                    // Safe under FIFO because MessageGroupId is icao24: an undeleted message blocks
                    // only its own aircraft, for at most maxReceiveCount * VisibilityTimeout (150s).
                    log.error("Malformed message {} left on queue for redrive: {}",
                            message.messageId(), e.getMessage());
                }
            }

            if (!positions.isEmpty()) {
                handler.handle(positions);
            }

            if (!parsed.isEmpty()) {
                deleteMessages(parsed);
            }
            return messages.size();
        } catch (SqsException e) {
            log.error("Failed to receive messages from SQS", e);
            return 0;
        } catch (Exception e) {
            // handler.handle() threw. The @Primary handler catches per position, so reaching here
            // means a downstream/infrastructure failure rather than one bad message — retrying the
            // whole batch after the visibility timeout is correct. Returning 0 also makes the
            // worker back off instead of hot-looping against a broken dependency.
            log.error("Failed to process messages — will NOT delete from queue (messages will reappear after visibility timeout)", e);
            return 0;
        }
    }

    private void deleteMessages(List<Message> messages) {
        List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            entries.add(DeleteMessageBatchRequestEntry.builder()
                    .id(String.valueOf(i))
                    .receiptHandle(messages.get(i).receiptHandle())
                    .build());
        }

        sqsClient.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                .queueUrl(queueUrl)
                .entries(entries)
                .build());

        log.debug("Deleted {} messages from SQS", messages.size());
    }
}

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

    public void poll() {
        try {
            ReceiveMessageResponse response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .build());

            List<Message> messages = response.messages();
            if (messages.isEmpty()) {
                return;
            }

            log.debug("Received {} messages from SQS", messages.size());

            List<FlightPosition> positions = new ArrayList<>();
            for (Message message : messages) {
                try {
                    FlightPosition fp = mapper.readValue(message.body(), FlightPosition.class);
                    positions.add(fp);
                } catch (Exception e) {
                    log.error("Failed to deserialize message {}: {}", message.messageId(), e.getMessage());
                }
            }

            handler.handle(positions);

            deleteMessages(messages);
        } catch (SqsException e) {
            log.error("Failed to receive messages from SQS", e);
        } catch (Exception e) {
            log.error("Failed to process messages — will NOT delete from queue (messages will reappear after visibility timeout)", e);
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

package skytrack.demo.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skytrack.demo.model.FlightPosition;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class SqsPositionProducer {

    private static final Logger log = LoggerFactory.getLogger(SqsPositionProducer.class);
    private static final int MAX_BATCH_SIZE = 10;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SqsClient sqsClient;
    private final String queueUrl;

    public SqsPositionProducer(SqsClient sqsClient, String queueUrl) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    public void send(List<FlightPosition> positions) {
        if (positions.isEmpty()) {
            return;
        }

        for (int i = 0; i < positions.size(); i += MAX_BATCH_SIZE) {
            List<FlightPosition> batch = positions.subList(i, Math.min(i + MAX_BATCH_SIZE, positions.size()));
            sendBatch(batch);
        }
    }

    private void sendBatch(List<FlightPosition> batch) {
        try {
            List<SendMessageBatchRequestEntry> entries = new ArrayList<>();

            for (int i = 0; i < batch.size(); i++) {
                FlightPosition fp = batch.get(i);
                String body = mapper.writeValueAsString(fp);

                entries.add(SendMessageBatchRequestEntry.builder()
                        .id(String.valueOf(i))
                        .messageBody(body)
                        .messageGroupId(fp.icao24())
                        .messageDeduplicationId(fp.icao24() + "-" + fp.timePosition())
                        .build());
            }

            SendMessageBatchResponse response = sqsClient.sendMessageBatch(SendMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(entries)
                    .build());

            if (!response.failed().isEmpty()) {
                // SQS returns 200 for a batch where individual entries failed. Without this the loss is
                // invisible: the positions are simply never queued and nothing downstream can tell.
                log.error("SQS rejected {} of {} positions in batch (first: {} - {})",
                        response.failed().size(), entries.size(),
                        response.failed().get(0).code(), response.failed().get(0).message());
            }

            log.debug("Sent batch of {} positions to SQS", batch.size() - response.failed().size());
        } catch (Exception e) {
            log.error("Failed to send batch of {} positions to SQS", batch.size(), e);
        }
    }
}

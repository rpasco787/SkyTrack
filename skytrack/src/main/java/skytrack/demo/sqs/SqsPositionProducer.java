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

    /**
     * @return the number of positions SQS accepted. Callers log this rather than the input size:
     *         {@code sendBatch} swallows transport failures and SQS returns HTTP 200 for batches
     *         with per-entry failures, so the input size is not evidence that anything was queued.
     */
    public int send(List<FlightPosition> positions) {
        if (positions.isEmpty()) {
            return 0;
        }

        int accepted = 0;
        // Collected across the whole send and logged once. One ERROR per failed batch means ~740
        // synchronous console appends per poll on the single thread that also drives the next poll;
        // the back-pressure delays ingestion long before the log volume matters.
        String firstFailure = null;

        for (int i = 0; i < positions.size(); i += MAX_BATCH_SIZE) {
            List<FlightPosition> batch = positions.subList(i, Math.min(i + MAX_BATCH_SIZE, positions.size()));
            BatchOutcome outcome = sendBatch(batch);
            accepted += outcome.accepted();
            if (firstFailure == null && outcome.failureReason() != null) {
                firstFailure = outcome.failureReason();
            }
        }

        int rejected = positions.size() - accepted;
        if (rejected > 0) {
            log.error("SQS rejected {} of {} positions this poll (first failure: {})",
                    rejected, positions.size(), firstFailure);
        }
        return accepted;
    }

    /** @param failureReason null when the whole batch was accepted. */
    private record BatchOutcome(int accepted, String failureReason) {}

    private BatchOutcome sendBatch(List<FlightPosition> batch) {
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

            int failed = response.failed().size();
            if (failed == 0) {
                return new BatchOutcome(entries.size(), null);
            }
            var first = response.failed().get(0);
            return new BatchOutcome(entries.size() - failed, first.code() + " - " + first.message());
        } catch (Exception e) {
            return new BatchOutcome(0, e.toString());
        }
    }
}

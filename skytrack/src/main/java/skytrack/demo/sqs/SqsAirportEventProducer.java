package skytrack.demo.sqs;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skytrack.demo.model.DelayEvent;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class SqsAirportEventProducer {

    private static final Logger log = LoggerFactory.getLogger(SqsAirportEventProducer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SqsClient sqsClient;
    private final String queueUrl;

    public SqsAirportEventProducer(SqsClient sqsClient, String queueUrl) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    public void send(DelayEvent event) {
        try {
            String body = mapper.writeValueAsString(event);
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .messageGroupId(event.arrivalAirportIata())
                    .messageDeduplicationId(event.icao24() + "-" + event.actualArrivalTime())
                    .build());
            log.debug("Published delay event for {} at {} to airport events queue",
                    event.callsign(), event.arrivalAirportIata());
        } catch (Exception e) {
            log.error("Failed to publish delay event for {} at {}: {}",
                    event.callsign(), event.arrivalAirportIata(), e.getMessage(), e);
        }
    }
}

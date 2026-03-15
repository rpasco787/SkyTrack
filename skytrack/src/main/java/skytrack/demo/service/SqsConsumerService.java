package skytrack.demo.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.sqs.SqsPositionConsumer;

@Service
public class SqsConsumerService {

    private final SqsPositionConsumer consumer;

    public SqsConsumerService(SqsPositionConsumer consumer) {
        this.consumer = consumer;
    }

    @Scheduled(fixedDelay = 1000)
    public void consumePositions() {
        consumer.poll();
    }
}

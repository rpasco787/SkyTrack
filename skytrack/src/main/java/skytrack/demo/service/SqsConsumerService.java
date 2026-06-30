package skytrack.demo.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.config.SqsProperties;
import skytrack.demo.sqs.SqsPositionConsumer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

@Service
public class SqsConsumerService {

    private final SqsPositionConsumer consumer;
    private final ExecutorService consumerPool;
    private final int consumerThreads;

    public SqsConsumerService(SqsPositionConsumer consumer,
                              @Qualifier("sqsConsumerPool") ExecutorService consumerPool,
                              SqsProperties properties) {
        this.consumer = consumer;
        this.consumerPool = consumerPool;
        this.consumerThreads = Math.max(properties.consumerThreads(), 1);
    }

    @Scheduled(fixedDelay = 1000)
    public void consumePositions() {
        if (consumerThreads == 1) {
            consumer.poll();
            return;
        }
        List<Callable<Void>> tasks = new ArrayList<>(consumerThreads);
        for (int i = 0; i < consumerThreads; i++) {
            tasks.add(() -> { consumer.poll(); return null; });
        }
        try {
            consumerPool.invokeAll(tasks);   // blocks until every poll() completes
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

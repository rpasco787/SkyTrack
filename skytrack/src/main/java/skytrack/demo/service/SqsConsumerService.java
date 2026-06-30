package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.config.SqsProperties;
import skytrack.demo.sqs.SqsPositionConsumer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Service
public class SqsConsumerService {

    private static final Logger log = LoggerFactory.getLogger(SqsConsumerService.class);

    private final SqsPositionConsumer consumer;
    private final ExecutorService consumerPool;
    private final int consumerThreads;

    public SqsConsumerService(SqsPositionConsumer consumer,
                              @Qualifier("sqsConsumerPool") ExecutorService consumerPool,
                              SqsProperties properties) {
        this.consumer = consumer;
        this.consumerPool = consumerPool;
        this.consumerThreads = properties.consumerThreads();
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
            List<Future<Void>> futures = consumerPool.invokeAll(tasks);
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ex) {
                    log.error("Consumer poll failed in thread pool", ex.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

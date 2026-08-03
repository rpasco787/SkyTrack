package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import skytrack.demo.config.SqsProperties;
import skytrack.demo.sqs.SqsPositionConsumer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SqsConsumerService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SqsConsumerService.class);

    /** Floor on retry rate when a poll returns no work. See {@link #idleBackoff()}. */
    static final long IDLE_BACKOFF_MILLIS = 1000;

    private final SqsPositionConsumer consumer;
    private final ExecutorService consumerPool;
    private final int consumerThreads;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SqsConsumerService(SqsPositionConsumer consumer,
                              @Qualifier("sqsConsumerPool") ExecutorService consumerPool,
                              SqsProperties properties) {
        this.consumer = consumer;
        this.consumerPool = consumerPool;
        this.consumerThreads = properties.consumerThreads();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        for (int i = 0; i < consumerThreads; i++) {
            consumerPool.submit(this::runWorker);
        }
        log.info("Started {} SQS consumer worker(s)", consumerThreads);
    }

    /**
     * Continuous loop rather than a scheduled tick. When the backlog is deep, poll() returns
     * immediately with a full batch and the worker goes straight back for more; the previous
     * fixedDelay(1000) + invokeAll barrier capped throughput near 10 messages/thread/second no
     * matter how deep the backlog, and made every thread wait for the slowest.
     */
    private void runWorker() {
        while (running.get()) {
            int handled = 0;
            try {
                handled = consumer.poll();
            } catch (Exception e) {
                // Never let one failure kill the worker — that would silently reduce capacity for
                // the rest of the run, with no signal beyond this line.
                log.error("Consumer worker poll failed", e);
            }
            if (handled == 0) {
                idleBackoff();
            }
        }
    }

    /**
     * poll() long-polls for up to 20s, but it swallows its own errors and returns immediately when
     * the queue is unreachable. Without this floor a worker would spin at full CPU against a broken
     * queue, flooding the log — strictly worse than the fixedDelay(1000) this replaced. The pause
     * applies only when the poll did no work, so a real backlog is still drained at full speed.
     */
    private void idleBackoff() {
        try {
            Thread.sleep(IDLE_BACKOFF_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}

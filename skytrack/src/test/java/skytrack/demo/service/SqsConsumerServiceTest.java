package skytrack.demo.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.config.SqsProperties;
import skytrack.demo.sqs.SqsPositionConsumer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsConsumerServiceTest {

    @Mock
    private SqsPositionConsumer consumer;

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) pool.shutdownNow();
    }

    private SqsConsumerService service(int threads) {
        pool = Executors.newFixedThreadPool(Math.max(threads, 1));
        var props = new SqsProperties(null, null, null, null, null, threads);
        return new SqsConsumerService(consumer, pool, props);
    }

    @Test
    void singleThreadPollsOncePerTick() {
        service(1).consumePositions();
        verify(consumer, times(1)).poll();
    }

    @Test
    void parallelDispatchesOnePollPerConfiguredThread() {
        service(4).consumePositions();
        verify(consumer, times(4)).poll();
    }

    @Test
    void waitsForAllPollsBeforeReturning() {
        // each poll() sleeps briefly; after consumePositions() returns, all must have run
        doAnswer(inv -> { Thread.sleep(20); return null; }).when(consumer).poll();
        service(4).consumePositions();
        verify(consumer, times(4)).poll();   // verified immediately after return → invokeAll blocked
    }
}

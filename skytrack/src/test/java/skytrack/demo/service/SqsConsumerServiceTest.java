package skytrack.demo.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.config.SqsProperties;
import skytrack.demo.sqs.SqsPositionConsumer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsConsumerServiceTest {

    @Mock
    private SqsPositionConsumer consumer;

    private ExecutorService pool;
    private SqsConsumerService service;

    @AfterEach
    void tearDown() {
        if (service != null) service.stop();
        if (pool != null) pool.shutdownNow();
    }

    private SqsConsumerService service(int threads) {
        pool = Executors.newFixedThreadPool(Math.max(threads, 1));
        var props = new SqsProperties(null, null, null, null, null, threads);
        service = new SqsConsumerService(consumer, pool, props);
        return service;
    }

    @Test
    void drainsABacklogContinuouslyRatherThanOncePerTick() throws Exception {
        var latch = new CountDownLatch(25);
        // A full batch every time: this is the deep-backlog case the change exists for.
        when(consumer.poll()).thenAnswer(inv -> { latch.countDown(); return 10; });

        service(2).start();

        // 25 polls in well under the 25 seconds the old fixedDelay(1000) design would need.
        assertThat(latch.await(3, TimeUnit.SECONDS))
                .as("workers must loop continuously while there is work, not once per scheduler tick")
                .isTrue();
    }

    @Test
    void backsOffInsteadOfSpinningWhenThereIsNoWork() throws Exception {
        var polls = new AtomicInteger();
        // poll() swallows its own errors and returns 0 immediately when the queue is unreachable.
        when(consumer.poll()).thenAnswer(inv -> { polls.incrementAndGet(); return 0; });

        service(1).start();
        Thread.sleep(1500);

        assertThat(polls.get())
                .as("an idle or broken queue must not be polled in a tight loop; with a %dms floor "
                        + "roughly 2 polls fit in 1.5s", SqsConsumerService.IDLE_BACKOFF_MILLIS)
                .isLessThanOrEqualTo(4);
        assertThat(polls.get()).isGreaterThan(0);
    }

    @Test
    void stopHaltsTheWorkers() throws Exception {
        when(consumer.poll()).thenReturn(10);

        var svc = service(1);
        svc.start();
        svc.stop();

        Thread.sleep(100);   // let any in-flight poll finish
        clearInvocations(consumer);
        Thread.sleep(200);

        verify(consumer, never()).poll();
        assertThat(svc.isRunning()).isFalse();
    }

    @Test
    void startsOneWorkerPerConfiguredThread() throws Exception {
        var started = new CountDownLatch(4);
        when(consumer.poll()).thenAnswer(inv -> { started.countDown(); Thread.sleep(50); return 10; });

        service(4).start();

        assertThat(started.await(2, TimeUnit.SECONDS))
                .as("every configured thread must get its own continuous worker")
                .isTrue();
    }

    @Test
    void aThrowingPollDoesNotKillTheWorker() throws Exception {
        var latch = new CountDownLatch(2);
        when(consumer.poll()).thenAnswer(inv -> {
            latch.countDown();
            throw new RuntimeException("SQS unavailable");
        });

        service(1).start();

        // Backoff applies after a throw too, so 2 attempts need ~1s.
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("one failure must not silently reduce consumer capacity for the rest of the run")
                .isTrue();
    }

    @Test
    void startIsIdempotent() throws Exception {
        var latch = new CountDownLatch(1);
        when(consumer.poll()).thenAnswer(inv -> { latch.countDown(); Thread.sleep(50); return 10; });

        var svc = service(1);
        svc.start();
        svc.start();   // must not submit a second set of workers

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(svc.isRunning()).isTrue();
    }
}

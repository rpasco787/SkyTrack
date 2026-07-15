package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.model.AirportDisruptionScore;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class DisruptionScoreServiceConcurrencyTest {

    private DelayEvent onTime(String airport, long arrivalTime) {
        return new DelayEvent("abc123", "UAL1234", "UA", "1234",
                "K" + airport, airport, arrivalTime,
                arrivalTime, 0L, DelayClassification.ON_TIME, "AEROAPI",
                Instant.ofEpochSecond(arrivalTime), null, null, null, null);
    }

    @Test
    void recordDelayDoesNotLoseUpdatesUnderConcurrency() throws Exception {
        var service = new DisruptionScoreService(new DisruptionScoreProperties(60, 1, 15, 30, 0.85, 0.15, 8));
        int threads = 8, perThread = 5_000;
        long arrival = 1_709_312_400L;               // all land in the same 1-min bucket

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) service.recordDelay(onTime("ORD", arrival));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        AirportDisruptionScore score = service.computeScore("ORD");
        assertThat(score.totalFlightsInWindow()).isEqualTo(threads * perThread);
    }
}

package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.model.AirportDisruptionScore;
import skytrack.demo.model.DelayEvent;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Service
@EnableConfigurationProperties(DisruptionScoreProperties.class)
public class DisruptionScoreService {

    private static final Logger log = LoggerFactory.getLogger(DisruptionScoreService.class);

    private final DisruptionScoreProperties props;
    private final Map<String, TreeMap<Long, BucketMetrics>> airportBuckets = new ConcurrentHashMap<>();

    public DisruptionScoreService(DisruptionScoreProperties props) {
        this.props = props;
    }

    public void recordDelay(DelayEvent event) {
        if (event.arrivalAirportIata() == null) return;

        long bucketKey = toBucketKey(event.actualArrivalTime());
        TreeMap<Long, BucketMetrics> buckets =
                airportBuckets.computeIfAbsent(event.arrivalAirportIata(), k -> new TreeMap<>());
        synchronized (buckets) {
            buckets.computeIfAbsent(bucketKey, k -> new BucketMetrics()).record(event);
        }
    }

    public AirportDisruptionScore computeScore(String airportIata) {
        TreeMap<Long, BucketMetrics> buckets = airportBuckets.get(airportIata);
        if (buckets == null) {
            return emptyScore(airportIata);
        }

        synchronized (buckets) {
            if (buckets.isEmpty()) {
                return emptyScore(airportIata);
            }

            long latestBucket = buckets.lastKey();
            long windowStart = latestBucket - (props.windowMinutes() * 60L);
            evictExpiredBuckets(buckets, windowStart);

            if (buckets.isEmpty()) {
                return emptyScore(airportIata);
            }

            int totalFlights = 0;
            int delayedFlights = 0;
            long totalDelaySeconds = 0;

            for (BucketMetrics bucket : buckets.values()) {
                totalFlights += bucket.totalFlights;
                delayedFlights += bucket.delayedFlights;
                totalDelaySeconds += bucket.totalDelaySeconds;
            }

            double avgDelayMinutes = totalFlights > 0
                    ? (totalDelaySeconds / 60.0) / totalFlights : 0;
            double delayedPct = totalFlights > 0
                    ? (double) delayedFlights / totalFlights : 0;
            double trend = computeTrend(buckets, windowStart, latestBucket);

            double delayedFlightScore = Math.min(delayedFlights / 10.0, 1.0) * 30;
            double severityScore = Math.min(avgDelayMinutes / 60.0, 1.0) * 30;
            double trendScore = Math.max(Math.min(trend, 1.0), 0.0) * 20;
            double percentageScore = delayedPct * 20;
            double score = Math.min(
                    delayedFlightScore + severityScore + trendScore + percentageScore, 100.0);

            return new AirportDisruptionScore(
                    airportIata, score, delayedFlights, totalFlights,
                    avgDelayMinutes, trend, Instant.now());
        }
    }

    public List<AirportDisruptionScore> getTopDisruptedAirports(int limit) {
        return airportBuckets.keySet().stream()
                .map(this::computeScore)
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(AirportDisruptionScore::score).reversed())
                .limit(limit)
                .toList();
    }

    private double computeTrend(TreeMap<Long, BucketMetrics> buckets,
                                 long windowStart, long latestBucket) {
        long midpoint = windowStart + (latestBucket - windowStart) / 2;
        int firstHalfDelayed = 0, secondHalfDelayed = 0;
        int firstHalfTotal = 0, secondHalfTotal = 0;

        for (var entry : buckets.entrySet()) {
            if (entry.getKey() < midpoint) {
                firstHalfDelayed += entry.getValue().delayedFlights;
                firstHalfTotal += entry.getValue().totalFlights;
            } else {
                secondHalfDelayed += entry.getValue().delayedFlights;
                secondHalfTotal += entry.getValue().totalFlights;
            }
        }

        double firstRate = firstHalfTotal > 0
                ? (double) firstHalfDelayed / firstHalfTotal : 0;
        double secondRate = secondHalfTotal > 0
                ? (double) secondHalfDelayed / secondHalfTotal : 0;
        return secondRate - firstRate;
    }

    private void evictExpiredBuckets(TreeMap<Long, BucketMetrics> buckets, long windowStart) {
        buckets.headMap(windowStart).clear();
    }

    private long toBucketKey(long epochSeconds) {
        long bucketSize = props.bucketSizeMinutes() * 60L;
        return (epochSeconds / bucketSize) * bucketSize;
    }

    private AirportDisruptionScore emptyScore(String airportIata) {
        return new AirportDisruptionScore(airportIata, 0.0, 0, 0, 0.0, 0.0, Instant.now());
    }

    static class BucketMetrics {
        int totalFlights;
        int delayedFlights;
        long totalDelaySeconds;

        void record(DelayEvent event) {
            totalFlights++;
            if (event.classification() != null && event.classification().isDelayed()) {
                delayedFlights++;
            }
            if (event.delaySeconds() != null && event.delaySeconds() > 0) {
                totalDelaySeconds += event.delaySeconds();
            }
        }
    }
}

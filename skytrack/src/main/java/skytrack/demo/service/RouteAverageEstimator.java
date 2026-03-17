package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.FlightSchedule;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RouteAverageEstimator {

    private static final int MIN_OBSERVATIONS = 3;

    // Key: "ICAO_CARRIER:IATA_AIRPORT" e.g. "UAL:LAX"
    private final Map<String, RunningAverage> averages = new ConcurrentHashMap<>();

    public void record(FlightSchedule schedule) {
        if (schedule.scheduledArrival() == null || schedule.actualArrival() == null) return;
        if (schedule.destination() == null || schedule.airline() == null) return;

        long delaySeconds = schedule.actualArrival().getEpochSecond()
                - schedule.scheduledArrival().getEpochSecond();
        String key = schedule.airline() + ":" + schedule.destination();
        averages.computeIfAbsent(key, k -> new RunningAverage()).add(delaySeconds);
    }

    public OptionalDouble estimateDelaySeconds(String carrierIcao, String airportIata) {
        String key = carrierIcao + ":" + airportIata;
        RunningAverage avg = averages.get(key);
        if (avg == null || avg.count() < MIN_OBSERVATIONS) return OptionalDouble.empty();
        return OptionalDouble.of(avg.average());
    }

    private static class RunningAverage {
        private final AtomicLong sum = new AtomicLong(0);
        private final AtomicInteger count = new AtomicInteger(0);

        void add(long value) {
            sum.addAndGet(value);
            count.incrementAndGet();
        }

        int count() { return count.get(); }
        double average() { return (double) sum.get() / count.get(); }
    }
}

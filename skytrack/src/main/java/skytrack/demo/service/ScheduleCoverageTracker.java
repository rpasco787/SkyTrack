package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.ScheduleCoverage;

import java.util.concurrent.atomic.LongAdder;

@Service
public class ScheduleCoverageTracker {

    private final LongAdder verified = new LongAdder();
    private final LongAdder estimated = new LongAdder();
    private final LongAdder unresolved = new LongAdder();

    public void record(String resolutionMethod) {
        if ("AEROAPI".equals(resolutionMethod)) {
            verified.increment();
        } else if ("ROUTE_AVERAGE".equals(resolutionMethod)) {
            estimated.increment();
        } else {
            unresolved.increment();
        }
    }

    public ScheduleCoverage snapshot() {
        long v = verified.sum();
        long e = estimated.sum();
        long u = unresolved.sum();
        long total = v + e + u;
        double rate = total > 0 ? (double) v / total : 0.0;
        return new ScheduleCoverage(total, v, e, u, rate);
    }
}

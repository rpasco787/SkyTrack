package skytrack.demo.service;

import org.springframework.stereotype.Service;

@Service
public class DelayPredictor {

    public long predictDelaySeconds(long inboundArrivalEpoch, long outboundScheduledDepEpoch,
                                    long minTurnaroundSeconds) {
        long earliestReady = inboundArrivalEpoch + minTurnaroundSeconds;
        return Math.max(0, earliestReady - outboundScheduledDepEpoch);
    }
}

package skytrack.demo.service;

import org.springframework.stereotype.Service;

/**
 * Departure delay as two additive terms:
 *
 * <pre>
 *   predicted = baselinePrior(carrier, origin, hour) + max(0, readyAt - scheduledDeparture)
 * </pre>
 *
 * <p>The second term alone is a feasibility check, not a prediction: it fires only when the
 * turnaround is physically impossible, so every rotation with adequate slack scores zero. The
 * first term carries everything a turnaround model cannot express — crew, boarding, gate and ATC
 * habits at a given carrier and station.</p>
 *
 * <p>The terms are kept disjoint upstream: the prior is fitted only on flights with no
 * late-aircraft delay, which is precisely what the pressure term models. Fitting both on the same
 * flights would double-count the cascade.</p>
 */
@Service
public class DelayPredictor {

    public long predictDelaySeconds(long inboundArrivalEpoch, long outboundScheduledDepEpoch,
                                    long turnaroundSeconds) {
        return predictDelaySeconds(inboundArrivalEpoch, outboundScheduledDepEpoch,
                turnaroundSeconds, 0L);
    }

    /**
     * @param turnaroundSeconds    how long this aircraft is <em>expected</em> to take on the
     *                             ground, not the fastest it could manage — a floor here reads as
     *                             systematic under-prediction.
     * @param baselinePriorSeconds habitual departure delay for this carrier / station / hour.
     *                             Commonly negative, since most flights push back early.
     * @return signed seconds; may be negative when the prior is negative and slack absorbs the
     *         turnaround. The ground truth (BTS {@code DEP_DELAY}) is signed too, so clamping
     *         would bias every slack rotation upward.
     */
    public long predictDelaySeconds(long inboundArrivalEpoch, long outboundScheduledDepEpoch,
                                    long turnaroundSeconds, long baselinePriorSeconds) {
        long readyAt = inboundArrivalEpoch + turnaroundSeconds;
        long pressure = Math.max(0, readyAt - outboundScheduledDepEpoch);
        return baselinePriorSeconds + pressure;
    }
}

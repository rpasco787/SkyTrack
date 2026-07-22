package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.config.PredictionProperties;

import java.util.Map;

@Service
public class TurnaroundEstimator {

    private final PredictionProperties props;
    private final Map<String, Long> carrierTurnarounds;

    public TurnaroundEstimator(PredictionProperties props, Map<String, Long> carrierTurnarounds) {
        this.props = props;
        this.carrierTurnarounds = carrierTurnarounds;
    }

    /**
     * Returns the minimum turnaround time in seconds for the given carrier IATA code.
     * If a carrier-specific override exists in carrierTurnarounds, it is used;
     * otherwise falls back to the configured default (minTurnaroundMinutes).
     */
    public long minTurnaroundSeconds(String carrierIata) {
        if (carrierIata != null && carrierTurnarounds.containsKey(carrierIata)) {
            return carrierTurnarounds.get(carrierIata);
        }
        return props.minTurnaroundMinutes() * 60L;
    }
}

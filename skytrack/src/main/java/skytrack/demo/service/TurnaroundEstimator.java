package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.config.PredictionProperties;

@Service
public class TurnaroundEstimator {

    private final PredictionProperties props;

    public TurnaroundEstimator(PredictionProperties props) {
        this.props = props;
    }

    // aircraftType reserved for future tiering; ignored in v1 (BTS has no type data)
    public long minTurnaroundSeconds(String aircraftType) {
        return props.minTurnaroundMinutes() * 60L;
    }
}

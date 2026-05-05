package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.model.CascadeAlert;
import skytrack.demo.model.DelayEvent;

import java.time.Instant;
import java.util.Optional;

@Service
@EnableConfigurationProperties(DisruptionScoreProperties.class)
public class CascadeDetector {

    private static final Logger log = LoggerFactory.getLogger(CascadeDetector.class);

    private final DisruptionScoreProperties props;

    public CascadeDetector(DisruptionScoreProperties props) {
        this.props = props;
    }

    public Optional<CascadeAlert> checkCascade(DelayEvent event) {
        if (event.delaySeconds() == null) return Optional.empty();

        long delayMinutes = event.delaySeconds() / 60;
        if (delayMinutes < props.cascadeThresholdMinutes()) return Optional.empty();

        long predictedDownstream = (long) (event.delaySeconds() * props.cascadePropagationFactor());

        if (predictedDownstream / 60 < props.delayThresholdMinutes()) return Optional.empty();

        var alert = new CascadeAlert(
                event.callsign(),
                event.arrivalAirportIata(),
                event.delaySeconds(),
                predictedDownstream,
                props.cascadePropagationFactor(),
                Instant.now());

        log.info("Cascade alert: {} at {} delay={}min -> predicted downstream={}min",
                event.callsign(), event.arrivalAirportIata(),
                delayMinutes, predictedDownstream / 60);

        return Optional.of(alert);
    }
}

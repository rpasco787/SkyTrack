package skytrack.demo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import skytrack.demo.service.AirportTimeZoneResolver;
import skytrack.demo.service.BaselineDelayPrior;
import skytrack.demo.service.BtsScheduleRepository;

import java.util.Map;

@Configuration
public class PredictionConfig {

    private static final Logger log = LoggerFactory.getLogger(PredictionConfig.class);

    /**
     * An empty repository here disables prediction everywhere downstream without any downstream
     * component being able to say so — {@code OutboundScheduleResolver} just returns empty and
     * {@code DelayPredictionService} returns before predicting. So this is the only place that can
     * report the condition, and it does so at startup rather than waiting to be asked.
     * {@link BtsScheduleHealthIndicator} exposes the same state on {@code /actuator/health}.
     */
    @Bean
    BtsScheduleRepository btsScheduleRepository(PredictionProperties props,
                                                        AirportTimeZoneResolver tz) {
        if (!props.enabled()) {
            log.info("Delay prediction is disabled (skytrack.prediction.enabled=false) — "
                    + "no departure predictions will be emitted");
            return BtsScheduleRepository.empty();
        }

        var repo = BtsScheduleRepository.fromCsv(props.btsCsvPath(), tz);
        if (repo.size() == 0) {
            log.warn("BTS schedule loaded 0 records from {} — every prediction will be skipped "
                    + "silently. The path is resolved against the application working directory; "
                    + "check it points at the on-time CSV and that its date format parses.",
                    props.btsCsvPath());
        } else {
            log.info("BTS schedule loaded {} records from {}", repo.size(), props.btsCsvPath());
        }
        return repo;
    }

    /**
     * Habitual (non-cascade) departure delay by carrier / origin / hour of day, fitted from the
     * loaded BTS window. This is the additive baseline term the turnaround-pressure term is
     * layered on top of; without it the predictor emits zero for every flight that has enough
     * slack to be physically feasible.
     */
    @Bean
    BaselineDelayPrior baselineDelayPrior(BtsScheduleRepository repo, AirportTimeZoneResolver tz) {
        return BaselineDelayPrior.from(repo, tz);
    }

    /**
     * Minimum turnaround overrides (seconds), keyed by {@code "CARRIER|AIRPORT"} and by bare
     * carrier code. Fitted as the p15 of actual ground time on rotations where the inbound landed
     * late, so it approximates the physical floor rather than the padded schedule; an empty map
     * uses the configured default.
     */
    @Bean
    Map<String, Long> carrierTurnarounds(BtsScheduleRepository repo) {
        return repo.pressuredTurnaroundP15ByCarrierAirport();
    }

    /**
     * Turnaround times (seconds) for the <em>prediction</em> path, keyed the same way as
     * {@link #carrierTurnarounds}: the p50 of the same pressured population rather than the p15.
     * Slack wants the floor; a prediction of when the aircraft will actually be ready wants the
     * typical case, or it assumes every crew hits its best-ever turnaround.
     */
    @Bean
    Map<String, Long> expectedTurnarounds(BtsScheduleRepository repo) {
        return repo.pressuredTurnaroundP50ByCarrierAirport();
    }

    /**
     * Per-route en-route recovery factors keyed by "ORIGIN-DEST" (e.g. "ORD-DEN").
     * When a route key is present the value overrides the global enRouteRecoveryFactor.
     * Derived from BTS historical on-time data; empty map uses the global default.
     */
    @Bean
    Map<String, Double> routeRecoveryFactors(BtsScheduleRepository repo) {
        return repo.medianRecoveryFactorByRoute();
    }
}

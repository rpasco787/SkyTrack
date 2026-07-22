package skytrack.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import skytrack.demo.service.AirportTimeZoneResolver;
import skytrack.demo.service.BtsScheduleRepository;

import java.util.Map;

@Configuration
public class PredictionConfig {

    @Bean
    BtsScheduleRepository btsScheduleRepository(PredictionProperties props,
                                                        AirportTimeZoneResolver tz) {
        if (!props.enabled()) return BtsScheduleRepository.empty();
        return BtsScheduleRepository.fromCsv(props.btsCsvPath(), tz);
    }

    /**
     * Carrier-specific minimum turnaround overrides (seconds), keyed by IATA carrier code.
     * Derived from BTS median ground time per carrier; empty map uses the configured default.
     */
    @Bean
    Map<String, Long> carrierTurnarounds(BtsScheduleRepository repo) {
        return repo.medianTurnaroundSecondsByCarrier();
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

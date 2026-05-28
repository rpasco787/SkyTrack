package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.client.WeatherSource;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.WeatherObservation;

import java.util.List;

@Service
public class WeatherPollingService {

    private static final Logger log = LoggerFactory.getLogger(WeatherPollingService.class);

    private final WeatherSource source;
    private final WeatherCache cache;
    private final WeatherProperties properties;

    public WeatherPollingService(WeatherSource source, WeatherCache cache,
                                 WeatherProperties properties) {
        this.source = source;
        this.cache = cache;
        this.properties = properties;
    }

    @Scheduled(fixedRateString = "#{${weather.poll-interval-minutes:15} * 60 * 1000}",
               initialDelay = 5_000)
    public void refresh() {
        if (properties.targetAirports().isEmpty()) {
            log.debug("Weather poll skipped: no target airports configured");
            return;
        }
        try {
            List<WeatherObservation> observations =
                    source.fetchObservations(properties.targetAirports());
            cache.update(observations);
            log.info("Weather poll: fetched {} observations for {} target airports",
                    observations.size(), properties.targetAirports().size());
        } catch (Exception e) {
            log.error("Weather poll failed: {}", e.getMessage(), e);
        }
    }
}

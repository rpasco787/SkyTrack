package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.WeatherObservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WeatherCache {

    private static final Logger log = LoggerFactory.getLogger(WeatherCache.class);

    private final ConcurrentHashMap<String, CachedObservation> entries = new ConcurrentHashMap<>();
    private final WeatherProperties properties;
    private final Clock clock;

    public WeatherCache(WeatherProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<WeatherObservation> get(String airportIcao) {
        if (airportIcao == null) return Optional.empty();
        CachedObservation cached = entries.get(airportIcao);
        if (cached == null) return Optional.empty();
        Duration age = Duration.between(cached.cachedAt(), clock.instant());
        if (age.toMinutes() >= properties.cacheTtlMinutes()) {
            entries.remove(airportIcao, cached);
            return Optional.empty();
        }
        return Optional.of(cached.observation());
    }

    public void update(List<WeatherObservation> observations) {
        Instant now = clock.instant();
        for (WeatherObservation obs : observations) {
            entries.put(obs.airportIcao(), new CachedObservation(obs, now));
        }
        log.debug("Weather cache updated: size={}, refreshed={}", entries.size(), observations.size());
    }

    public int size() {
        return entries.size();
    }

    private record CachedObservation(WeatherObservation observation, Instant cachedAt) {}
}

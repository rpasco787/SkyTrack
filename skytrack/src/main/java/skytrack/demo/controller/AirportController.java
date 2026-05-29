package skytrack.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.AirportDisruptionScore;
import skytrack.demo.model.AirportStatusResponse;
import skytrack.demo.service.DisruptionScoreService;
import skytrack.demo.service.RecentCascadeStore;
import skytrack.demo.service.WeatherCache;

import java.util.List;

@RestController
public class AirportController {

    private final DisruptionScoreService disruptionScoreService;
    private final WeatherCache weatherCache;
    private final RecentCascadeStore recentCascadeStore;

    public AirportController(DisruptionScoreService disruptionScoreService,
                             WeatherCache weatherCache,
                             RecentCascadeStore recentCascadeStore) {
        this.disruptionScoreService = disruptionScoreService;
        this.weatherCache = weatherCache;
        this.recentCascadeStore = recentCascadeStore;
    }

    @GetMapping("/airports/{iata}/status")
    public AirportStatusResponse status(@PathVariable String iata) {
        var score = disruptionScoreService.computeScore(iata);
        // TODO: replace with AirportLookupService mapping for non-US airports
        var weather = weatherCache.get("K" + iata).orElse(null);
        return new AirportStatusResponse(score, weather, recentCascadeStore.getRecent(iata));
    }

    @GetMapping("/airports/disruptions")
    public List<AirportDisruptionScore> disruptions(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") double minScore) {
        return disruptionScoreService.getTopDisruptedAirports(limit).stream()
                .filter(s -> s.score() >= minScore)
                .toList();
    }
}

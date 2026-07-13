package skytrack.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.PredictedDelayEvent;
import skytrack.demo.model.PredictionAccuracySummary;
import skytrack.demo.service.PredictionAccuracyService;
import skytrack.demo.service.RecentPredictionStore;

import java.util.List;

@RestController
public class PredictionController {

    private final RecentPredictionStore recentPredictionStore;
    private final PredictionAccuracyService accuracyService;

    public PredictionController(RecentPredictionStore recentPredictionStore,
                                PredictionAccuracyService accuracyService) {
        this.recentPredictionStore = recentPredictionStore;
        this.accuracyService = accuracyService;
    }

    @GetMapping("/predictions/{iata}")
    public List<PredictedDelayEvent> predictions(@PathVariable String iata) {
        return recentPredictionStore.getRecent(iata);
    }

    @GetMapping("/predictions/{iata}/accuracy")
    public PredictionAccuracySummary accuracy(@PathVariable String iata) {
        return accuracyService.summarize(iata, recentPredictionStore.getRecent(iata));
    }
}

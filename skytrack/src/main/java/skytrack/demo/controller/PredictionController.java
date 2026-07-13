package skytrack.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.PredictedDelayEvent;
import skytrack.demo.service.RecentPredictionStore;

import java.util.List;

@RestController
public class PredictionController {

    private final RecentPredictionStore recentPredictionStore;

    public PredictionController(RecentPredictionStore recentPredictionStore) {
        this.recentPredictionStore = recentPredictionStore;
    }

    @GetMapping("/predictions/{iata}")
    public List<PredictedDelayEvent> predictions(@PathVariable String iata) {
        return recentPredictionStore.getRecent(iata);
    }
}

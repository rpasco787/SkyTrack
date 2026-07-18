package skytrack.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.CascadeAccuracySummary;
import skytrack.demo.model.CascadeChain;
import skytrack.demo.service.CascadeAccuracyService;
import skytrack.demo.service.RecentCascadeStore;

import java.util.List;

@RestController
public class CascadeController {

    private final RecentCascadeStore recentCascadeStore;
    private final CascadeAccuracyService cascadeAccuracyService;

    public CascadeController(RecentCascadeStore recentCascadeStore,
                             CascadeAccuracyService cascadeAccuracyService) {
        this.recentCascadeStore = recentCascadeStore;
        this.cascadeAccuracyService = cascadeAccuracyService;
    }

    @GetMapping("/cascades/{iata}")
    public List<CascadeChain> cascades(@PathVariable String iata) {
        return recentCascadeStore.getRecent(iata);
    }

    @GetMapping("/cascades/{iata}/accuracy")
    public CascadeAccuracySummary accuracy(@PathVariable String iata) {
        return cascadeAccuracyService.summarize(iata, recentCascadeStore.getRecent(iata));
    }
}

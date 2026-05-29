package skytrack.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.CascadeAlert;
import skytrack.demo.service.RecentCascadeStore;

import java.util.List;

@RestController
public class CascadeController {

    private final RecentCascadeStore recentCascadeStore;

    public CascadeController(RecentCascadeStore recentCascadeStore) {
        this.recentCascadeStore = recentCascadeStore;
    }

    @GetMapping("/cascades/{iata}")
    public List<CascadeAlert> cascades(@PathVariable String iata) {
        return recentCascadeStore.getRecent(iata);
    }
}

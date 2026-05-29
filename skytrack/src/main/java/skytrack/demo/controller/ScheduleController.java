package skytrack.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.ScheduleCoverage;
import skytrack.demo.service.ScheduleCoverageTracker;

@RestController
public class ScheduleController {

    private final ScheduleCoverageTracker tracker;

    public ScheduleController(ScheduleCoverageTracker tracker) {
        this.tracker = tracker;
    }

    @GetMapping("/schedule/coverage")
    public ScheduleCoverage coverage() {
        return tracker.snapshot();
    }
}

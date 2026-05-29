package skytrack.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.parquet.DelayParquetRow;
import skytrack.demo.service.AnalyticsService;

import java.util.List;

@RestController
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/analytics/delays")
    public List<DelayParquetRow> delays(
            @RequestParam(required = false) String airport,
            @RequestParam String date) {
        return analyticsService.queryDelays(airport, date);
    }
}

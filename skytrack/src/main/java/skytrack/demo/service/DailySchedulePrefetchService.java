package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.model.FlightSchedule;

import java.time.LocalDate;
import java.util.List;

@Service
public class DailySchedulePrefetchService {
    private static final Logger log = LoggerFactory.getLogger(DailySchedulePrefetchService.class);
    private final FlightScheduleApiClient scheduleApiClient;

    public DailySchedulePrefetchService(FlightScheduleApiClient scheduleApiClient) {
        this.scheduleApiClient = scheduleApiClient;
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void prefetchDailySchedules() {
        String today = LocalDate.now().toString();
        try {
            List<FlightSchedule> schedules = scheduleApiClient.getDailyFlights(today);
            log.info("Prefetched {} flight schedules for {}", schedules.size(), today);
            // TODO (Week 2): Write to DynamoDB cache
        } catch (Exception e) {
            log.error("Daily schedule prefetch failed for {}", today, e);
        }
    }
}

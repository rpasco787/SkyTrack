package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.client.FlightScheduleApiClient;
import skytrack.demo.model.FlightSchedule;
import java.time.Instant;
import java.util.List;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailySchedulePrefetchServiceTest {
    @Mock private FlightScheduleApiClient scheduleApiClient;
    @InjectMocks private DailySchedulePrefetchService prefetchService;

    @Test
    void shouldCallGetDailyFlights() {
        when(scheduleApiClient.getDailyFlights(anyString())).thenReturn(List.of(
                new FlightSchedule("UAL1234", "UA1234", "UAL", "ORD", "LAX",
                        Instant.now(), Instant.now().plusSeconds(9000),
                        null, null, null, null, "B738")));
        prefetchService.prefetchDailySchedules();
        verify(scheduleApiClient, times(1)).getDailyFlights(anyString());
    }

    @Test
    void shouldHandleEmptyResults() {
        when(scheduleApiClient.getDailyFlights(anyString())).thenReturn(List.of());
        prefetchService.prefetchDailySchedules();
        verify(scheduleApiClient, times(1)).getDailyFlights(anyString());
    }

    @Test
    void shouldHandleApiException() {
        when(scheduleApiClient.getDailyFlights(anyString())).thenThrow(new RuntimeException("API down"));
        prefetchService.prefetchDailySchedules();
        verify(scheduleApiClient, times(1)).getDailyFlights(anyString());
    }
}

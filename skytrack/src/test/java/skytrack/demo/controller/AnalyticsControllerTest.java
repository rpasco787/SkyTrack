package skytrack.demo.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import skytrack.demo.parquet.DelayParquetRow;
import skytrack.demo.service.AnalyticsService;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    @Mock AnalyticsService analyticsService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(analyticsService)).build();
    }

    @Test
    void shouldReturnHistoricalDelays() throws Exception {
        when(analyticsService.queryDelays(eq("ORD"), eq("2026-05-29"))).thenReturn(List.of(
                new DelayParquetRow("abc", "UAL1", "UA", "1", "KORD", "ORD",
                        1748528400L, 1748527500L, 900L, "MAJOR_DELAY", "AEROAPI",
                        1748528400000L, "IFR", 2.0, 800, 12)));

        mockMvc.perform(get("/analytics/delays").param("airport", "ORD").param("date", "2026-05-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].arrivalAirportIata").value("ORD"))
                .andExpect(jsonPath("$[0].delaySeconds").value(900));
    }
}

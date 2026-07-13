package skytrack.demo.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.PredictedDelayEvent;
import skytrack.demo.model.PredictionAccuracySummary;
import skytrack.demo.service.PredictionAccuracyService;
import skytrack.demo.service.RecentPredictionStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PredictionControllerTest {

    @Mock RecentPredictionStore recentPredictionStore;
    @Mock PredictionAccuracyService accuracyService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new PredictionController(recentPredictionStore, accuracyService)).build();
    }

    @Test
    void shouldReturnRecentPredictions() throws Exception {
        var event = new PredictedDelayEvent(
                "UAL1234", "N12345", "ORD",
                "UA", "5678",
                1773088200L, 1773090000L, 2700L, 960L,
                DelayClassification.MINOR, 900L, "BTS_REPLAY",
                Instant.parse("2026-03-09T20:30:00Z"));

        when(recentPredictionStore.getRecent("ORD")).thenReturn(List.of(event));

        mockMvc.perform(get("/predictions/ORD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].inboundCallsign").value("UAL1234"))
                .andExpect(jsonPath("$[0].departureAirportIata").value("ORD"))
                .andExpect(jsonPath("$[0].predictedDelaySeconds").value(960));
    }

    @Test
    void shouldReturnEmptyListForUnknownAirport() throws Exception {
        when(recentPredictionStore.getRecent("ZZZ")).thenReturn(List.of());

        mockMvc.perform(get("/predictions/ZZZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldReturnAccuracySummary() throws Exception {
        var summary = new PredictionAccuracySummary("ORD", 5, 3, 450.0,
                Map.of("MODERATE", Map.of("MINOR", 2), "MAJOR", Map.of("MAJOR", 1)));

        when(recentPredictionStore.getRecent(eq("ORD"))).thenReturn(List.of());
        when(accuracyService.summarize(eq("ORD"), any())).thenReturn(summary);

        mockMvc.perform(get("/predictions/ORD/accuracy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.airportIata").value("ORD"))
                .andExpect(jsonPath("$.totalPredictions").value(5))
                .andExpect(jsonPath("$.backtestableCount").value(3))
                .andExpect(jsonPath("$.meanAbsoluteErrorSeconds").value(450.0));
    }
}

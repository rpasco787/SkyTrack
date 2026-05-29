package skytrack.demo.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import skytrack.demo.model.AirportDisruptionScore;
import skytrack.demo.service.DisruptionScoreService;
import skytrack.demo.service.RecentCascadeStore;
import skytrack.demo.service.WeatherCache;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AirportControllerTest {

    @Mock DisruptionScoreService disruptionScoreService;
    @Mock WeatherCache weatherCache;
    @Mock RecentCascadeStore recentCascadeStore;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AirportController(disruptionScoreService, weatherCache, recentCascadeStore))
                .build();
    }

    @Test
    void shouldReturnAirportStatus() throws Exception {
        when(disruptionScoreService.computeScore("ORD")).thenReturn(
                new AirportDisruptionScore("ORD", 72.5, 8, 20, 35.0, 0.2, Instant.now()));
        when(weatherCache.get("KORD")).thenReturn(Optional.empty());
        when(recentCascadeStore.getRecent("ORD")).thenReturn(List.of());

        mockMvc.perform(get("/airports/ORD/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score.airportIata").value("ORD"))
                .andExpect(jsonPath("$.score.score").value(72.5));
    }

    @Test
    void shouldReturnTopDisruptions() throws Exception {
        when(disruptionScoreService.getTopDisruptedAirports(anyInt())).thenReturn(List.of(
                new AirportDisruptionScore("ORD", 80.0, 10, 25, 40.0, 0.3, Instant.now()),
                new AirportDisruptionScore("ATL", 55.0, 6, 18, 25.0, 0.1, Instant.now())));

        mockMvc.perform(get("/airports/disruptions").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].airportIata").value("ORD"))
                .andExpect(jsonPath("$[1].airportIata").value("ATL"));
    }

    @Test
    void shouldFilterByMinScore() throws Exception {
        when(disruptionScoreService.getTopDisruptedAirports(anyInt())).thenReturn(List.of(
                new AirportDisruptionScore("ORD", 80.0, 10, 25, 40.0, 0.3, Instant.now()),
                new AirportDisruptionScore("ATL", 55.0, 6, 18, 25.0, 0.1, Instant.now())));

        mockMvc.perform(get("/airports/disruptions").param("minScore", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].airportIata").value("ORD"));
    }
}

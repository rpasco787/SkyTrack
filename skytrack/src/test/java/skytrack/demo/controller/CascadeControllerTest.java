package skytrack.demo.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import skytrack.demo.model.CascadeAccuracySummary;
import skytrack.demo.model.CascadeChain;
import skytrack.demo.model.CascadeHop;
import skytrack.demo.service.CascadeAccuracyService;
import skytrack.demo.service.RecentCascadeStore;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CascadeControllerTest {

    @Mock RecentCascadeStore recentCascadeStore;
    @Mock CascadeAccuracyService cascadeAccuracyService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CascadeController(recentCascadeStore, cascadeAccuracyService))
                .build();
    }

    @Test
    void shouldReturnRecentCascades() throws Exception {
        var hop = new CascadeHop("UA", "200", "N1", "ORD", "DEN", 1000L, 3600L, 1800L);
        var chain = CascadeChain.of("UAL1", "ORD", 2400L, List.of(hop), Instant.now());
        when(recentCascadeStore.getRecent("ORD")).thenReturn(List.of(chain));

        mockMvc.perform(get("/cascades/ORD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceCallsign").value("UAL1"))
                .andExpect(jsonPath("$[0].originAirportIata").value("ORD"))
                .andExpect(jsonPath("$[0].flightsAffected").value(1))
                .andExpect(jsonPath("$[0].totalPredictedDelaySeconds").value(3600))
                .andExpect(jsonPath("$[0].hops[0].destIata").value("DEN"));
    }

    @Test
    void shouldReturnAccuracySummary() throws Exception {
        var summary = new CascadeAccuracySummary("ORD", 3, 7, 5, 1200.0, 2.33);
        when(recentCascadeStore.getRecent("ORD")).thenReturn(List.of());
        when(cascadeAccuracyService.summarize("ORD", List.of())).thenReturn(summary);

        mockMvc.perform(get("/cascades/ORD/accuracy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backtestableHops").value(5))
                .andExpect(jsonPath("$.hopLevelMaeSeconds").value(1200.0))
                .andExpect(jsonPath("$.totalChains").value(3));
    }
}

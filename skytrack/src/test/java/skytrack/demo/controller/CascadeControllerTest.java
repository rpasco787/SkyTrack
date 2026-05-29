package skytrack.demo.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import skytrack.demo.model.CascadeAlert;
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

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CascadeController(recentCascadeStore)).build();
    }

    @Test
    void shouldReturnRecentCascades() throws Exception {
        when(recentCascadeStore.getRecent("ORD")).thenReturn(List.of(
                new CascadeAlert("UAL1", "ORD", 2400L, 2040L, 0.85, Instant.now())));

        mockMvc.perform(get("/cascades/ORD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceCallsign").value("UAL1"))
                .andExpect(jsonPath("$[0].arrivalAirportIata").value("ORD"));
    }
}

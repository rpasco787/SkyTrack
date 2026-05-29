package skytrack.demo.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import skytrack.demo.model.ScheduleCoverage;
import skytrack.demo.service.ScheduleCoverageTracker;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ScheduleControllerTest {

    @Mock ScheduleCoverageTracker tracker;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ScheduleController(tracker)).build();
    }

    @Test
    void shouldReturnCoverageSnapshot() throws Exception {
        when(tracker.snapshot()).thenReturn(new ScheduleCoverage(100, 92, 5, 3, 0.92));

        mockMvc.perform(get("/schedule/coverage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(100))
                .andExpect(jsonPath("$.verified").value(92))
                .andExpect(jsonPath("$.verifiedRate").value(0.92));
    }
}

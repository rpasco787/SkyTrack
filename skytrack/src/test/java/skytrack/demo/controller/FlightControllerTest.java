package skytrack.demo.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import skytrack.demo.model.AircraftTrack;
import skytrack.demo.repository.AircraftTrackRepository;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FlightControllerTest {

    @Mock AircraftTrackRepository repository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FlightController(repository)).build();
    }

    @Test
    void shouldReturnTrackForKnownCallsign() throws Exception {
        AircraftTrack track = AircraftTrack.initial("abc123");
        track.setCallsign("UAL100");
        track.setLatitude(41.9);
        track.setLongitude(-87.9);
        when(repository.findByCallsign("UAL100")).thenReturn(Optional.of(track));

        mockMvc.perform(get("/flights/UAL100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icao24").value("abc123"))
                .andExpect(jsonPath("$.callsign").value("UAL100"));
    }

    @Test
    void shouldReturn404ForUnknownCallsign() throws Exception {
        when(repository.findByCallsign("ZZZ999")).thenReturn(Optional.empty());
        mockMvc.perform(get("/flights/ZZZ999")).andExpect(status().isNotFound());
    }
}

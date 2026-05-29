package skytrack.demo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.AircraftTrack;
import skytrack.demo.repository.AircraftTrackRepository;

@RestController
public class FlightController {

    private final AircraftTrackRepository repository;

    public FlightController(AircraftTrackRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/flights/{callsign}")
    public ResponseEntity<AircraftTrack> flight(@PathVariable String callsign) {
        return repository.findByCallsign(callsign)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

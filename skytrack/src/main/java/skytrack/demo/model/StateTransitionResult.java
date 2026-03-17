package skytrack.demo.model;

import java.util.Optional;

public record StateTransitionResult(
        AircraftTrack updatedTrack,
        Optional<LandingEvent> landingEvent) {}

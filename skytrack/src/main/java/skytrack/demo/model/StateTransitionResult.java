package skytrack.demo.model;

import java.util.Optional;

/**
 * @param stateChanged whether this position moved the track to a different {@link AircraftState}.
 *                     Drives conditional persistence: a position that changes nothing carries no
 *                     information worth a DynamoDB write.
 */
public record StateTransitionResult(
        AircraftTrack updatedTrack,
        Optional<LandingEvent> landingEvent,
        boolean stateChanged) {}

package skytrack.demo.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.*;

import java.util.Optional;

@Component
@EnableConfigurationProperties(StateMachineProperties.class)
public class AircraftStateMachine {

    private final AirportLookupService airportLookup;
    private final StateMachineProperties props;

    public AircraftStateMachine(AirportLookupService airportLookup, StateMachineProperties props) {
        this.airportLookup = airportLookup;
        this.props = props;
    }

    public StateTransitionResult process(AircraftTrack track, FlightPosition position) {
        AircraftState currentState = track.getAircraftState();

        // Check for stale timeout. lastSeen is the last *persisted* contact, so the threshold is
        // widened by the persist interval — see StateMachineProperties#effectiveStaleTimeoutSeconds.
        if (track.getLastSeen() != null
                && (position.lastContact() - track.getLastSeen()) > props.effectiveStaleTimeoutSeconds()) {
            updateTrackFields(track, position);
            track.setAircraftState(AircraftState.UNKNOWN);
            track.setStateEnteredAt(position.lastContact());
            track.setNearestAirportIcao(null);
            return new StateTransitionResult(track, Optional.empty(),
                    currentState != AircraftState.UNKNOWN);
        }

        Optional<Airport> groundAirport = airportLookup.findNearest(
                position.latitude(), position.longitude(), props.groundRadiusKm());

        AircraftState newState = currentState;
        Optional<LandingEvent> landingEvent = Optional.empty();

        switch (currentState) {
            case UNKNOWN -> {
                if (position.onGround() && groundAirport.isPresent()) {
                    // First position is on ground — set state but don't emit landing
                    newState = AircraftState.ON_GROUND;
                    track.setNearestAirportIcao(groundAirport.get().icaoCode());
                } else if (!position.onGround()) {
                    newState = AircraftState.EN_ROUTE;
                }
            }
            case EN_ROUTE -> {
                if (position.onGround() && groundAirport.isPresent()) {
                    newState = AircraftState.ON_GROUND;
                    track.setNearestAirportIcao(groundAirport.get().icaoCode());
                    landingEvent = Optional.of(createLandingEvent(position, groundAirport.get()));
                } else {
                    Optional<Airport> approachAirport = airportLookup.findNearest(
                            position.latitude(), position.longitude(), props.approachRadiusKm());
                    if (approachAirport.isPresent() && isDescending(track, position)) {
                        newState = AircraftState.APPROACHING;
                        track.setNearestAirportIcao(approachAirport.get().icaoCode());
                    }
                }
            }
            case APPROACHING -> {
                if (position.onGround() && groundAirport.isPresent()) {
                    newState = AircraftState.ON_GROUND;
                    track.setNearestAirportIcao(groundAirport.get().icaoCode());
                    landingEvent = Optional.of(createLandingEvent(position, groundAirport.get()));
                } else {
                    Optional<Airport> approachAirport = airportLookup.findNearest(
                            position.latitude(), position.longitude(), props.approachRadiusKm());
                    if (approachAirport.isEmpty() || isClimbing(track, position)) {
                        newState = AircraftState.EN_ROUTE;
                        track.setNearestAirportIcao(null);
                    }
                }
            }
            case ON_GROUND -> {
                if (!position.onGround()) {
                    newState = AircraftState.DEPARTED;
                    track.setNearestAirportIcao(null);
                }
            }
            case DEPARTED -> {
                if (!position.onGround()) {
                    newState = AircraftState.EN_ROUTE;
                }
            }
        }

        // Update state if changed
        if (newState != currentState) {
            track.setAircraftState(newState);
            track.setStateEnteredAt(position.lastContact());
        }

        updateTrackFields(track, position);
        return new StateTransitionResult(track, landingEvent, newState != currentState);
    }

    private void updateTrackFields(AircraftTrack track, FlightPosition position) {
        track.setCallsign(position.callsign());
        track.setLatitude(position.latitude());
        track.setLongitude(position.longitude());
        track.setBaroAltitude(position.baroAltitude());
        track.setLastSeen(position.lastContact());
    }

    private boolean isDescending(AircraftTrack track, FlightPosition position) {
        if (track.getBaroAltitude() == null || position.baroAltitude() == null) return false;
        return position.baroAltitude() < track.getBaroAltitude();
    }

    private boolean isClimbing(AircraftTrack track, FlightPosition position) {
        if (track.getBaroAltitude() == null || position.baroAltitude() == null) return false;
        return position.baroAltitude() > track.getBaroAltitude() + 100;
    }

    private LandingEvent createLandingEvent(FlightPosition position, Airport airport) {
        return new LandingEvent(
                position.icao24(),
                position.callsign(),
                airport.icaoCode() != null ? airport.icaoCode() : airport.ident(),
                airport.iataCode(),
                position.lastContact(),
                position.latitude(),
                position.longitude());
    }
}

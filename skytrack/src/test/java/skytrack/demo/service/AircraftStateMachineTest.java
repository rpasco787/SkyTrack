package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.*;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AircraftStateMachineTest {

    private AircraftStateMachine stateMachine;
    private AirportLookupService airportLookup;

    // ORD coords and test airport
    private static final Airport ORD = new Airport(
            "KORD", "KORD", "ORD", "O'Hare International Airport",
            41.9742, -87.9073, "large_airport");

    @BeforeEach
    void setUp() {
        airportLookup = mock(AirportLookupService.class);
        var props = new StateMachineProperties(150.0, 50.0, 5.0, 300);
        stateMachine = new AircraftStateMachine(airportLookup, props);
    }

    private FlightPosition position(String callsign, double lat, double lon,
                                     double alt, boolean onGround, long time) {
        return new FlightPosition("abc123", callsign, lat, lon, alt,
                230.0, 270.0, onGround, time, time - 5, Instant.ofEpochSecond(time));
    }

    @Test
    void shouldTransitionFromUnknownToEnRouteWhenAirborne() {
        var track = AircraftTrack.initial("abc123");
        var pos = position("UAL1234", 41.0, -88.0, 10000.0, false, 1000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.empty());
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldTransitionFromEnRouteToOnGroundAndEmitLandingEvent() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.EN_ROUTE);
        track.setBaroAltitude(3000.0);
        track.setLastSeen(900L);

        var pos = position("UAL1234", 41.9742, -87.9073, 0.0, true, 1000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.of(ORD));

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(result.landingEvent()).isPresent();

        LandingEvent event = result.landingEvent().get();
        assertThat(event.icao24()).isEqualTo("abc123");
        assertThat(event.callsign()).isEqualTo("UAL1234");
        assertThat(event.arrivalAirportIcao()).isEqualTo("KORD");
        assertThat(event.arrivalAirportIata()).isEqualTo("ORD");
    }

    @Test
    void shouldTransitionFromOnGroundToDeparted() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.ON_GROUND);
        track.setBaroAltitude(0.0);
        track.setLastSeen(900L);

        var pos = position("UAL1234", 41.9742, -87.9073, 500.0, false, 1000L);

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.DEPARTED);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldNotEmitDuplicateLandingEventWhenStayingOnGround() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.ON_GROUND);
        track.setBaroAltitude(0.0);
        track.setLastSeen(900L);

        var pos = position("UAL1234", 41.9742, -87.9073, 0.0, true, 1000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.of(ORD));

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldTransitionToApproachingWhenDescendingNearAirport() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.EN_ROUTE);
        track.setBaroAltitude(5000.0);
        track.setLastSeen(900L);

        // Position at 3000m, descending, within approach radius
        var pos = position("UAL1234", 42.1, -87.8, 3000.0, false, 1000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.of(ORD));
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.APPROACHING);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldResetToUnknownOnStaleTimeout() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.EN_ROUTE);
        track.setLastSeen(100L);

        // Position arrives 400 seconds after last seen (> 300s timeout)
        var pos = position("UAL1234", 41.0, -88.0, 10000.0, false, 500L);

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.UNKNOWN);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldTransitionFromDepartedToEnRoute() {
        var track = AircraftTrack.initial("abc123");
        track.setAircraftState(AircraftState.DEPARTED);
        track.setBaroAltitude(500.0);
        track.setLastSeen(900L);

        var pos = position("UAL1234", 41.0, -88.0, 3000.0, false, 1000L);

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
    }

    @Test
    void shouldHandleFullFlightSequence() {
        var track = AircraftTrack.initial("abc123");
        long t = 1000L;

        // 1. UNKNOWN → EN_ROUTE (airborne, far from airports)
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.empty());
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var r1 = stateMachine.process(track, position("UAL1234", 40.0, -90.0, 10000.0, false, t));
        assertThat(r1.updatedTrack().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
        track = r1.updatedTrack();
        t += 100;

        // 2. EN_ROUTE → APPROACHING (descending near ORD)
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.of(ORD));
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var r2 = stateMachine.process(track, position("UAL1234", 42.1, -87.8, 3000.0, false, t));
        assertThat(r2.updatedTrack().getAircraftState()).isEqualTo(AircraftState.APPROACHING);
        track = r2.updatedTrack();
        t += 100;

        // 3. APPROACHING → ON_GROUND (landed at ORD)
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.of(ORD));

        var r3 = stateMachine.process(track, position("UAL1234", 41.9742, -87.9073, 0.0, true, t));
        assertThat(r3.updatedTrack().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(r3.landingEvent()).isPresent();
        assertThat(r3.landingEvent().get().arrivalAirportIata()).isEqualTo("ORD");
        track = r3.updatedTrack();
        t += 100;

        // 4. ON_GROUND → DEPARTED (taking off)
        var r4 = stateMachine.process(track, position("UAL1234", 41.9742, -87.9073, 500.0, false, t));
        assertThat(r4.updatedTrack().getAircraftState()).isEqualTo(AircraftState.DEPARTED);
        track = r4.updatedTrack();
        t += 100;

        // 5. DEPARTED → EN_ROUTE (climbing)
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.empty());
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var r5 = stateMachine.process(track, position("UAL1234", 41.0, -88.0, 5000.0, false, t));
        assertThat(r5.updatedTrack().getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);
    }

    @Test
    void shouldNotEmitLandingForUnknownToOnGround() {
        // First-ever position is on the ground — we didn't observe the landing
        var track = AircraftTrack.initial("abc123");
        var pos = position("UAL1234", 41.9742, -87.9073, 0.0, true, 1000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.of(ORD));

        var result = stateMachine.process(track, pos);

        assertThat(result.updatedTrack().getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
        assertThat(result.landingEvent()).isEmpty();
    }

    @Test
    void shouldUpdateTrackFieldsOnEveryPosition() {
        var track = AircraftTrack.initial("abc123");
        var pos = position("UAL1234", 41.5, -88.5, 10000.0, false, 2000L);

        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(50.0)))
                .thenReturn(Optional.empty());
        when(airportLookup.findNearest(anyDouble(), anyDouble(), eq(5.0)))
                .thenReturn(Optional.empty());

        var result = stateMachine.process(track, pos);
        var updated = result.updatedTrack();

        assertThat(updated.getCallsign()).isEqualTo("UAL1234");
        assertThat(updated.getLatitude()).isEqualTo(41.5);
        assertThat(updated.getLongitude()).isEqualTo(-88.5);
        assertThat(updated.getBaroAltitude()).isEqualTo(10000.0);
        assertThat(updated.getLastSeen()).isEqualTo(2000L);
    }
}

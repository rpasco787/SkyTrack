package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.*;
import skytrack.demo.repository.AircraftTrackRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatefulFlightPositionHandlerTest {

    @Mock private AircraftTrackRepository repository;
    @Mock private AircraftStateMachine stateMachine;
    @Mock private ScheduleResolver scheduleResolver;
    @Mock private DelayEventProcessor delayEventProcessor;

    private StatefulFlightPositionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StatefulFlightPositionHandler(
                repository, stateMachine, scheduleResolver, delayEventProcessor,
                new StateMachineProperties(150.0, 50.0, 5.0, 300, 120));
    }

    private FlightPosition position(String icao24, String callsign) {
        return new FlightPosition(icao24, callsign, 41.9742, -87.9073,
                10668.0, 230.0, 270.0, false,
                1709312400L, 1709312400L, Instant.parse("2026-03-01T00:00:00Z"));
    }

    private FlightPosition positionAt(String icao24, long lastContact) {
        return new FlightPosition(icao24, "UAL1234", 41.9742, -87.9073,
                10668.0, 230.0, 270.0, false,
                lastContact, lastContact, Instant.parse("2026-03-01T00:00:00Z"));
    }

    private AircraftTrack trackWithLastSeen(long lastSeen) {
        var track = AircraftTrack.initial("abc");
        track.setLastSeen(lastSeen);
        return track;
    }

    @Test
    void shouldLoadTrackProcessAndSave() {
        var existingTrack = AircraftTrack.initial("abc123");
        when(repository.findByIcao24("abc123")).thenReturn(Optional.of(existingTrack));

        var result = new StateTransitionResult(existingTrack, Optional.empty(), false);
        when(stateMachine.process(eq(existingTrack), any(FlightPosition.class))).thenReturn(result);

        handler.handle(List.of(position("abc123", "UAL1234")));

        verify(repository).findByIcao24("abc123");
        verify(stateMachine).process(eq(existingTrack), any());
        verify(repository).save(existingTrack);
        verify(scheduleResolver, never()).resolve(any());
        verify(delayEventProcessor, never()).process(any());
    }

    @Test
    void shouldCreateInitialTrackForNewAircraft() {
        when(repository.findByIcao24("new123")).thenReturn(Optional.empty());

        var newTrack = AircraftTrack.initial("new123");
        var result = new StateTransitionResult(newTrack, Optional.empty(), false);
        when(stateMachine.process(any(AircraftTrack.class), any(FlightPosition.class))).thenReturn(result);

        handler.handle(List.of(position("new123", "DAL567")));

        verify(repository).findByIcao24("new123");
        verify(repository).save(any(AircraftTrack.class));
    }

    @Test
    void shouldCallScheduleResolverAndDelayProcessorOnLanding() {
        var track = AircraftTrack.initial("abc123");
        when(repository.findByIcao24("abc123")).thenReturn(Optional.of(track));

        var landingEvent = new LandingEvent("abc123", "UAL1234", "KORD", "ORD",
                1709312400L, 41.9742, -87.9073);
        var result = new StateTransitionResult(track, Optional.of(landingEvent), true);
        when(stateMachine.process(eq(track), any())).thenReturn(result);

        var resolved = new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, 1709312000L, 400L, "AEROAPI");
        when(scheduleResolver.resolve(landingEvent)).thenReturn(resolved);

        handler.handle(List.of(position("abc123", "UAL1234")));

        verify(scheduleResolver).resolve(landingEvent);
        verify(delayEventProcessor).process(resolved);
    }

    @Test
    void shouldContinueProcessingAfterErrorOnOnePosition() {
        when(repository.findByIcao24("bad123")).thenThrow(new RuntimeException("DynamoDB error"));

        var track = AircraftTrack.initial("good456");
        when(repository.findByIcao24("good456")).thenReturn(Optional.of(track));
        var result = new StateTransitionResult(track, Optional.empty(), false);
        when(stateMachine.process(eq(track), any())).thenReturn(result);

        handler.handle(List.of(
                position("bad123", "ERR1"),
                position("good456", "OK1")
        ));

        verify(repository).save(track);
    }

    @Test
    void skipsThePersistWhenNothingChangedAndTheHeartbeatIsNotDue() {
        var track = trackWithLastSeen(1_000L);
        when(repository.findByIcao24("abc")).thenReturn(Optional.of(track));
        when(stateMachine.process(any(), any()))
                .thenReturn(new StateTransitionResult(track, Optional.empty(), false));

        handler.handle(List.of(positionAt("abc", 1_030L)));   // 30s later, no state change

        verify(repository, never()).save(any());
    }

    @Test
    void persistsWhenTheStateChanged() {
        var track = trackWithLastSeen(1_000L);
        when(repository.findByIcao24("abc")).thenReturn(Optional.of(track));
        when(stateMachine.process(any(), any()))
                .thenReturn(new StateTransitionResult(track, Optional.empty(), true));

        handler.handle(List.of(positionAt("abc", 1_030L)));

        verify(repository).save(track);
    }

    @Test
    void persistsALandingEvenWhenTheStateDidNotChange() {
        var track = trackWithLastSeen(1_000L);
        when(repository.findByIcao24("abc")).thenReturn(Optional.of(track));
        var landingEvent = new LandingEvent("abc", "UAL1234", "KORD", "ORD",
                1_030L, 41.9742, -87.9073);
        when(stateMachine.process(any(), any()))
                .thenReturn(new StateTransitionResult(track, Optional.of(landingEvent), false));
        when(scheduleResolver.resolve(landingEvent)).thenReturn(null);

        handler.handle(List.of(positionAt("abc", 1_030L)));

        verify(repository).save(track);
    }

    @Test
    void persistsAsAHeartbeatOnceThePersistIntervalHasElapsed() {
        var track = trackWithLastSeen(1_000L);
        when(repository.findByIcao24("abc")).thenReturn(Optional.of(track));
        when(stateMachine.process(any(), any()))
                .thenReturn(new StateTransitionResult(track, Optional.empty(), false));

        handler.handle(List.of(positionAt("abc", 1_000L + 120L)));   // interval reached

        verify(repository).save(track);
    }

    @Test
    void persistsWhenTheTrackHasNeverBeenSeenBefore() {
        var track = AircraftTrack.initial("abc");   // initial() leaves lastSeen null
        when(repository.findByIcao24("abc")).thenReturn(Optional.of(track));
        when(stateMachine.process(any(), any()))
                .thenReturn(new StateTransitionResult(track, Optional.empty(), false));

        handler.handle(List.of(positionAt("abc", 1_030L)));

        verify(repository).save(track);
    }
}

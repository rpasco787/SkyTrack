package skytrack.demo.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.metrics.PipelineMetrics;
import skytrack.demo.model.*;
import skytrack.demo.repository.AircraftTrackRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatefulFlightPositionHandlerTest {

    @Mock private AircraftTrackRepository repository;
    @Mock private AircraftStateMachine stateMachine;
    @Mock private ScheduleResolver scheduleResolver;
    @Mock private DelayEventProcessor delayEventProcessor;

    private StatefulFlightPositionHandler handler;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        handler = new StatefulFlightPositionHandler(
                repository, stateMachine, scheduleResolver, delayEventProcessor,
                new StateMachineProperties(150.0, 50.0, 5.0, 300, 120),
                new PipelineMetrics(registry));
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

    /** The mutable map shape {@code findAllByIcao24} promises — the handler inserts into it. */
    private static Map<String, AircraftTrack> loaded(String icao24, AircraftTrack track) {
        var map = new HashMap<String, AircraftTrack>();
        map.put(icao24, track);
        return map;
    }

    private AircraftTrack trackWithLastSeen(long lastSeen) {
        var track = AircraftTrack.initial("abc");
        track.setLastSeen(lastSeen);
        return track;
    }

    @Test
    void shouldReadEachAircraftAtMostOncePerBatch() {
        var positions = List.of(
                positionAt("abc123", 1709312400L),
                positionAt("abc123", 1709312430L),
                positionAt("def456", 1709312400L));
        when(repository.findAllByIcao24(anyCollection())).thenReturn(new HashMap<>());

        handler.handle(positions);

        verify(repository, times(1)).findAllByIcao24(anyCollection());
        verify(repository, never()).findByIcao24(anyString());
    }

    @Test
    void shouldCarryUnsavedMutationsBetweenTwoPositionsForTheSameAircraft() {
        // The first position mutates the track but does not meet the persist condition. The second
        // must see those mutations; re-reading DynamoDB would silently discard them.
        var existing = AircraftTrack.initial("abc123");
        var loaded = new HashMap<String, AircraftTrack>();
        loaded.put("abc123", existing);
        when(repository.findAllByIcao24(anyCollection())).thenReturn(loaded);
        when(stateMachine.process(any(), any()))
                .thenReturn(new StateTransitionResult(existing, Optional.empty(), false));

        handler.handle(List.of(
                positionAt("abc123", 1709312400L),
                positionAt("abc123", 1709312430L)));

        var captor = ArgumentCaptor.forClass(AircraftTrack.class);
        verify(stateMachine, times(2)).process(captor.capture(), any());
        assertThat(captor.getAllValues().get(0))
                .describedAs("both positions must operate on the same in-memory track")
                .isSameAs(captor.getAllValues().get(1));
    }

    @Test
    void shouldLoadTrackProcessAndSave() {
        var existingTrack = AircraftTrack.initial("abc123");
        when(repository.findAllByIcao24(anyCollection())).thenReturn(loaded("abc123", existingTrack));

        var result = new StateTransitionResult(existingTrack, Optional.empty(), false);
        when(stateMachine.process(eq(existingTrack), any(FlightPosition.class))).thenReturn(result);

        handler.handle(List.of(position("abc123", "UAL1234")));

        verify(repository).findAllByIcao24(anyCollection());
        verify(stateMachine).process(eq(existingTrack), any());
        verify(repository).save(existingTrack);
        verify(scheduleResolver, never()).resolve(any());
        verify(delayEventProcessor, never()).process(any());
    }

    @Test
    void shouldCreateInitialTrackForNewAircraft() {
        // Unseen aircraft are simply absent from the batch-read map; the handler inserts
        // AircraftTrack.initial(...) via computeIfAbsent, which is why the map must be mutable.
        when(repository.findAllByIcao24(anyCollection())).thenReturn(new HashMap<>());

        var newTrack = AircraftTrack.initial("new123");
        var result = new StateTransitionResult(newTrack, Optional.empty(), false);
        when(stateMachine.process(any(AircraftTrack.class), any(FlightPosition.class))).thenReturn(result);

        handler.handle(List.of(position("new123", "DAL567")));

        verify(repository).findAllByIcao24(anyCollection());
        verify(repository).save(any(AircraftTrack.class));
    }

    @Test
    void shouldCallScheduleResolverAndDelayProcessorOnLanding() {
        var track = AircraftTrack.initial("abc123");
        when(repository.findAllByIcao24(anyCollection())).thenReturn(loaded("abc123", track));

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
    void incrementsLandingsDetectedOncePerLandingEvent() {
        var landing = new LandingEvent("abc123", "UAL1234", "KORD", "ORD",
                1709312400L, 41.9742, -87.9073);
        var track = AircraftTrack.initial("abc123");
        when(repository.findAllByIcao24(anyCollection())).thenReturn(loaded("abc123", track));
        when(stateMachine.process(any(), any()))
                .thenReturn(new StateTransitionResult(track, Optional.of(landing), true))
                .thenReturn(new StateTransitionResult(track, Optional.empty(), false));
        when(scheduleResolver.resolve(any())).thenReturn(new ResolvedArrival("abc123", "UAL1234", "UA", "1234",
                "KORD", "ORD", 1709312400L, null, null, "UNRESOLVED"));

        handler.handle(List.of(position("abc123", "UAL1234"), position("abc123", "UAL1234")));

        assertThat(registry.get(PipelineMetrics.LANDINGS_DETECTED).counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldContinueProcessingAfterErrorOnOnePosition() {
        // The read is now a single BatchGetItem before the loop, so a per-position failure can no
        // longer come from the repository — it has to come from processing. That is precisely what
        // the per-position try/catch is there to contain, so the test's intent is unchanged.
        var badTrack = AircraftTrack.initial("bad123");
        var track = AircraftTrack.initial("good456");
        var tracks = loaded("bad123", badTrack);
        tracks.put("good456", track);
        when(repository.findAllByIcao24(anyCollection())).thenReturn(tracks);

        when(stateMachine.process(eq(badTrack), any())).thenThrow(new RuntimeException("boom"));
        when(stateMachine.process(eq(track), any()))
                .thenReturn(new StateTransitionResult(track, Optional.empty(), false));

        handler.handle(List.of(
                position("bad123", "ERR1"),
                position("good456", "OK1")
        ));

        verify(repository).save(track);
    }

    @Test
    void skipsThePersistWhenNothingChangedAndTheHeartbeatIsNotDue() {
        var track = trackWithLastSeen(1_000L);
        when(repository.findAllByIcao24(anyCollection())).thenReturn(loaded("abc", track));
        when(stateMachine.process(any(), any()))
                .thenReturn(new StateTransitionResult(track, Optional.empty(), false));

        handler.handle(List.of(positionAt("abc", 1_030L)));   // 30s later, no state change

        verify(repository, never()).save(any());
    }

    @Test
    void persistsWhenTheStateChanged() {
        var track = trackWithLastSeen(1_000L);
        when(repository.findAllByIcao24(anyCollection())).thenReturn(loaded("abc", track));
        when(stateMachine.process(any(), any()))
                .thenReturn(new StateTransitionResult(track, Optional.empty(), true));

        handler.handle(List.of(positionAt("abc", 1_030L)));

        verify(repository).save(track);
    }

    @Test
    void persistsALandingEvenWhenTheStateDidNotChange() {
        var track = trackWithLastSeen(1_000L);
        when(repository.findAllByIcao24(anyCollection())).thenReturn(loaded("abc", track));
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
        when(repository.findAllByIcao24(anyCollection())).thenReturn(loaded("abc", track));
        when(stateMachine.process(any(), any()))
                .thenReturn(new StateTransitionResult(track, Optional.empty(), false));

        handler.handle(List.of(positionAt("abc", 1_000L + 120L)));   // interval reached

        verify(repository).save(track);
    }

    @Test
    void persistsWhenTheTrackHasNeverBeenSeenBefore() {
        var track = AircraftTrack.initial("abc");   // initial() leaves lastSeen null
        when(repository.findAllByIcao24(anyCollection())).thenReturn(loaded("abc", track));
        when(stateMachine.process(any(), any()))
                .thenReturn(new StateTransitionResult(track, Optional.empty(), false));

        handler.handle(List.of(positionAt("abc", 1_030L)));

        verify(repository).save(track);
    }
}

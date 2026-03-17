package skytrack.demo.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AircraftTrackTest {

    @Test
    void shouldBuildWithLombok() {
        var track = AircraftTrack.builder()
                .icao24("abc123")
                .sortKey("TRACK")
                .state("EN_ROUTE")
                .callsign("UAL1234")
                .latitude(41.9742)
                .longitude(-87.9073)
                .baroAltitude(10668.0)
                .build();

        assertThat(track.getIcao24()).isEqualTo("abc123");
        assertThat(track.getState()).isEqualTo("EN_ROUTE");
        assertThat(track.getCallsign()).isEqualTo("UAL1234");
    }

    @Test
    void shouldConvertAircraftStateEnum() {
        var track = AircraftTrack.builder()
                .icao24("abc123")
                .sortKey("TRACK")
                .state("EN_ROUTE")
                .build();

        assertThat(track.getAircraftState()).isEqualTo(AircraftState.EN_ROUTE);

        track.setAircraftState(AircraftState.ON_GROUND);
        assertThat(track.getState()).isEqualTo("ON_GROUND");
        assertThat(track.getAircraftState()).isEqualTo(AircraftState.ON_GROUND);
    }

    @Test
    void shouldCreateInitialTrack() {
        var track = AircraftTrack.initial("abc123");

        assertThat(track.getIcao24()).isEqualTo("abc123");
        assertThat(track.getSortKey()).isEqualTo("TRACK");
        assertThat(track.getAircraftState()).isEqualTo(AircraftState.UNKNOWN);
        assertThat(track.getStateEnteredAt()).isNotNull();
        assertThat(track.getUpdatedAt()).isNotNull();
        assertThat(track.getTtl()).isGreaterThan(track.getUpdatedAt());
    }

    @Test
    void shouldDefaultToUnknownWhenStateIsNull() {
        var track = new AircraftTrack();
        assertThat(track.getAircraftState()).isEqualTo(AircraftState.UNKNOWN);
    }
}

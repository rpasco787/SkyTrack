package skytrack.demo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateMachinePropertiesTest {

    @Test
    void persistIntervalMustBeShorterThanTheStaleTimeout() {
        // Otherwise lastSeen goes stale, the track resets to UNKNOWN, and landings from UNKNOWN
        // are silently not emitted.
        assertThatThrownBy(() -> new StateMachineProperties(150, 50, 5, 300, 300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("persistIntervalSeconds");
    }

    @Test
    void rejectsAPersistIntervalLongerThanTheStaleTimeout() {
        assertThatThrownBy(() -> new StateMachineProperties(150, 50, 5, 300, 301))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("staleTimeoutSeconds");
    }

    @Test
    void defaultsAnUnsetPersistIntervalRatherThanDisablingTheHeartbeat() {
        // Binding a yaml file that omits persist-interval-seconds yields 0. Treating that as
        // "never heartbeat" would be the landing-dropping configuration, so it defaults instead.
        assertThat(new StateMachineProperties(150, 50, 5, 300, 0).persistIntervalSeconds())
                .isEqualTo(120);
    }

    @Test
    void acceptsAnIntervalInsideTheStaleWindow() {
        assertThat(new StateMachineProperties(150, 50, 5, 300, 120).persistIntervalSeconds())
                .isEqualTo(120);
    }
}

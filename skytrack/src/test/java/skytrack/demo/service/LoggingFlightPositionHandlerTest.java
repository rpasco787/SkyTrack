package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.FlightPosition;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingFlightPositionHandlerTest {

    @Test
    void shouldHandlePositionsWithoutThrowing() {
        var handler = new LoggingFlightPositionHandler();
        var positions = List.of(
                new FlightPosition("abc123", "UAL1234", 41.97, -87.91,
                        10668.0, 230.5, 270.0, false,
                        1709312400L, 1709312400L, Instant.now())
        );

        assertThatCode(() -> handler.handle(positions))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldHandleEmptyList() {
        var handler = new LoggingFlightPositionHandler();

        assertThatCode(() -> handler.handle(List.of()))
                .doesNotThrowAnyException();
    }
}

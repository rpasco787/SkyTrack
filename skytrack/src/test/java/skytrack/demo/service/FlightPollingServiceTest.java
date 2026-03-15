package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.client.FlightDataSource;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.sqs.SqsPositionProducer;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightPollingServiceTest {

    @Mock
    private FlightDataSource flightDataSource;

    @Mock
    private SqsPositionProducer sqsPositionProducer;

    @InjectMocks
    private FlightPollingService pollingService;

    @Test
    void shouldFetchPositionsAndPublishToSqs() {
        var positions = List.of(
                new FlightPosition("abc123", "UAL1234", 41.97, -87.91,
                        10668.0, 230.5, 270.0, false,
                        1709312400L, 1709312400L, Instant.now())
        );
        when(flightDataSource.fetchPositions()).thenReturn(positions);

        pollingService.pollFlightData();

        verify(flightDataSource).fetchPositions();
        verify(sqsPositionProducer).send(positions);
    }

    @Test
    void shouldNotPublishWhenNoPositions() {
        when(flightDataSource.fetchPositions()).thenReturn(List.of());

        pollingService.pollFlightData();

        verify(flightDataSource).fetchPositions();
        verifyNoInteractions(sqsPositionProducer);
    }

    @Test
    void shouldHandleDataSourceException() {
        when(flightDataSource.fetchPositions()).thenThrow(new RuntimeException("Connection failed"));

        pollingService.pollFlightData();

        verify(flightDataSource).fetchPositions();
        verifyNoInteractions(sqsPositionProducer);
    }
}

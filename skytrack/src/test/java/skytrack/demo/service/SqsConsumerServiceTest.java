package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import skytrack.demo.sqs.SqsPositionConsumer;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SqsConsumerServiceTest {

    @Mock
    private SqsPositionConsumer consumer;

    @InjectMocks
    private SqsConsumerService service;

    @Test
    void shouldDelegateToConsumer() {
        service.consumePositions();

        verify(consumer).poll();
    }
}

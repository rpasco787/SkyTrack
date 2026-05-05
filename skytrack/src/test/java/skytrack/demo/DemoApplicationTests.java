package skytrack.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import skytrack.demo.sqs.SqsAirportEventProducer;
import skytrack.demo.sqs.SqsPositionConsumer;
import skytrack.demo.sqs.SqsPositionProducer;

@SpringBootTest
class DemoApplicationTests {

	@MockitoBean
	SqsPositionProducer sqsPositionProducer;

	@MockitoBean
	SqsPositionConsumer sqsPositionConsumer;

	@MockitoBean
	SqsAirportEventProducer sqsAirportEventProducer;

	@Test
	void contextLoads() {
	}

}

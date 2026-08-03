package skytrack.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import skytrack.demo.sqs.SqsAirportEventProducer;
import skytrack.demo.sqs.SqsPositionConsumer;
import skytrack.demo.sqs.SqsPositionProducer;

// The real BTS CSV is gitignored and the configured path is resolved against the app's working
// directory rather than surefire's (the repo root), so point the context at the committed fixture.
// Without this the context fails to start: fromCsv throws on a missing file.
@SpringBootTest(properties =
		"skytrack.prediction.bts-csv-path=skytrack/src/test/resources/backtest/bts-fixture-2026-03-09.csv")
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

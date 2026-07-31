package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import skytrack.demo.service.BtsScheduleRepository;
import skytrack.demo.sqs.SqsAirportEventProducer;
import skytrack.demo.sqs.SqsPositionConsumer;
import skytrack.demo.sqs.SqsPositionProducer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the prediction pipeline's <em>wiring</em>, which every other prediction test misses:
 * {@code AccuracyBacktestIT}, {@code GoldenAccuracyTest} and {@code PredictionPipelineIntegrationTest}
 * all hand-build their own object graph, so none of them can see a Spring-level misconfiguration.
 *
 * <p>Only {@code bts-csv-path} is overridden here, and deliberately so — it points at the committed
 * fixture because the real 196MB CSV is gitignored. Everything else, {@code enabled} above all,
 * is inherited from {@code application.yml}, so flipping that flag off fails this test. That is the
 * whole point: {@code enabled: false} substitutes an empty repository, which makes
 * {@code OutboundScheduleResolver} return empty for every arrival and the pipeline emit zero
 * predictions in complete silence.</p>
 */
@SpringBootTest(properties =
        "skytrack.prediction.bts-csv-path=skytrack/src/test/resources/backtest/bts-fixture-2026-03-09.csv")
class PredictionWiringTest {

    @MockitoBean SqsPositionProducer sqsPositionProducer;
    @MockitoBean SqsPositionConsumer sqsPositionConsumer;
    @MockitoBean SqsAirportEventProducer sqsAirportEventProducer;

    @Autowired BtsScheduleRepository btsScheduleRepository;
    @Autowired PredictionProperties predictionProperties;
    @Autowired BtsScheduleHealthIndicator btsScheduleHealthIndicator;

    @Test
    void predictionIsEnabledInTheShippedConfiguration() {
        assertThat(predictionProperties.enabled())
                .as("skytrack.prediction.enabled gates the BTS repository; false means the "
                        + "pipeline silently emits zero predictions")
                .isTrue();
    }

    @Test
    void wiredBtsScheduleRepositoryHoldsRecords() {
        assertThat(btsScheduleRepository.size())
                .as("Spring-wired BTS repository must carry schedule records for "
                        + "OutboundScheduleResolver to resolve any rotation")
                .isGreaterThan(0);
    }

    /**
     * Guards registration rather than logic — the indicator's own behaviour is covered by
     * {@link BtsScheduleHealthIndicatorTest}. A component that is never component-scanned reports
     * nothing, which would reproduce the silence it exists to break.
     */
    @Test
    void btsScheduleHealthIndicatorIsRegisteredAndReportsTheLoadedSchedule() {
        Health health = btsScheduleHealthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("records", btsScheduleRepository.size());
    }
}

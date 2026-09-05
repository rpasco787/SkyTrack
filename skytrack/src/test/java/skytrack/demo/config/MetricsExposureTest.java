package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import skytrack.demo.sqs.SqsAirportEventProducer;
import skytrack.demo.sqs.SqsPositionConsumer;
import skytrack.demo.sqs.SqsPositionProducer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the observability <em>wiring</em>: that the Prometheus scrape endpoint is exposed, that
 * the health probes Kubernetes/compose would hit exist, and (from Task 2 on) that every custom
 * pipeline metric is registered at startup rather than on first use — Grafana panels for a
 * metric that only appears after the first landing would show "No data" for the first minutes
 * of every run.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "skytrack.prediction.bts-csv-path=skytrack/src/test/resources/backtest/bts-fixture-2026-03-09.csv")
class MetricsExposureTest {

    @MockitoBean SqsPositionProducer sqsPositionProducer;
    @MockitoBean SqsPositionConsumer sqsPositionConsumer;
    @MockitoBean SqsAirportEventProducer sqsAirportEventProducer;

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void prometheusEndpointIsExposedAndTaggedWithApplication() throws Exception {
        var response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        // A JVM metric proves the registry is wired; the tag proves management.metrics.tags applied.
        assertThat(response.body())
                .contains("jvm_memory_used_bytes")
                .contains("application=\"skytrack\"");
    }

    @Test
    void livenessAndReadinessProbesAreExposed() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void customPipelineMetricsAreRegisteredAtStartup() throws Exception {
        var body = get("/actuator/prometheus").body();

        // Exact exported names — deploy/grafana/dashboards/skytrack.json queries these strings.
        assertThat(body)
                .contains("skytrack_positions_consumed_total")
                .contains("skytrack_landings_detected_total")
                .contains("skytrack_predictions_total{")
                .contains("classification=\"SEVERE\"")
                .contains("skytrack_schedule_resolution_seconds_bucket{")
                .contains("outcome=\"resolved\"");
    }
}

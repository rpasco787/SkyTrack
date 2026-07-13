package skytrack.demo.parquet;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.PredictedDelayEvent;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PredictionParquetRowTest {

    private static final Instant CREATED = Instant.parse("2026-03-09T20:30:00Z");

    private PredictedDelayEvent event(Long actualDelay) {
        return new PredictedDelayEvent(
                "UAL1234", "N12345", "ORD",
                "UA", "5678",
                1773088200L, 1773090000L, 2700L, 960L,
                DelayClassification.MINOR, actualDelay, "BTS_REPLAY", CREATED);
    }

    @Test
    void shouldMapAllFieldsFromEvent() {
        PredictionParquetRow row = PredictionParquetRow.from(event(900L));

        assertThat(row.inboundCallsign()).isEqualTo("UAL1234");
        assertThat(row.tailNumber()).isEqualTo("N12345");
        assertThat(row.departureAirportIata()).isEqualTo("ORD");
        assertThat(row.outboundCarrier()).isEqualTo("UA");
        assertThat(row.outboundFlightNumber()).isEqualTo("5678");
        assertThat(row.observedInboundArrivalEpoch()).isEqualTo(1773088200L);
        assertThat(row.outboundScheduledDepEpoch()).isEqualTo(1773090000L);
        assertThat(row.minTurnaroundSeconds()).isEqualTo(2700L);
        assertThat(row.predictedDelaySeconds()).isEqualTo(960L);
        assertThat(row.actualDelaySeconds()).isEqualTo(900L);
        assertThat(row.predictedClassification()).isEqualTo("MINOR");
        assertThat(row.confidence()).isEqualTo("BTS_REPLAY");
        assertThat(row.createdAtEpochMillis()).isEqualTo(CREATED.toEpochMilli());
    }

    @Test
    void shouldTolerateNullActualDelay() {
        PredictionParquetRow row = PredictionParquetRow.from(event(null));

        assertThat(row.actualDelaySeconds()).isNull();
        assertThat(row.predictedClassification()).isEqualTo("MINOR");
    }

    @Test
    void shouldRoundTripThroughParquet() throws Exception {
        var row = PredictionParquetRow.from(event(900L));
        var serializer = new ParquetSerializer();
        byte[] bytes = serializer.serializePredictions(java.util.List.of(row));
        assertThat(bytes).isNotEmpty();
    }
}

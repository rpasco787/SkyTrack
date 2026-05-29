package skytrack.demo.parquet;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.DelayClassification;
import skytrack.demo.model.DelayEvent;
import skytrack.demo.model.FlightCategory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DelayParquetRowTest {

    @Test
    void shouldMapAllFieldsFromDelayEvent() {
        Instant created = Instant.parse("2026-05-29T14:30:00Z");
        var event = new DelayEvent(
                "abc123", "UAL456", "UA", "456",
                "KORD", "ORD",
                1748528400L, 1748527500L, 900L,
                DelayClassification.MINOR, "AEROAPI", created,
                FlightCategory.MVFR, 4.0, 1500, 18);

        DelayParquetRow row = DelayParquetRow.from(event);

        assertThat(row.icao24()).isEqualTo("abc123");
        assertThat(row.arrivalAirportIata()).isEqualTo("ORD");
        assertThat(row.actualArrivalTime()).isEqualTo(1748528400L);
        assertThat(row.scheduledArrivalTime()).isEqualTo(1748527500L);
        assertThat(row.delaySeconds()).isEqualTo(900L);
        assertThat(row.classification()).isEqualTo("MINOR");
        assertThat(row.resolutionMethod()).isEqualTo("AEROAPI");
        assertThat(row.createdAtEpochMillis()).isEqualTo(created.toEpochMilli());
        assertThat(row.flightCategory()).isEqualTo("MVFR");
        assertThat(row.visibilityStatuteMiles()).isEqualTo(4.0);
        assertThat(row.ceilingFeet()).isEqualTo(1500);
    }

    @Test
    void shouldTolerateNullableFields() {
        var event = new DelayEvent(
                "abc123", "UAL456", null, null,
                "KORD", "ORD",
                1748528400L, null, null,
                null, "UNRESOLVED", Instant.parse("2026-05-29T14:30:00Z"),
                null, null, null, null);

        DelayParquetRow row = DelayParquetRow.from(event);

        assertThat(row.scheduledArrivalTime()).isNull();
        assertThat(row.delaySeconds()).isNull();
        assertThat(row.classification()).isNull();
        assertThat(row.flightCategory()).isNull();
        assertThat(row.ceilingFeet()).isNull();
    }
}

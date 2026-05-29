package skytrack.demo.parquet;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParquetSerializerTest {

    private final ParquetSerializer serializer = new ParquetSerializer();

    private static DelayParquetRow row(String icao, String iata, Long delay) {
        return new DelayParquetRow(icao, "UAL1", "UA", "1",
                "K" + iata, iata, 1748528400L, 1748527500L, delay,
                "MAJOR", "AEROAPI", 1748528400000L, "IFR", 2.0, 800, 12);
    }

    @Test
    void shouldRoundTripRowsThroughParquetBytes() throws IOException {
        List<DelayParquetRow> rows = List.of(
                row("a1", "ORD", 900L),
                row("b2", "ATL", 1800L));

        byte[] bytes = serializer.serialize(rows);
        assertThat(bytes).isNotEmpty();

        List<DelayParquetRow> readBack = serializer.deserialize(bytes);
        assertThat(readBack).hasSize(2);
        assertThat(readBack.get(0).arrivalAirportIata()).isEqualTo("ORD");
        assertThat(readBack.get(1).delaySeconds()).isEqualTo(1800L);
    }

    @Test
    void shouldSerializeEmptyListToValidParquet() throws IOException {
        byte[] bytes = serializer.serialize(List.of());
        assertThat(bytes).isNotEmpty(); // Parquet still writes header + footer
        assertThat(serializer.deserialize(bytes)).isEmpty();
    }
}

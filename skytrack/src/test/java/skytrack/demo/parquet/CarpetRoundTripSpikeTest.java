package skytrack.demo.parquet;

import com.jerolba.carpet.CarpetReader;
import com.jerolba.carpet.CarpetWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CarpetRoundTripSpikeTest {

    record Row(String id, long value, Long nullableValue, String label) {}

    @Test
    void shouldRoundTripRecordsThroughParquet(@TempDir Path tempDir) throws IOException {
        List<Row> rows = List.of(
                new Row("a", 1L, 10L, "first"),
                new Row("b", 2L, null, "second"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var writer = new CarpetWriter<>(out, Row.class)) {
            writer.write(rows);
        }
        assertThat(out.size()).isGreaterThan(0);

        // Parquet reads need random access -> write bytes to a temp file first.
        Path file = tempDir.resolve("spike.parquet");
        Files.write(file, out.toByteArray());

        List<Row> readBack = new CarpetReader<>(file.toFile(), Row.class).toList();

        assertThat(readBack).hasSize(2);
        assertThat(readBack.get(0).id()).isEqualTo("a");
        assertThat(readBack.get(0).nullableValue()).isEqualTo(10L);
        assertThat(readBack.get(1).nullableValue()).isNull();
    }
}

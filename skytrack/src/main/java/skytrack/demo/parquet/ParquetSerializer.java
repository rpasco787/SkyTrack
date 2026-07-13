package skytrack.demo.parquet;

import com.jerolba.carpet.CarpetReader;
import com.jerolba.carpet.CarpetWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class ParquetSerializer {

    public byte[] serialize(List<DelayParquetRow> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var writer = new CarpetWriter<>(out, DelayParquetRow.class)) {
            writer.write(rows);
        }
        return out.toByteArray();
    }

    public byte[] serializePredictions(List<PredictionParquetRow> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var writer = new CarpetWriter<>(out, PredictionParquetRow.class)) {
            writer.write(rows);
        }
        return out.toByteArray();
    }

    /** Parquet requires random access on read, so bytes are staged to a temp file. */
    public List<DelayParquetRow> deserialize(byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile("skytrack-parquet-", ".parquet");
        try {
            Files.write(tmp, bytes);
            return new CarpetReader<>(tmp.toFile(), DelayParquetRow.class).toList();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
